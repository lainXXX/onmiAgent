package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bash 工作目录管理器
 *
 * <p>自动跟踪命令执行后的工作目录变化，防止通过 cd 逃逸到项目目录外。</p>
 *
 * <h3>核心功能：</h3>
 * <ul>
 *   <li>解析 bash -c "cd /path && command" 中的 cd 目标</li>
 *   <li>通过 pwd -P 验证实际目录</li>
 *   <li>规范化路径（解析符号链接）</li>
 *   <li>限制工作目录在项目根目录下</li>
 * </ul>
 *
 * <h3>使用方式：</h3>
 * <pre>
 * // 在命令执行后调用
 * String newDir = wdm.trackAndValidate(stdout + stderr, "/project/root");
 * Path current = wdm.getCurrentDir();
 * </pre>
 */
@Component
@Slf4j
public class WorkingDirectoryManager {

    private static final Pattern CD_PATTERN = Pattern.compile(
        "^\\s*cd\\s+([^;\\|&\\>\\<]+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PWD_PATTERN = Pattern.compile(
        "(/[a-zA-Z]:[/\\\\][^\n]*|/home/[^\n]*)"
    );

    private final Path projectRoot;

    /**
     * 当前工作目录（规范化后）
     */
    private volatile Path currentDir;

    public WorkingDirectoryManager(
            @Value("${agent.working-directory:${user.dir}}") String workspace) {
        this.projectRoot = normalize(workspace);
        this.currentDir = this.projectRoot;
        log.info("[WorkingDirectoryManager] 初始化, projectRoot={}, currentDir={}",
            projectRoot, currentDir);
    }

    /**
     * 从命令输出中解析 cd 目标并验证
     *
     * @param stdout stderr 混合输出
     * @return 验证后的工作目录（始终在项目根目录下）
     */
    public Path trackAndValidate(String stdout, String stderr) {
        if (stdout == null && stderr == null) {
            return currentDir;
        }

        String output = (stdout != null ? stdout : "") + (stderr != null ? stderr : "");

        // 1. 尝试从 pwd -P 输出中提取真实路径
        Path pwdPath = extractPwdPath(output);
        if (pwdPath != null && isWithinProject(pwdPath)) {
            this.currentDir = pwdPath;
            log.debug("[WorkingDirectoryManager] pwd 检测到新目录: {}", currentDir);
            return currentDir;
        }

        // 2. 尝试从 cd 命令中提取目标路径
        Path cdPath = extractCdPath(output);
        if (cdPath != null && isWithinProject(cdPath)) {
            this.currentDir = cdPath;
            log.debug("[WorkingDirectoryManager] cd 检测到新目录: {}", currentDir);
            return currentDir;
        }

        return currentDir;
    }

    /**
     * 从 BashExecutor 执行前获取当前目录
     */
    public Path getCurrentDir() {
        return currentDir;
    }

    /**
     * 重置到项目根目录
     */
    public Path resetToProjectRoot() {
        this.currentDir = projectRoot;
        log.info("[WorkingDirectoryManager] 重置到项目根目录: {}", projectRoot);
        return currentDir;
    }

    /**
     * 获取项目根目录
     */
    public Path getProjectRoot() {
        return projectRoot;
    }

    /**
     * 验证路径是否在项目根目录下
     */
    public boolean isWithinProject(Path dir) {
        if (dir == null) return false;
        try {
            Path normalized = normalize(dir.toString());
            Path realPath = normalized.toRealPath();
            Path rootReal = projectRoot.toRealPath();
            String realStr = realPath.toString();
            String rootStr = rootReal.toString();
            return realStr.startsWith(rootStr) || realStr.equals(rootStr);
        } catch (Exception e) {
            // 无法解析时使用保守策略：仅允许项目根目录下
            log.warn("[WorkingDirectoryManager] 路径验证异常: {}", dir, e);
            String dirStr = dir.toString();
            String rootStr = projectRoot.toString();
            return dirStr.startsWith(rootStr) || dirStr.equals(rootStr);
        }
    }

    private Path normalize(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return projectRoot;
        }
        // 处理 Windows Git Bash 风格路径
        String normalized = normalizeGitBashPath(pathStr.trim());
        return Paths.get(normalized).normalize().toAbsolutePath();
    }

    private String normalizeGitBashPath(String path) {
        // Git Bash 风格: /c/Users/... -> C:/Users/...
        if (path.matches("^/[a-z]/.*") || path.matches("^/[A-Z]/.*")) {
            char drive = Character.toUpperCase(path.charAt(1));
            String rest = path.substring(3).replace("/", "\\");
            return drive + ":\\" + rest;
        }
        // 已有 Windows 风格路径
        if (path.matches("^[a-zA-Z]:.*")) {
            return path.replace("/", "\\");
        }
        return path;
    }

    /**
     * 从 pwd -P 输出中提取真实路径
     */
    private Path extractPwdPath(String output) {
        if (output == null || output.isBlank()) return null;

        // 匹配常见的路径格式
        Matcher matcher = PWD_PATTERN.matcher(output);
        if (matcher.find()) {
            String path = matcher.group(1);
            return normalize(path);
        }
        return null;
    }

    /**
     * 从 cd 命令中提取目标路径
     */
    private Path extractCdPath(String output) {
        if (output == null || output.isBlank()) return null;

        // 查找 cd 命令（但不适用于已执行的 cd，因为当前shell不会输出cd）
        // 这里主要用于解析 bash -c "cd /path && cmd" 中的路径
        Matcher matcher = CD_PATTERN.matcher(output);
        if (matcher.find()) {
            String pathStr = matcher.group(1).trim();
            // 移除引号
            pathStr = pathStr.replaceAll("^[\"']|[\"']$", "");
            return normalize(pathStr);
        }
        return null;
    }
}
