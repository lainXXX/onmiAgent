package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.model.task.TaskEntity;
import top.javarem.omni.repository.chat.MemoryRepository;
import top.javarem.omni.service.task.TaskService;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务进度追踪Advisor
 *
 * <p>核心功能：追踪对话轮次，在模型迷失方向时提醒其更新任务进度。</p>
 *
 * <h3>问题背景：</h3>
 * <p>在多步骤任务中，模型容易出现重复工作、跳过步骤、迷失方向等问题。</p>
 *
 * <h3>解决方案：</h3>
 * <ul>
 *   <li><b>exec_rounds</b> - 从ChatMemory消息数推算对话轮次</li>
 *   <li><b>活跃任务</b> - 基于TaskEntity查询pending/in_progress状态</li>
 *   <li><b>Nag提醒</b> - exec_rounds >= 3时注入提醒</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-26
 * @see TaskService
 * @see TaskEntity
 * @see MemoryRepository
 */
@Component
@Slf4j
public class TaskProgressAdvisor implements BaseAdvisor {

    /**
     * Nag阈值 - 超过此值的对话轮次没有推进任务状态时触发提醒
     */
    private static final int NAG_THRESHOLD = 3;

    /**
     * 任务服务 - 提供TaskEntity查询能力
     */
    private final TaskService taskService;

    /**
     * 记忆仓库 - 用于推算对话轮次
     */
    private final MemoryRepository memoryRepository;

    /**
     * 消息聚合器（用于流式响应）
     */
    private final ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();

    /**
     * Advisor执行顺序
     *
     * <p>设置为在LifecycleToolCallAdvisor之后执行。</p>
     */
    private static final int ORDER = Integer.MAX_VALUE - 100;

