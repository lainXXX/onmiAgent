package top.javarem.skillDemo.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent 异步任务注册表
 * 管理所有子 Agent 的 Future 任务，支持查询和权限校验
 */
@Slf4j
@Component
public class AgentTaskRegistry {

    record AgentTaskRecord(
        java.util.concurrent.Future<AgentResult> future,
        String userId,
        String sessionId,
        String agentType,
        long createdAt
    ) {}

    private final Map<String, AgentTaskRecord> registry = new ConcurrentHashMap<>();

    /**
     * 注册子 Agent 任务
     * @return 生成的 taskId
     */
    public String register(
            java.util.concurrent.Future<AgentResult> future,
            String userId,
            String sessionId,
            String agentType
    ) {
        String taskId = java.util.UUID.randomUUID().toString();
        registry.put(taskId, new AgentTaskRecord(future, userId, sessionId, agentType, System.currentTimeMillis()));
        log.info("[AgentTaskRegistry] 注册任务: taskId={}, agentType={}, userId={}", taskId, agentType, userId);
        return taskId;
    }

    /**
     * 获取任务记录
     */
    public AgentTaskRecord get(String taskId) {
        return registry.get(taskId);
    }

    /**
     * 检查任务是否存在
     */
    public boolean exists(String taskId) {
        return registry.containsKey(taskId);
    }

    /**
     * 权限校验：检查用户是否有权访问该任务
     */
    public boolean isOwner(String taskId, String userId, String sessionId) {
        AgentTaskRecord record = registry.get(taskId);
        if (record == null) {
            return false;
        }
        return record.userId().equals(userId) && record.sessionId().equals(sessionId);
    }

    /**
     * 获取任务状态
     */
    public String getStatus(String taskId) {
        AgentTaskRecord record = registry.get(taskId);
        if (record == null) {
            return "not_found";
        }
        if (record.future().isDone()) {
            return "completed";
        }
        return "running";
    }

    /**
     * 移除任务记录
     */
    public AgentTaskRecord remove(String taskId) {
        return registry.remove(taskId);
    }

    /**
     * 获取当前注册的任务数量
     */
    public int size() {
        return registry.size();
    }
}
