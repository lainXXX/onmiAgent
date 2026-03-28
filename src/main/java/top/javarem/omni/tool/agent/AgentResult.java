package top.javarem.onmi.tool.agent;

import java.util.Map;

/**
 * 子 Agent 执行结果
 */
public record AgentResult(
    String taskId,
    String agentType,
    String status,      // running, completed, failed
    String output,
    String error,
    long durationMs,
    String worktreePath,  // git worktree 路径（如果有）
    Map<String, Object> metadata  // 扩展元数据
) {
    public static AgentResult running(String taskId, String agentType) {
        return new AgentResult(taskId, agentType, "running", null, null, 0, null, null);
    }

    public static AgentResult completed(String taskId, String agentType, String output, long durationMs) {
        return new AgentResult(taskId, agentType, "completed", output, null, durationMs, null, null);
    }

    public static AgentResult completed(String taskId, String agentType, String output, long durationMs, String worktreePath) {
        return new AgentResult(taskId, agentType, "completed", output, null, durationMs, worktreePath, null);
    }

    public static AgentResult failed(String taskId, String agentType, String error) {
        return new AgentResult(taskId, agentType, "failed", null, error, 0, null, null);
    }

    public AgentResult withMetadata(String key, Object value) {
        Map<String, Object> newMeta = metadata == null ? new java.util.HashMap<>() : new java.util.HashMap<>(metadata);
        newMeta.put(key, value);
        return new AgentResult(taskId, agentType, status, output, error, durationMs, worktreePath, newMeta);
    }
}
