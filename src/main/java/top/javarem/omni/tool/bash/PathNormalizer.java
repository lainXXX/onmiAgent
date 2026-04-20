package top.javarem.omni.tool.bash;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class PathNormalizer {

    private final String workspace;

    public PathNormalizer(@Value("${agent.working-directory:${user.dir}}") String workspace) {
        this.workspace = normalize(workspace);
    }

    public String normalize(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) return "";
        try {
            // 统一使用 OsHelper 进行 OS 适配路径归一化
            String converted = OsHelper.current().normalizePath(pathStr);
            Path normalized = Paths.get(converted).normalize();
            return normalized.toString().replace("\\", "/");
        } catch (Exception e) {
            return pathStr.replace("\\", "/");
        }
    }

    public void validate(String command) {
        validate(command, this.workspace);
    }

    public void validate(String command, String workspace) {
        if (workspace == null || workspace.isBlank()) {
            workspace = this.workspace;
        }
        String[] words = command.split("[\\s]+");
        for (String word : words) {
            if (isUrl(word)) {
                // URL 不做路径检查（http://, https://, // 开头的 UNC 路径除外）
                continue;
            }
            if (word.contains("/") || word.contains("\\")) {
                validatePath(word, workspace);
            }
        }
    }

    /**
     * 判断词是否为 URL（应跳过路径检查）
     */
    private boolean isUrl(String word) {
        if (word == null || word.isBlank()) return false;
        String lower = word.toLowerCase();
        // http://, https://, ftp://, sftp:// 等协议 URL
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("ftp://") || lower.startsWith("sftp://") ||
            lower.startsWith("git://") || lower.startsWith("ssh://")) {
            return true;
        }
        // 以 // 开头但是 UNC 路径（Windows 网络路径）的情况需要在上下文中判断
        // 这里简单处理：以 // 开头且后面是字母数字的，视为 UNC 而非 URL
        // 但 http://localhost:8080/approval 这种虽然是 // 但不是 UNC
        // 更精确的判断：检查 // 后面是否是合法的主机名格式
        if (word.startsWith("//")) {
            String afterSlashSlash = word.substring(2);
            // UNC 路径特征：后面是 server\share 或 server/share
            // URL 特征：后面是 hostname:port/path
            // 简单判断：如果包含冒号（在端口前）或斜杠（在路径前），则是 URL
            if (afterSlashSlash.contains(":") || afterSlashSlash.contains("/")) {
                return true; // 视为 URL
            }
        }
        return false;
    }

    private void validatePath(String pathCandidate, String workspace) {
        try {
            String normalized = normalize(pathCandidate);

            Path resolved;
            if (normalized.startsWith("./") || normalized.startsWith("../") || !normalized.contains("/")) {
                resolved = Paths.get(workspace, normalized).toAbsolutePath().normalize();
            } else {
                resolved = Paths.get(normalized).toAbsolutePath().normalize();
            }
            Path workspacePath = Paths.get(workspace).toAbsolutePath().normalize();

            String resolvedStr = resolved.toString().replace("\\", "/");
            String workspaceStr = workspacePath.toString().replace("\\", "/");

            if (!resolvedStr.startsWith(workspaceStr + "/") && !resolvedStr.equals(workspaceStr)) {
                throw new WorkspaceAccessException(pathCandidate);
            }
        } catch (WorkspaceAccessException e) {
            throw e;
        } catch (Exception e) {
            // Path parsing failed — allow it (will be caught by shell)
        }
    }
}