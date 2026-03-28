package top.javarem.omni.tool.bash;

/**
 * 命令输出处理器（格式化 + 智能截断）
 */
public final class OutputProcessor {

    private OutputProcessor() {}

    /**
     * 处理输出
     */
    public static String process(String rawOutput, int exitCode, boolean timeout) {
        String cleanOutput = AnsiStripper.strip(rawOutput);
        StringBuilder sb = new StringBuilder();

        if (timeout) {
            sb.append("⏱️ 命令执行超时（已强制终止）\n\n");
        } else {
            sb.append(exitCode == 0 ? "✅ 命令执行成功\n\n" : "❌ 命令执行失败 (退出码: " + exitCode + ")\n\n");
        }

        sb.append("💻 命令输出:\n");
        sb.append("─────────────────────────────────────────\n");

        if (cleanOutput.isEmpty()) {
            sb.append("(无输出)");
        } else if (cleanOutput.length() <= BashConstants.MAX_OUTPUT_CHARS) {
            sb.append(cleanOutput);
        } else {
            String start = cleanOutput.substring(0, Math.min(BashConstants.KEEP_START, cleanOutput.length()));
            int endStart = Math.max(0, cleanOutput.length() - BashConstants.KEEP_END);
            String end = cleanOutput.substring(endStart);

            sb.append(start);
            sb.append("\n\n... [系统警告：输出超长，中间部分已省略以节省 Token] ...\n\n");
            sb.append(end);
            sb.append("\n─────────────────────────────────────────\n");
            sb.append("⚠️ 输出已截断（原始长度: ").append(cleanOutput.length()).append(" 字符）");
        }

        return sb.toString();
    }
}
