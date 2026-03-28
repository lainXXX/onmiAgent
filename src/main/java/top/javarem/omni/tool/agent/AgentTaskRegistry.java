package top.javarem.onmi.tool.agent;

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
        String description,     // 任务描述（3-5词）
        String prompt,         // 原始 prompt
        String worktreePath,   // git worktree 路径
        long createdAt
    ) {}

    private final Map<String, AgentTaskRecord> registry = new ConcurrentHashMap<>();

    // 用于 resume 机制：taskId -> taskId 的映射（指向当前活跃任务）
    private final Map<String, String> resumeChain = new ConcurrentHashMap<>();

    /**
     * 注册子 Agent 任务
     * @return 生成的 taskId
     */
    public String register(
            java.util.concurrent.Future<AgentResult> future,
            String userId,
            String sessionId,
            String agentType,
            String description,
            String prompt
    ) {
        String taskId = java.util.UUID.randomUUID().toString();
        registry.put(taskId, new AgentTaskRecord(future, userId, sessionId, agentType, description, prompt, null, System.currentTimeMillis()));
        log.info("[AgentTaskRegistry] 注册任务: taskId={}, agentType={}, description={}, userId={}", taskId, agentType, description, userId);
        return taskId;
    }

    /**
     * 注册子 Agent 任务（带 worktree）
     */
    public String registerWithWorktree(
            java.util.concurrent.Future<AgentResult> future,
            String userId,
            String sessionId,
            String agentType,
            String description,
            String prompt,
            String worktreePath
    ) {
        String taskId = java.util.UUID.randomUUID().toString();
        registry.put(taskId, new AgentTaskRecord(future, userId, sessionId, agentType, description, prompt, worktreePath, System.currentTimeMillis()));
        log.info("[AgentTaskRegistry] 注册任务(带worktree): taskId={}, agentType={}, worktree={}", taskId, agentType, worktreePath);
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
     * 获取 worktree 路径
     */
    public String getWorktreePath(String taskId) {
        AgentTaskRecord record = registry.get(taskId);
        return record != null ? record.worktreePath() : null;
    }

    /**
     * 建立 resume 链：previousTaskId -> currentTaskId
     */
    public void linkResume(String previousTaskId, String currentTaskId) {
        resumeChain.put(previousTaskId, currentTaskId);
        log.info("[AgentTaskRegistry] Resume链路: {} -> {}", previousTaskId, currentTaskId);
    }

    /**
     * 获取 resume 目标
     */
    public String getResumeTarget(String taskId) {
        return resumeChain.get(taskId);
    }

    /**
     * 移除任务记录
     */
    public AgentTaskRecord remove(String taskId) {
        resumeChain.remove(taskId);
        return registry.remove(taskId);
    }

    /**
     * 获取当前注册的任务数量
     */
    public int size() {
        return registry.size();
    }
}