    /**
     * 构造方法
     */
    public TaskProgressAdvisor(TaskService taskService, MemoryRepository memoryRepository) {
        this.taskService = taskService;
        this.memoryRepository = memoryRepository;
        log.info("[TaskProgressAdvisor] 初始化完成, ORDER={}, NAG_THRESHOLD={}",
            ORDER, NAG_THRESHOLD);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ==================== BaseAdvisor 实现 ====================

    /**
     * 前置处理 - 在LLM调用之前执行
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userId = getUserId(request);
        String sessionId = getSessionId(request);

        log.debug("[TaskProgressAdvisor] before: userId={}, sessionId={}", userId, sessionId);

        // 步骤1：获取活跃任务数量
        int activeCount = countActiveTasks(userId, sessionId);

        // 步骤2：从ChatMemory推算exec_rounds
        int execRounds = calculateExecRounds(sessionId);

        if (activeCount == 0) {
            // 无活跃任务，不触发提醒
            log.debug("[TaskProgressAdvisor] 无活跃任务, userId={}, sessionId={}", userId, sessionId);
        } else if (execRounds >= NAG_THRESHOLD) {
            // 有活跃任务且达到阈值，注入提醒
            log.info("[TaskProgressAdvisor] exec_rounds达到阈值({} >= {}), 注入提醒, userId={}, sessionId={}",
                execRounds, NAG_THRESHOLD, userId, sessionId);
            request = injectReminder(request, userId, sessionId, execRounds);
        }

        return request;
    }

    /**
     * 后置处理 - 在LLM调用之后执行
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        log.debug("[TaskProgressAdvisor] after: response完成");
        return response;
    }

    // ==================== Stream模式 ====================

    /**
     * 流式调用处理
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userId = getUserId(request);
        String sessionId = getSessionId(request);

        // 活跃任务检查
        int activeCount = countActiveTasks(userId, sessionId);
        int execRounds = calculateExecRounds(sessionId);

        if (activeCount > 0 && execRounds >= NAG_THRESHOLD) {
            request = injectReminder(request, userId, sessionId, execRounds);
        }

        // 执行流
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        if (flux == null) {
            return Flux.empty();
        }

        // 在流结束后处理
        return aggregator.aggregateChatClientResponse(flux, completeResponse -> {
            after(completeResponse, chain);
        });
    }

    // ==================== 核心方法 ====================

    /**
     * 注入提醒到请求中
     *
     * <p>将reminder文本作为新的用户消息追加到现有消息列表前。</p>
     */
    private ChatClientRequest injectReminder(ChatClientRequest request, String userId, String sessionId, int execRounds) {
        try {
            // 构建提醒文本
            String reminderText = buildReminder(userId, sessionId, execRounds);

            // 创建新的UserMessage
            UserMessage reminderMessage = new UserMessage(reminderText);

            // 获取所有现有消息
            List<Message> allMessages = new ArrayList<>(request.prompt().getInstructions());

            // 在消息列表最后面插入提醒消息
            allMessages.add(reminderMessage);

            // 使用messages()方法重建prompt
            Prompt newPrompt = request.prompt().mutate()
                .messages(allMessages)
                .build();

            log.info("[TaskProgressAdvisor] 提醒已注入, exec_rounds={}", execRounds);

            return request.mutate().prompt(newPrompt).build();

        } catch (Exception e) {
            log.warn("[TaskProgressAdvisor] 注入提醒时发生异常: {}", e.getMessage(), e);
            return request;
        }
    }

    /**
     * 构建提醒文本
     */
    private String buildReminder(String userId, String sessionId, int execRounds) {
        StringBuilder sb = new StringBuilder();

        sb.append("<system_reminder>\n");

        // 获取进行中的任务
        List<TaskEntity> inProgressTasks = taskService.list(userId, sessionId, TaskEntity.STATUS_IN_PROGRESS, 1, 10);

        if (!inProgressTasks.isEmpty()) {
            TaskEntity task = inProgressTasks.get(0);
            sb.append("[当前焦点]\n");
            sb.append("  🔄 ").append(task.subject()).append("\n");
            sb.append("  状态: 进行中（已 ").append(execRounds).append(" 轮未更新）\n");
            sb.append("  是否还在执行？当前进度如何？\n\n");
        } else {
            sb.append("⚠️ 没有正在执行的任务\n\n");
        }

        sb.append("请调用 TaskList 查看完整任务列表。\n");
        sb.append("请调用 TaskUpdate 汇报进度或完成任务，再继续执行。\n");
        sb.append("system_reminder\n\n");

        return sb.toString();
    }

    // ==================== 基于TaskEntity的查询 ====================

    /**
     * 获取活跃任务数量
     *
     * <p>活跃状态 = pending + in_progress。</p>
     */
    private int countActiveTasks(String userId, String sessionId) {
        var stats = taskService.stats(userId, sessionId);
        int pending = stats.getOrDefault(TaskEntity.STATUS_PENDING, 0);
        int inProgress = stats.getOrDefault(TaskEntity.STATUS_IN_PROGRESS, 0);
        return pending + inProgress;
    }

    /**
     * 从ChatMemory推算exec_rounds
     *
     * <p>每2条消息算一轮（用户+助手）。</p>
     */
    private int calculateExecRounds(String sessionId) {
        try {
            List<Message> messages = memoryRepository.findMessagesByConversationId(sessionId);
            // 每2条消息算一轮（用户消息 + 助手回复）
            int rounds = messages.size() / 2;
            return rounds;
        } catch (Exception e) {
            log.warn("[TaskProgressAdvisor] calculateExecRounds失败: {}", e.getMessage());
            return 0;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从请求上下文中获取用户ID
     */
    private String getUserId(ChatClientRequest request) {
        Object userId = request.context().get("userId");
        return userId != null ? userId.toString() : "default";
    }

    /**
     * 从请求上下文中获取会话ID
     */
    private String getSessionId(ChatClientRequest request) {
        Object sessionId = request.context().get(ChatMemory.CONVERSATION_ID);
        return sessionId != null ? sessionId.toString() : "default";
    }
}
