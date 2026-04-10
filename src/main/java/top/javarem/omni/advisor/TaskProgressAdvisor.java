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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.model.task.TaskEntity;
import top.javarem.omni.repository.chat.MemoryRepository;
import top.javarem.omni.service.task.TaskService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * 轮次阈值 - 超过此值的对话轮次没有推进任务状态时触发提醒
     */
    private static final int ROUNDS_THRESHOLD = 3;

    /**
     * 时间阈值 - 任务超过此时间未更新，认为可能已停滞
     */
    private static final long TIME_THRESHOLD_MS = 5 * 60 * 1000;

    /**
     * 提醒冷却时间 - 同一会话两次提醒的最小间隔
     */
    private static final long NAG_COOLDOWN_MS = 10 * 60 * 1000;

    /**
     * 硬性上限阈值 - 超过此值直接强制终止对话，防止无限循环
     */
    private static final int HARD_LIMIT = 100;

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
     * 每会话的上次提醒时间（用于冷却机制）
     */
    private final Map<String, Long> lastNagTime = new ConcurrentHashMap<>();

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
        log.info("[TaskProgressAdvisor] 初始化完成, ORDER={}, ROUNDS_THRESHOLD={}, TIME_THRESHOLD_MS={}, NAG_COOLDOWN_MS={}, HARD_LIMIT={}",
            ORDER, ROUNDS_THRESHOLD, TIME_THRESHOLD_MS, NAG_COOLDOWN_MS, HARD_LIMIT);
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

        // 1. 获取进行中的任务
        List<TaskEntity> inProgressTasks = taskService.list(
            userId, sessionId, TaskEntity.STATUS_IN_PROGRESS, 1, 1
        );

        if (inProgressTasks.isEmpty()) {
            log.debug("[TaskProgressAdvisor] 无进行中任务，跳过, userId={}, sessionId={}", userId, sessionId);
            return request;
        }

        TaskEntity task = inProgressTasks.get(0);

        // 2. 硬性上限检查 - 防止无限循环
        int totalRounds = calculateTotalRounds(sessionId);
        if (totalRounds >= HARD_LIMIT) {
            log.error("[TaskProgressAdvisor] 总轮次达到硬性上限({} >= {}), 强制终止对话, userId={}, sessionId={}",
                totalRounds, HARD_LIMIT, userId, sessionId);
            throw new IllegalStateException(String.format(
                "对话轮次已达到上限(%d)，可能存在无限循环。建议使用 TaskList 查看任务状态。", HARD_LIMIT));
        }

        // 3. 计算"任务更新后"的对话轮次
        int roundsSinceUpdate = calculateRoundsSince(sessionId, task.updatedAt());

        // 4. 任务活性检测：更新时间在阈值内，认为有活性
        if (hasRecentActivity(task)) {
            log.debug("[TaskProgressAdvisor] 任务有活性，updatedAt={}, roundsSinceUpdate={}",
                task.updatedAt(), roundsSinceUpdate);
            return request;
        }

        // 5. 轮次阈值检查
        if (roundsSinceUpdate < ROUNDS_THRESHOLD) {
            log.debug("[TaskProgressAdvisor] 轮次未达阈值，roundsSinceUpdate={} < {}",
                roundsSinceUpdate, ROUNDS_THRESHOLD);
            return request;
        }

        // 6. 冷却检查
        if (!shouldNag(sessionId)) {
            log.debug("[TaskProgressAdvisor] 提醒冷却中，sessionId={}", sessionId);
            return request;
        }

        // 7. 注入提醒
        log.info("[TaskProgressAdvisor] 注入提醒，task={}, roundsSinceUpdate={}",
            task.subject(), roundsSinceUpdate);
        request = injectReminder(request, task, roundsSinceUpdate);
        recordNag(sessionId);

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

        // 1. 获取进行中任务
        List<TaskEntity> inProgressTasks = taskService.list(
            userId, sessionId, TaskEntity.STATUS_IN_PROGRESS, 1, 1
        );

        if (inProgressTasks.isEmpty()) {
            log.debug("[TaskProgressAdvisor] [Stream] 无进行中任务，跳过");
        } else {
            TaskEntity task = inProgressTasks.get(0);

            // 2. 硬性上限检查
            int totalRounds = calculateTotalRounds(sessionId);
            if (totalRounds >= HARD_LIMIT) {
                log.error("[TaskProgressAdvisor] [Stream] 总轮次达到硬性上限({} >= {})",
                    totalRounds, HARD_LIMIT);
                throw new IllegalStateException(String.format(
                    "对话轮次已达到上限(%d)，可能存在无限循环。", HARD_LIMIT));
            }

            // 3. 计算任务更新后的轮次
            int roundsSinceUpdate = calculateRoundsSince(sessionId, task.updatedAt());

            // 4. 活性 + 阈值 + 冷却检查
            if (!hasRecentActivity(task) && roundsSinceUpdate >= ROUNDS_THRESHOLD && shouldNag(sessionId)) {
                log.info("[TaskProgressAdvisor] [Stream] 注入提醒，task={}, roundsSinceUpdate={}",
                    task.subject(), roundsSinceUpdate);
                request = injectReminder(request, task, roundsSinceUpdate);
                recordNag(sessionId);
            }
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
     * <p>将 reminder 作为新的 UserMessage 追加到 messages 列表末尾。</p>
     */
    private ChatClientRequest injectReminder(ChatClientRequest request, TaskEntity task, int roundsSinceUpdate) {
        try {
            String reminderText = buildReminder(task, roundsSinceUpdate);
            UserMessage reminderMessage = new UserMessage(reminderText);

            // ✅ 正确做法：追加到 getInstructions() 末尾
            List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
            messages.add(reminderMessage);

            Prompt newPrompt = request.prompt().mutate()
                .messages(messages)
                .build();

            log.info("[TaskProgressAdvisor] 提醒已注入, task={}, roundsSinceUpdate={}", task.subject(), roundsSinceUpdate);
            return request.mutate().prompt(newPrompt).build();

        } catch (Exception e) {
            log.warn("[TaskProgressAdvisor] 注入提醒异常: {}", e.getMessage(), e);
            return request;
        }
    }

    /**
     * 构建提醒文本
     */
    private String buildReminder(TaskEntity task, int roundsSinceUpdate) {
        return String.format("""
            <system_reminder>
            [任务进度提醒]
            当前进行中任务：[%s]
            已 %d 轮对话未更新任务状态。
            建议：请调用 TaskList 查看任务列表，TaskUpdate 汇报进度或完成任务。
            </system_reminder>
            """, task.subject(), roundsSinceUpdate);
    }

    // ==================== 基于TaskEntity的查询 ====================

    /**
     * 计算自任务上次更新以来的对话轮次
     */
    private int calculateRoundsSince(String sessionId, LocalDateTime taskUpdatedAt) {
        try {
            List<Message> messages = memoryRepository.findMessagesByConversationId(sessionId);
            long cutoffMs = taskUpdatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            return (int) messages.stream()
                .filter(m -> m.getMetadata() != null)
                .filter(m -> {
                    Object ts = m.getMetadata().get("timestamp");
                    if (ts == null) return false;
                    long msgTime = (ts instanceof Long) ? (Long) ts : Long.parseLong(ts.toString());
                    return msgTime > cutoffMs;
                })
                .count() / 2;
        } catch (Exception e) {
            log.warn("[TaskProgressAdvisor] calculateRoundsSince 失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 计算总对话轮次（用于硬性上限检查）
     */
    private int calculateTotalRounds(String sessionId) {
        try {
            return memoryRepository.findMessagesByConversationId(sessionId).size() / 2;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 判断任务是否有活性（更新时间在阈值内）
     */
    private boolean hasRecentActivity(TaskEntity task) {
        long age = System.currentTimeMillis() - task.updatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return age < TIME_THRESHOLD_MS;
    }

    /**
     * 冷却检查 - 距离上次提醒是否已超过冷却时间
     */
    private boolean shouldNag(String sessionId) {
        long lastNag = lastNagTime.getOrDefault(sessionId, 0L);
        return System.currentTimeMillis() - lastNag > NAG_COOLDOWN_MS;
    }

    /**
     * 记录提醒时间
     */
    private void recordNag(String sessionId) {
        lastNagTime.put(sessionId, System.currentTimeMillis());
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
