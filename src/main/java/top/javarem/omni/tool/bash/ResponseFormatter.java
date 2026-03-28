package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bash 响应格式化器
 *
 * <p>一体化输出处理：ANSI过滤、截断、错误/成功信息构建。</p>
 */
@Component
@Slf4j
public class ResponseFormatter {

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
     * 格式化执行失败响应
     */
    public String formatError(String rawOutput, int exitCode) {
        String cleaned = rawOutput != null ? stripAnsi(rawOutput) : "(无输出)";
        String truncated = truncateIfNeeded(cleaned);

        return String.format("""
            ❌ 命令执行失败

            退出码: %d

            输出:
            %s
            """, exitCode, truncated);
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
