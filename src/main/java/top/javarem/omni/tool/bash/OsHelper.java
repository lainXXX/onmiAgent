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
 *   <li>Windows + Git Bash（优先）</li>
 *   <li>Windows + PowerShell（降级方案）</li>
 *   <li>Windows + cmd.exe（最终降级）</li>
 *   <li>Linux（sh / bash）</li>
 *   <li>macOS（sh / bash）</li>
 * </ul>
 */
@Getter
@Slf4j
public enum OsHelper {

    /** Windows + Git Bash（优先使用 bash） */
    WINDOWS_GIT_BASH("windows", true, false),
    /** Windows + PowerShell（降级方案） */
    WINDOWS_POWERSHELL("windows", false, false),
    /** Windows + cmd.exe（最终降级） */
    WINDOWS_CMD("windows", false, false),
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
        // Windows 下打印 Git Bash 路径
        if (CURRENT == WINDOWS_GIT_BASH) {
            log.info("[OsHelper] Git Bash 路径: {}", CURRENT.getShellPath());
        }
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
        return CURRENT == WINDOWS_CMD || CURRENT == WINDOWS_GIT_BASH || CURRENT == WINDOWS_POWERSHELL;
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
            case WINDOWS_POWERSHELL -> {
                yield detectPowerShellPath();
            }
            case LINUX -> "sh";
            case MACOS -> "sh";
            case WINDOWS_CMD -> "cmd";
        };
    }

    /**
     * 获取 shell 启动参数
     *
     * @return {shellPath, arg} - Windows cmd 使用 "/c"，PowerShell 使用 "-Command"，其他使用 "-c"
     */
    public String[] getShellCommandPrefix() {
        return switch (this) {
            case WINDOWS_CMD -> new String[]{"cmd", "/c"};
            case WINDOWS_POWERSHELL -> new String[]{getShellPath(), "-NoProfile", "-NonInteractive", "-Command"};
            case WINDOWS_GIT_BASH -> {
                String shell = getShellPath();
                yield new String[]{shell, "-c"};
            }
            default -> new String[]{getShellPath(), "-c"};
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
            case WINDOWS_CMD, WINDOWS_GIT_BASH, WINDOWS_POWERSHELL -> {
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
            if (isWindows()) {
                char drive = Character.toUpperCase(p.charAt(1));
                return drive + ":\\" + p.substring(3).replace("/", "\\");
            }
            // Linux/macOS 上出现 /c/ 视为普通相对路径
            return p;
        }

        // Windows 上统一正斜杠为反斜杠
        if (isWindows()) {
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
     * 是否使用 cmd /c 参数风格（Windows cmd）
     * PowerShell 使用 -Command 风格
     */
    public boolean usesCmdStyle() {
        return this == WINDOWS_CMD;
    }

    /**
     * 是否使用 PowerShell
     */
    public boolean usesPowerShell() {
        return this == WINDOWS_POWERSHELL;
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

        // Windows：按优先级尝试 shell
        // 1. Git Bash（最高优先级，提供完整 bash 体验）
        if (hasGitBash()) {
            return WINDOWS_GIT_BASH;
        }

        // 2. PowerShell（降级方案，支持更现代的脚本）
        if (hasPowerShell()) {
            return WINDOWS_POWERSHELL;
        }

        // 3. cmd.exe（最终降级）
        return WINDOWS_CMD;
    }

    private static boolean hasGitBash() {
        return detectGitBashPathWithCache() != null;
    }

    /**
     * 检测 Git Bash 路径并缓存到环境变量
     * 优先级：
     * 1. CLAUDE_CODE_GIT_BASH_PATH 环境变量（已设置且有效）
     * 2. PATH 中的 bash
     * 3. Windows 系统环境变量中的 git 安装路径
     * 4. 常见 Git Bash 安装路径
     */
    private static String detectGitBashPathWithCache() {
        // 1. 优先检查环境变量
        String envPath = System.getenv("CLAUDE_CODE_GIT_BASH_PATH");
        if (envPath != null && !envPath.isBlank() && new File(envPath).exists()) {
            return envPath;
        }

        // 2. 尝试在 PATH 中找 bash
        String bashFromPath = findExecutableInPath("bash");
        if (bashFromPath != null) {
            setGitBashPathEnv(bashFromPath);
            return bashFromPath;
        }

        // 3. 从 Windows 系统环境变量中查找 git 安装路径
        String gitFromSystemEnv = findGitFromWindowsSystemEnv();
        if (gitFromSystemEnv != null) {
            setGitBashPathEnv(gitFromSystemEnv);
            return gitFromSystemEnv;
        }

        // 4. 检查常见 Git Bash 安装路径
        String[][] candidates = {
                {"C:\\Program Files\\Git\\bin\\bash.exe"},
                {System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe"},
        };
        for (String[] cand : candidates) {
            if (new File(cand[0]).exists()) {
                setGitBashPathEnv(cand[0]);
                return cand[0];
            }
        }
        return null;
    }

    /**
     * 从 Windows 系统 PATH 中动态查找 git 安装目录（用于推导 bash.exe）
     *
     * <p>完全动态的相对路径推导方案，不依赖任何硬编码路径。</p>
     *
     * <p>工作原理：无论 Git 装在哪个盘、哪个目录，只要 PATH 中有 git.exe，
     * 我们就向上跳一级（从 cmd 目录到 Git 根目录），然后在根目录下找 bin\bash.exe。</p>
     */
    private static String findGitFromWindowsSystemEnv() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String[] paths = pathEnv.split(File.pathSeparator);

        for (String dir : paths) {
            File currentDir = new File(dir);

            // 检查 cmd 目录下是否有 git.exe
            File gitExe = new File(currentDir, "git.exe");
            if (gitExe.exists()) {
                // 获取 Git 根目录（向上跳一级）
                File gitRootDir = currentDir.getParentFile();

                if (gitRootDir != null) {
                    // 推导目标1: Git根目录下的 bin\bash.exe (标准情况)
                    File targetBash1 = new File(gitRootDir, "bin" + File.separator + "bash.exe");
                    if (targetBash1.exists()) {
                        try {
                            return targetBash1.getCanonicalPath();
                        } catch (Exception e) {
                            return targetBash1.getAbsolutePath();
                        }
                    }

                    // 推导目标2: Git根目录下的 usr\bin\bash.exe (MSYS2 底层 bash)
                    File targetBash2 = new File(gitRootDir, "usr" + File.separator + "bin" + File.separator + "bash.exe");
                    if (targetBash2.exists()) {
                        try {
                            return targetBash2.getCanonicalPath();
                        } catch (Exception e) {
                            return targetBash2.getAbsolutePath();
                        }
                    }
                }
            }

            // 极端情况：如果用户直接把 bin 目录加到了 PATH 里
            File directBash = new File(currentDir, "bash.exe");
            if (directBash.exists() && currentDir.getAbsolutePath().toLowerCase().contains("git")) {
                try {
                    return directBash.getCanonicalPath();
                } catch (Exception e) {
                    return directBash.getAbsolutePath();
                }
            }
        }

        return null;
    }

    /**
     * 设置 CLAUDE_CODE_GIT_BASH_PATH 环境变量（仅当前进程）
     */
    private static void setGitBashPathEnv(String path) {
        if (path == null || path.isBlank()) return;
        try {
            System.setProperty("CLAUDE_CODE_GIT_BASH_PATH", path);
            log.info("[OsHelper] 已设置 CLAUDE_CODE_GIT_BASH_PATH: {}", path);
        } catch (Exception e) {
            log.warn("[OsHelper] 设置 CLAUDE_CODE_GIT_BASH_PATH 失败: {}", e.getMessage());
        }
    }

    private static String detectGitBashPath() {
        String path = detectGitBashPathWithCache();
        if (path != null) {
            log.info("[OsHelper] Git Bash 路径: {}", path);
            return path;
        }
        // 最终降级
        log.warn("[OsHelper] 未检测到 Git Bash，使用 'bash' 纯名称");
        return "bash";
    }

    /**
     * 检测系统是否安装了 PowerShell
     * 优先检测 PowerShell Core (pwsh)，其次 Windows PowerShell (powershell)
     */
    private static boolean hasPowerShell() {
        // 优先检测 pwsh (PowerShell Core 7+)
        if (hasPowerShellCore()) {
            return true;
        }
        // 降级检测 Windows PowerShell (5.1)
        return hasWindowsPowerShell();
    }

    private static boolean hasPowerShellCore() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pwsh", "-Version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {
        }

        // 检查常见安装路径
        String[][] candidates = {
                {"C:\\Program Files\\PowerShell\\7\\pwsh.exe"},
                {System.getProperty("user.home") + "\\AppData\\Local\\Microsoft\\PowerShell\\pwsh.exe"},
        };
        for (String[] cand : candidates) {
            if (new File(cand[0]).exists()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWindowsPowerShell() {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {
        }

        // 检查 Windows PowerShell 路径
        String path = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
        return new File(path).exists();
    }

    /**
     * 检测 PowerShell 路径
     * 优先返回 PowerShell Core (pwsh)，降级使用 Windows PowerShell
     */
    private static String detectPowerShellPath() {
        // 优先检测 PowerShell Core
        String[][] coreCandidates = {
                {"C:\\Program Files\\PowerShell\\7\\pwsh.exe", "PowerShell Core (全局)"},
                {System.getProperty("user.home") + "\\AppData\\Local\\Microsoft\\PowerShell\\pwsh.exe", "PowerShell Core (用户)"},
        };
        for (String[] cand : coreCandidates) {
            if (new File(cand[0]).exists()) {
                log.info("[OsHelper] 检测到 {}: {}", cand[1], cand[0]);
                return cand[0];
            }
        }

        // 尝试从 PATH 查找 pwsh
        String pwshFromPath = findExecutableInPath("pwsh");
        if (pwshFromPath != null) {
            log.info("[OsHelper] 检测到 PowerShell Core (PATH): {}", pwshFromPath);
            return pwshFromPath;
        }

        // 降级到 Windows PowerShell
        String[][] desktopCandidates = {
                {"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "Windows PowerShell"},
        };
        for (String[] cand : desktopCandidates) {
            if (new File(cand[0]).exists()) {
                log.info("[OsHelper] 检测到 {}: {}", cand[1], cand[0]);
                return cand[0];
            }
        }

        // 尝试从 PATH 查找 powershell
        String psFromPath = findExecutableInPath("powershell");
        if (psFromPath != null) {
            log.info("[OsHelper] 检测到 Windows PowerShell (PATH): {}", psFromPath);
            return psFromPath;
        }

        // 最终降级
        log.warn("[OsHelper] 未检测到 PowerShell，使用 'powershell' 纯名称（可能失败）");
        return "powershell";
    }

    /**
     * 在 PATH 环境变量中查找可执行文件
     */
    private static String findExecutableInPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String[] paths = pathEnv.split(File.pathSeparator);
        for (String dir : paths) {
            File exe = new File(dir, name + ".exe");
            if (exe.exists()) {
                return exe.getAbsolutePath();
            }
            // 无扩展名也尝试
            exe = new File(dir, name);
            if (exe.exists()) {
                return exe.getAbsolutePath();
            }
        }
        return null;
    }
}
