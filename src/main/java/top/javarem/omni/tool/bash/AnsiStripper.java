package top.javarem.omni.tool.bash;

/**
 * ANSI 转义序列去除器
 */
public final class AnsiStripper {

    private AnsiStripper() {}

    /**
     * 去除 ANSI 转义序列
     */
    public static String strip(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.replaceAll("\\u001B\\[[;\\d]*[A-Za-z]", "")
                   .replaceAll("\\u001B\\]0;[^\u001B]*\\u001B\\\\", "")
                   .replaceAll("\\u001B\\]P[0-9a-fA-F][^\u001B]*\\u001B\\\\", "");
    }
}
