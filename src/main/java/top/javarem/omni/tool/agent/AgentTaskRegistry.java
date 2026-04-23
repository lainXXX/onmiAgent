package top.javarem.omni.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent 异步任务注册表
 * 管理所有子 Agent 的 Future 任务，支持生命周期控制、查询和权限校验
 */
@Slf4j
@Component
public class AgentTaskRegistry {

    public record AgentTaskRecord(
            String taskId,          // 新增：任务自身的ID
            java.util.concurrent.Future<AgentResult> future,
            String userId,
            String sessionId,
            String agentType,
            String description,     // 任务描述（3-5词）
            String prompt,          // 原始 prompt
            String worktreePath,    // git worktree 路径
            long createdAt
    ) {}

    // 活跃任务缓存
    private final Map<String, AgentTaskRecord> registry = new ConcurrentHashMap<>();

    // 用于 resume 机制：previousTaskId -> currentTaskId 的映射
    private final Map<String, String> resumeChain = new ConcurrentHashMap<>();

    /**
     * 【优化新增】允许外部显式传入 taskId，解决 Worktree 与 Task ID 同步问题
     */
    public void register(
            String taskId,
            java.util.concurrent.Future<AgentResult> future,
            String userId,
            String sessionId,
            String agentType,
            String description,
            String prompt
    ) {
        registry.put(taskId, new AgentTaskRecord(taskId, future, userId, sessionId, agentType, description, prompt, null, System.currentTimeMillis()));
        log.info("[AgentTaskRegistry] 注册任务(外部ID): taskId={}, agentType={}, description={}, userId={}", taskId, agentType, description, userId);
    }

    /**
     * 兼容旧版：自动生成 ID 并注册任务
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
        register(taskId, future, userId, sessionId, agentType, description, prompt);
        return taskId;
    }

    /**
     * 【优化新增】允许外部显式传入 taskId 并注册带 Worktree 的任务
     */
    public void registerWithWorktree(
            String taskId,
            java.util.concurrent.Future<AgentResult> future,
            String userId,
            String sessionId,
            String agentType,
            String description,
            String prompt,
            String worktreePath
    ) {
        registry.put(taskId, new AgentTaskRecord(taskId, future, userId, sessionId, agentType, description, prompt, worktreePath, System.currentTimeMillis()));
        log.info("[AgentTaskRegistry] 注册任务(带worktree): taskId={}, agentType={}, worktree={}", taskId, agentType, worktreePath);
    }

    /**
     * 兼容旧版：自动生成 ID 并注册带 Worktree 的任务
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
        registerWithWorktree(taskId, future, userId, sessionId, agentType, description, prompt, worktreePath);
        return taskId;
    }

    /**
     * 获取任务记录
     */
    public AgentTaskRecord get(String taskId) {
        return registry.get(taskId);
    }

    /**
     * 检查任务是否存在（只检查当前活跃和未清理的任务）
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
        log.info("[AgentTaskRegistry] Resume链路建立: {} -> {}", previousTaskId, currentTaskId);
    }

    /**
     * 获取 resume 目标
     */
    public String getResumeTarget(String taskId) {
        return resumeChain.get(taskId);
    }

    /**
     * 安全移除任务记录并清理相关的垃圾映射
     */
    public AgentTaskRecord remove(String taskId) {
        // 清理当前 taskId 作为起点的 resume 链
        resumeChain.remove(taskId);

        // 也可以选择性清理当前 taskId 作为终点的链 (防止内存泄漏)
        resumeChain.entrySet().removeIf(entry -> entry.getValue().equals(taskId));

        AgentTaskRecord removedRecord = registry.remove(taskId);
        if (removedRecord != null) {
            log.debug("[AgentTaskRegistry] 任务记录已移除: taskId={}", taskId);
        }
        return removedRecord;
    }

    /**
     * 获取当前活跃任务数量
     */
    public int size() {
        return registry.size();
    }
}