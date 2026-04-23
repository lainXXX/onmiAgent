package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import top.javarem.omni.repository.chat.ChatMemoryRepository;
import top.javarem.omni.model.task.TaskEntity;
import top.javarem.omni.service.task.TaskService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class TaskProgressAdvisor implements BaseAdvisor {

    private static final int ROUNDS_THRESHOLD = 3;
    private static final long TIME_THRESHOLD_MS = 5 * 60 * 1000;
    private static final long NAG_COOLDOWN_MS = 10 * 60 * 1000;
    private static final int HARD_LIMIT = 100000;
    private static final int ORDER = Integer.MAX_VALUE - 100;

    private final TaskService taskService;
    private final ChatMemoryRepository chatMemoryRepository;
    private final Map<String, Long> lastNagTime = new ConcurrentHashMap<>();

    public TaskProgressAdvisor(TaskService taskService, ChatMemoryRepository chatMemoryRepository) {
        this.taskService = taskService;
        this.chatMemoryRepository = chatMemoryRepository;
        log.info("[TaskProgressAdvisor] 初始化完成, ORDER={}", ORDER);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ==================== BaseAdvisor 实现 ====================

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return doBeforeLogic(request);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private ChatClientRequest doBeforeLogic(ChatClientRequest request) {
        String userId = getUserId(request);
        String sessionId = getSessionId(request);

        log.debug("[TaskProgressAdvisor] doBeforeLogic: userId={}, sessionId={}", userId, sessionId);

        List<TaskEntity> inProgressTasks = taskService.list(
            userId, sessionId, TaskEntity.STATUS_IN_PROGRESS, 1, 1
        );

        if (inProgressTasks.isEmpty()) {
            log.debug("[TaskProgressAdvisor] 无进行中任务，跳过");
            return request;
        }

        TaskEntity task = inProgressTasks.get(0);

        // 硬性上限检查
        int totalRounds = calculateTotalRounds(sessionId);
        if (totalRounds >= HARD_LIMIT) {
            log.error("[TaskProgressAdvisor] 总轮次达到硬性上限({} >= {})", totalRounds, HARD_LIMIT);
            throw new IllegalStateException(String.format(
                "对话轮次已达到上限(%d)，可能存在无限循环。建议使用 TaskList 查看任务状态。", HARD_LIMIT));
        }

        int roundsSinceUpdate = calculateRoundsSince(sessionId, task.updatedAt());

        // 任务活性检测
        if (hasRecentActivity(task)) {
            log.debug("[TaskProgressAdvisor] 任务有活性，updatedAt={}, roundsSinceUpdate={}",
                task.updatedAt(), roundsSinceUpdate);
            return request;
        }

        // 轮次阈值检查
        if (roundsSinceUpdate < ROUNDS_THRESHOLD) {
            log.debug("[TaskProgressAdvisor] 轮次未达阈值，roundsSinceUpdate={} < {}",
                roundsSinceUpdate, ROUNDS_THRESHOLD);
            return request;
        }

        // 冷却检查
        if (!shouldNag(sessionId)) {
            log.debug("[TaskProgressAdvisor] 提醒冷却中，sessionId={}", sessionId);
            return request;
        }

        // 注入提醒
        log.info("[TaskProgressAdvisor] 注入提醒，task={}, roundsSinceUpdate={}",
            task.subject(), roundsSinceUpdate);
        request = injectReminder(request, task, roundsSinceUpdate);
        recordNag(sessionId);

        return request;
    }

    private ChatClientRequest injectReminder(ChatClientRequest request, TaskEntity task, int roundsSinceUpdate) {
        try {
            String reminderText = buildReminder(task, roundsSinceUpdate);
            UserMessage reminderMessage = new UserMessage(reminderText);

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

    // ==================== 查询方法 ====================

    private int calculateRoundsSince(String sessionId, LocalDateTime taskUpdatedAt) {
        try {
            List<Message> messages = chatMemoryRepository.getCleanContext(sessionId);
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

    private int calculateTotalRounds(String sessionId) {
        try {
            return chatMemoryRepository.getCleanContext(sessionId).size() / 2;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasRecentActivity(TaskEntity task) {
        long age = System.currentTimeMillis() - task.updatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return age < TIME_THRESHOLD_MS;
    }

    private boolean shouldNag(String sessionId) {
        long lastNag = lastNagTime.getOrDefault(sessionId, 0L);
        return System.currentTimeMillis() - lastNag > NAG_COOLDOWN_MS;
    }

    private void recordNag(String sessionId) {
        lastNagTime.put(sessionId, System.currentTimeMillis());
    }

    // ==================== 工具方法 ====================

    private String getUserId(ChatClientRequest request) {
        Object userId = request.context().get("userId");
        return userId != null ? userId.toString() : "default";
    }

    private String getSessionId(ChatClientRequest request) {
        Object sessionId = request.context().get(ChatMemory.CONVERSATION_ID);
        return sessionId != null ? sessionId.toString() : "default";
    }
}
