package top.javarem.skillDemo.tool.agent;

/**
 * 子 Agent 执行结果
 */
public record AgentResult(
    String taskId,
    String agentType,
    String status,      // running, completed, failed
    String output,
    String error,
    long durationMs
) {
    public static AgentResult running(String taskId, String agentType) {
        return new AgentResult(taskId, agentType, "running", null, null, 0);
    }

    public static AgentResult completed(String taskId, String agentType, String output, long durationMs) {
        return new AgentResult(taskId, agentType, "completed", output, null, durationMs);
    }

    public static AgentResult failed(String taskId, String agentType, String error) {
        return new AgentResult(taskId, agentType, "failed", null, error, 0);
    }
}
