package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bash 响应格式化器
 *
 * <p>一体化输出处理：ANSI过滤、截断、错误/成功信息构建、退出码语义解释。</p>
 */
@Component
@Slf4j
public class ResponseFormatter {

    private final CommandSemantics commandSemantics;

    public ResponseFormatter(CommandSemantics commandSemantics) {
        this.commandSemantics = commandSemantics;
    }

    /**
     * 格式化成功响应
     */
    public String formatSuccess(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "✅ 命令执行成功\n\n(无输出)";
        }

        String cleaned = stripAnsi(rawOutput);
        String truncated = truncateIfNeeded(cleaned);

        return "✅ 命令执行成功\n\n" + truncated;
    }

    /**
     * 格式化超时响应
     */
    public String formatTimeout(String rawOutput, long timeoutMs) {
        String cleaned = rawOutput != null ? stripAnsi(rawOutput) : "(无输出)";
        String truncated = truncateIfNeeded(cleaned);
        long timeoutSeconds = timeoutMs / 1000;

        return String.format("""
            ⏰ 命令执行超时

            超时时间: %d 秒
            已输出内容（可能被截断）:

            %s
            """, timeoutSeconds, truncated);
    }

    /**
     * 格式化执行失败响应（带退出码语义解释）
     */
    public String formatError(String rawOutput, int exitCode) {
        return formatError(rawOutput, exitCode, null);
    }

    /**
     * 格式化执行失败响应（带退出码语义解释）
     *
     * @param rawOutput 原始输出
     * @param exitCode 退出码
     * @param command 原始命令（用于语义解释）
     */
    public String formatError(String rawOutput, int exitCode, String command) {
        String cleaned = rawOutput != null ? stripAnsi(rawOutput) : "(无输出)";
        String truncated = truncateIfNeeded(cleaned);

        String semantic = null;
        if (command != null && commandSemantics != null) {
            semantic = commandSemantics.interpret(command, exitCode, rawOutput);
        }

        if (semantic != null) {
            return String.format("""
                ❌ %s

                退出码: %d
                语义: %s

                输出:
                %s
                """, "命令执行失败", exitCode, semantic, truncated);
        }

        return String.format("""
            ❌ 命令执行失败

            退出码: %d

            输出:
            %s
            """, exitCode, truncated);
    }

    /**
     * 格式化待审批响应
     */
    public String formatPending(String ticketId, String message) {
        return String.format("""
            ⏸️ %s

            票根ID: %s

            请在界面中审批此命令，或通过以下方式批准：
            - 在界面中点击批准按钮
            - 调用 POST /approval 接口，body: {"ticketId": "%s", "approved": true, "command": "<完整命令>"}
            """, message, ticketId, ticketId);
    }

    /**
     * 格式化后台命令启动响应
     */
    public String formatBackgroundStarted(String pid, String command) {
        return String.format("✅ 后台命令已启动\n\nPID: %s\n命令: %s", pid, command);
    }

    /**
     * 格式化破坏性命令警告（附加到成功/错误输出中）
     */
    public String formatDestructiveWarning(String command) {
        return String.format("⚠️ 【警告】破坏性命令: %s\n", command);
    }

    /**
     * 去除 ANSI 转义序列
     */
    private String stripAnsi(String input) {
        if (input == null) return "";
        return input
            .replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "")
            .replaceAll("\u001B\\][^\u0007]+\u0007", "")
            .replaceAll("\u001B", "");
    }

    /**
     * 智能截断：保留头部和尾部
     */
    private String truncateIfNeeded(String output) {
        if (output.length() <= BashConstants.MAX_OUTPUT_CHARS) {
            return output;
        }

        int keepStart = BashConstants.KEEP_START;
        int keepEnd = BashConstants.KEEP_END;

        String head = output.substring(0, Math.min(keepStart, output.length()));
        String tail = output.substring(Math.max(output.length() - keepEnd, 0));

        return head + "\n\n... [中间内容已截断] ...\n\n" + tail;
    }
}
