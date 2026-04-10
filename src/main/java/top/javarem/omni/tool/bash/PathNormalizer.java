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
            // 转换 Git Bash 路径格式 /c/... -> C:/...
            String converted = convertGitBashPath(pathStr);
            Path normalized = Paths.get(converted).normalize();
            return normalized.toString().replace("\\", "/");
        } catch (Exception e) {
            return pathStr.replace("\\", "/");
        }
    }

    /**
     * 转换 Git Bash 路径格式为 Windows 路径格式
     * /c/Users/... -> C:/Users/...
     * /d/Program Files/... -> D:/Program Files/...
     */
    private String convertGitBashPath(String path) {
        if (path == null || path.isBlank()) return path;
        // 匹配 /c/ 或 /d/ 等驱动器路径
        if (path.matches("^/[a-z]/.*")) {
            char drive = Character.toUpperCase(path.charAt(1));
            return drive + ":" + path.substring(2);
        }
        return path;
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
            if (word.contains("/") || word.contains("\\")) {
                validatePath(word, workspace);
            }
        }
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
                throw new SecurityException("禁止访问 WORKSPACE 之外的路径: " + pathCandidate);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            // Path parsing failed — allow it (will be caught by shell)
        }
    }
}