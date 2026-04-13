package top.javarem.omni.tool.bash;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 操作系统检测与跨平台行为统一入口
 *
 * <h3>核心原则：</h3>
 * <ul>
 *   <li>OS 检测只做一次，结果缓存</li>
 *   <li>OS 相关行为通过枚举+方法提供，不在业务代码散落 if-else</li>
 *   <li>Windows Git Bash 视为 Linux 兼容环境（路径、shell 行为）</li>
 * </ul>
 *
 * <h3>当前支持的操作系统：</h3>
 * <ul>
 *   <li>Windows（cmd.exe）</li>
 *   <li>Windows + Git Bash（bash.exe）</li>
 *   <li>Linux（sh / bash）</li>
 *   <li>macOS（sh / bash）</li>
 * </ul>
 */
@Getter
@Slf4j
public enum OsHelper {

    /** Windows（原生 cmd） */
    WINDOWS_CMD("windows", false, false),
    /** Windows + Git Bash（优先使用 bash） */
    WINDOWS_GIT_BASH("windows", true, false),
    /** Linux（sh/bash） */
    LINUX("linux", true, false),
    /** macOS（sh/bash） */
    MACOS("darwin", true, false);

    /** 当前运行环境 */
    private static final OsHelper CURRENT;

    static {
        CURRENT = detect();
        log.info("[OsHelper] 操作系统检测结果: {} (family={}, hasBash={}, hasPosix={})",
                CURRENT.name(), CURRENT.osFamily, CURRENT.hasBash, CURRENT.hasPosix);
    }

    /** OS 家族：windows / linux / darwin */
    private final String osFamily;
    /** 是否提供 bash/shell */
    private final boolean hasBash;
    /** 是否为 POSIX 系统（Linux/macOS） */
    private final boolean hasPosix;

    OsHelper(String osFamily, boolean hasBash, boolean hasPosix) {
        this.osFamily = osFamily;
        this.hasBash = hasBash;
        this.hasPosix = hasPosix;
    }

    // ==================== 静态入口 ====================

    public static OsHelper current() {
        return CURRENT;
    }

    public static boolean isWindows() {
        return CURRENT == WINDOWS_CMD || CURRENT == WINDOWS_GIT_BASH;
    }

    public static boolean isLinux() {
        return CURRENT == LINUX;
    }

    public static boolean isMac() {
        return CURRENT == MACOS;
    }

    public static boolean isPosix() {
        return CURRENT.hasPosix;
    }

    public static boolean hasBash() {
        return CURRENT.hasBash;
    }

    // ==================== Shell 选择 ====================

    /**
     * 获取推荐的 shell 路径
     *
     * @return shell 路径（Windows 返回完整 exe 路径，Linux/macOS 返回 "sh"）
     */
    public String getShellPath() {
        return switch (this) {
            case WINDOWS_GIT_BASH -> {
                // 已检测到 Git Bash
                yield detectGitBashPath();
            }
            case LINUX -> "sh";
            case MACOS -> "sh";
            case WINDOWS_CMD -> "cmd";
        };
    }

    /**
     * 获取 shell 启动参数
     *
     * @return {shellPath, "-c"} 或 {"cmd", "/c"}
     */
    public String[] getShellCommandPrefix() {
        return switch (this) {
            case WINDOWS_CMD -> new String[]{"cmd", "/c"};
            default -> {
                String shell = getShellPath();
                yield new String[]{shell, "-c"};
            }
        };
    }

    // ==================== 进程终止 ====================

    /**
     * 构建杀进程命令
     *
     * @param pid 进程 ID
     * @return {command, arg1, arg2, ...}
     */
    public String[] buildKillCommand(long pid) {
        return switch (this) {
            case WINDOWS_CMD, WINDOWS_GIT_BASH -> {
                // Windows: taskkill /F /T /PID <pid> 强制杀进程树
                yield new String[]{"cmd", "/c", "taskkill", "/F", "/T", "/PID", String.valueOf(pid)};
            }
            case LINUX, MACOS -> {
                // POSIX: pkill -9 杀整个进程组
                yield new String[]{"/bin/sh", "-c",
                        "pkill -9 -P " + pid + " 2>/dev/null; kill -9 " + pid + " 2>/dev/null; true"};
            }
        };
    }

    // ==================== 路径归一化 ====================

    /**
     * 将任意风格路径归一化为当前 OS 的原生路径格式
     *
     * <ul>
     *   <li>Git Bash /c/Users/... → C:\Users\...</li>
     *   <li>Unix /home/user/... → /home/user/...</li>
     *   <li>C:/Users → C:\Users（正斜杠转反斜杠，仅 Windows）</li>
     * </ul>
     */
    public String normalizePath(String path) {
        if (path == null || path.isBlank()) return path;

        String p = path.trim();

        // Git Bash 风格路径检测：/c/... /d/... （仅 Windows）
        if (p.matches("^/[a-z]/.*") || p.matches("^/[A-Z]/.*")) {
            if (this == WINDOWS_CMD || this == WINDOWS_GIT_BASH) {
                char drive = Character.toUpperCase(p.charAt(1));
                return drive + ":\\" + p.substring(3).replace("/", "\\");
            }
            // Linux/macOS 上出现 /c/ 视为普通相对路径
            return p;
        }

        // Windows 上统一正斜杠为反斜杠
        if (this == WINDOWS_CMD || this == WINDOWS_GIT_BASH) {
            if (p.matches("^[A-Za-z]:.*")) {
                return p.replace("/", "\\");
            }
        }

        return p;
    }

    /**
     * 将路径转换为 Unix 风格（正斜杠，可用于跨 OS 比较）
     */
    public String toUnixPath(String path) {
        if (path == null) return null;
        return path.replace("\\", "/");
    }

    // ==================== 命令行参数格式 ====================

    /**
     * 是否使用 cmd /c 参数风格（Windows cmd vs Unix sh -c）
     */
    public boolean usesCmdStyle() {
        return this == WINDOWS_CMD;
    }

    // ==================== 检测逻辑 ====================

    private static OsHelper detect() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (!os.contains("windows") && !os.contains("darwin")) {
            // Linux 及一切非 Windows/Darwin
            return LINUX;
        }

        if (os.contains("darwin")) {
            return MACOS;
        }

        // Windows：检测是否有 Git Bash
        if (hasGitBash()) {
            return WINDOWS_GIT_BASH;
        }

        return WINDOWS_CMD;
    }

    private static boolean hasGitBash() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "echo test");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {
        }

        // 检测常见 Git Bash 安装路径
        String[][] candidates = {
                {"C:\\Program Files\\Git\\bin\\bash.exe"},
                {System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe"},
        };
        for (String[] cand : candidates) {
            if (new File(cand[0]).exists()) {
                return true;
            }
        }
        return false;
    }

    private static String detectGitBashPath() {
        String[][] candidates = {
                {"C:\\Program Files\\Git\\bin\\bash.exe", "Git Bash (全局)"},
                {System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe", "Git Bash (用户)"},
        };
        for (String[] cand : candidates) {
            if (new File(cand[0]).exists()) {
                log.info("[OsHelper] 检测到 {}: {}", cand[1], cand[0]);
                return cand[0];
            }
        }
        return "bash"; // fallback
    }
}
