package top.javarem.omni.tool.bash;

/**
 * 响应构建器
 */
public final class ResponseBuilder {

    private ResponseBuilder() {}

    public static String buildError(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }

    public static String buildDenied(String command, String reason) {
        return "⛔ 命令执行被拒绝\n\n" +
                "执行的命令: " + command + "\n\n" +
                "原因: " + reason + "\n\n" +
                "💡 为了系统安全，某些高危命令需要人工审批才能执行。";
    }

    public static String buildSuicideBlocked(String command) {
        return "⛔ 命令执行被拒绝\n\n" +
                "执行的命令: " + command + "\n\n" +
                "原因: 禁止终止当前进程自身 (PID: " + BashConstants.CURRENT_PID + ")\n\n" +
                "💡 为了系统安全，不允许终止当前 Agent 进程。";
    }
}
