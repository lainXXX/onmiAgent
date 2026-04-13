package top.javarem.omni.tool.bash;

/**
 * Bash 工具常量定义
 */
public final class BashConstants {

    private BashConstants() {}

    // ==================== 超时配置 ====================
    public static final long DEFAULT_TIMEOUT_MS = 120_000;
    public static final long MAX_TIMEOUT_MS = 600_000;
    public static final int KILL_WAIT_SECONDS = 3;

    // ==================== 输出限制 ====================
    public static final int MAX_OUTPUT_CHARS = 6000;
    public static final int KEEP_START = 1000;
    public static final int KEEP_END = 5000;
}
