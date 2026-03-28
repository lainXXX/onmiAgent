package top.javarem.omni.tool.bash;

/**
 * 危险命令检查结果
 */
public record DangerousCheckResult(boolean isDangerous, String reason) {}
