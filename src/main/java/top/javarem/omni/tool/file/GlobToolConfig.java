package top.javarem.omni.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.tool.AgentTool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Glob 目录与文件结构探索工具
 * 按模式查找文件路径，帮助 Agent 建立项目宏观认知
 */
@Component
@Slf4j
public class GlobToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "glob";
    }

    private static final String DEFAULT_PATH = ".";
    private static final int MAX_RESULTS = 100;

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist",
            ".idea", ".vscode", "bin", "out", ".gradle",
            ".svn", "CVS", ".hg"
    );

    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".class", ".png", ".jpg",
            ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".woff",
            ".woff2", ".ttf", ".eot", ".map", ".ds_store"
    );

    /**
     * 按模式查找文件路径
     *
     * @param pattern Glob 匹配模式
     * @param path    搜索起点目录，默认为当前项目根目录
     * @param context ToolContext，用于获取动态 workspace
     * @return 匹配的文件路径列表
     */
    @Tool(name = "glob", description = """
            适用于任何代码库大小的快速文件模式匹配工具
            -支持glob模式，如“**/*.js”或“src/**/*.ts”
            -返回按修改时间排序的匹配文件路径
            -需要按名称模式查找文件时使用此工具
            -当您进行可能需要多轮globbing和greping的开放式搜索时，请使用代理工具
            -您可以在一次响应中调用多个工具。如果可能有用，最好同时进行多个搜索
            """)
    public String glob(
            @ToolParam(description = "Glob模式。示例：**/*.java, src/**/*Controller.java") String pattern,
            @ToolParam(description = "搜索起点。默认当前目录", required = false) String path,
            ToolContext context) {
        String workspace = extractWorkspace(context);
        log.info("[glob] 开始执行: pattern={}, path={}, workspace={}", pattern, path, workspace);

        // 1. 参数归一化
        String normalizedPattern = pattern == null ? "" : pattern.trim();
        if (normalizedPattern.isEmpty()) {
            return buildErrorResponse("匹配模式不能为空", "请提供有效的 glob 模式，如 **/*.java");
        }

        String searchPath = (path == null || path.trim().isEmpty()) ? DEFAULT_PATH : path.trim();

        // 2. 解析路径
        PathCheckResult pathCheck = resolveAndCheckPath(searchPath, workspace);
        if (!pathCheck.approved()) {
            return pathCheck.errorMessage();
        }
        Path basePath = pathCheck.resolvedPath();

        // 3. 执行搜索（不使用 FOLLOW_LINKS，安全遍历）
        try {
            List<String> matchedFiles = performGlob(normalizedPattern, basePath);

            if (matchedFiles.isEmpty()) {
                log.info("[glob] 完成: pattern={}, path={}, 结果=0", normalizedPattern, searchPath);
                return buildEmptyResponse(normalizedPattern, searchPath);
            }

            boolean truncated = false;
            if (matchedFiles.size() > MAX_RESULTS) {
                matchedFiles = new ArrayList<>(matchedFiles.subList(0, MAX_RESULTS));
                truncated = true;
            }

            log.info("[glob] 完成: pattern={}, path={}, 结果={}", normalizedPattern, searchPath, matchedFiles.size());
            return buildSuccessResponse(matchedFiles, normalizedPattern, searchPath, truncated);

        } catch (Exception e) {
            log.error("[glob] 失败: pattern={}, path={}, error={}", normalizedPattern, searchPath, e.getMessage(), e);
            return buildErrorResponse("搜索失败: " + e.getMessage(),
                    "请检查 glob 模式是否正确");
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private PathCheckResult resolveAndCheckPath(String relativePath, String workspace) {
        try {
            String normalized = normalizeGitBashPath(relativePath);
            Path base = workspace != null ? Paths.get(workspace) : Paths.get(System.getProperty("user.dir"));
            Path resolved = base.resolve(normalized).normalize();
            return new PathCheckResult(true, resolved, null);
        } catch (Exception e) {
            return new PathCheckResult(false, null,
                    buildErrorResponse("路径解析失败: " + e.getMessage(),
                            "请检查路径格式是否正确"));
        }
    }

    private String normalizeGitBashPath(String path) {
        if (path == null || path.isEmpty()) return path;
        // Git Bash 风格路径检测：/c/... 或 /d/...
        if (path.matches("^/[a-z]/.*") || path.matches("^/[A-Z]/.*")) {
            char drive = path.charAt(1);
            String rest = path.substring(3).replace("/", "\\");
            return drive + ":\\" + rest;
        }
        return path;
    }

    // ============================================================
    // Glob 执行（安全遍历，不 follow symlinks）
    // ============================================================

    private List<String> performGlob(String pattern, Path basePath) throws IOException {
        // 将 glob 模式转换为正则
        String regexPattern = globToRegex(pattern);
        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(regexPattern,
                java.util.regex.Pattern.CASE_INSENSITIVE);

        List<String> results = new ArrayList<>();

        // 注意：不使用 FOLLOW_LINKS，防止遍历到 workspace 外的符号链接
        EnumSet<FileVisitOption> visitOptions = EnumSet.noneOf(FileVisitOption.class);

        Files.walkFileTree(basePath, visitOptions, Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (EXCLUDED_DIRS.contains(dirName)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // 安全检查：防止通过 symlink 遍历到 workspace 外
                        if (attrs.isSymbolicLink() && !isInsideWorkspace(dir, basePath)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // 跳过 symlink 文件
                        if (attrs.isSymbolicLink()) {
                            return FileVisitResult.CONTINUE;
                        }

                        String fileName = file.getFileName() != null ? file.getFileName().toString() : "";
                        String extension = getExtension(fileName);

                        if (EXCLUDED_EXTENSIONS.contains(extension)) {
                            return FileVisitResult.CONTINUE;
                        }

                        String relativePath = relativizePath(file, basePath);
                        if (regex.matcher(relativePath).matches() || regex.matcher(fileName).matches()) {
                            results.add(relativePath);
                        }

                        if (results.size() >= MAX_RESULTS + 10) {
                            return FileVisitResult.TERMINATE;
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });

        return results;
    }

    private boolean isInsideWorkspace(Path dir, Path basePath) {
        try {
            Path normalizedDir = dir.normalize();
            Path normalizedBase = basePath.normalize();
            return normalizedDir.startsWith(normalizedBase);
        } catch (Exception e) {
            return false;
        }
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int len = glob.length();

        for (int i = 0; i < len; i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < len && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                        if (i + 1 < len && (glob.charAt(i + 1) == '/' || glob.charAt(i + 1) == '\\')) {
                            regex.append("/?");
                            i++;
                        }
                    } else {
                        regex.append("[^/\\\\]*");
                    }
                }
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '\\' -> regex.append("\\\\");
                case '{' -> regex.append("(?:");
                case '}' -> regex.append(")");
                case ',' -> regex.append("|");
                case '(', ')', '+', '|', '^', '$', '@', '%' -> regex.append("\\").append(c);
                default -> regex.append(c);
            }
        }

        regex.append("$");
        return regex.toString();
    }

    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot).toLowerCase() : "";
    }

    private String relativizePath(Path absolutePath, Path basePath) {
        try {
            return basePath.relativize(absolutePath).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return absolutePath.toString().replace("\\", "/");
        }
    }

    // ============================================================
    // 响应构建
    // ============================================================

    private String buildSuccessResponse(List<String> files, String pattern, String path, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("📂 Glob 匹配结果: \"").append(pattern).append("\" 在 \"").append(path).append("\"\n");
        sb.append("共找到 ").append(files.size()).append(" 个文件:\n\n");
        for (String file : files) {
            sb.append("- ").append(file).append("\n");
        }
        if (truncated) {
            sb.append("\n... (结果过多已截断，最多显示 ").append(MAX_RESULTS).append(" 个)");
        }
        return sb.toString();
    }

    private String buildEmptyResponse(String pattern, String path) {
        return "📂 Glob 匹配结果: \"" + pattern + "\" 在 \"" + path + "\"\n\n" +
                "❌ 未找到匹配文件\n\n" +
                "建议:\n" +
                "  - 检查 glob 模式是否正确\n" +
                "  - 确认搜索路径是否正确\n" +
                "  - 常见模式示例：**/*.java, src/**/*.kt, **/pom.xml";
    }

    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" + "💡 建议: " + suggestion;
    }

    private String extractWorkspace(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object workspace = context.getContext().get(AdvisorContextConstants.WORKSPACE);
        return workspace != null ? workspace.toString() : null;
    }

    private record PathCheckResult(boolean approved, Path resolvedPath, String errorMessage) {
    }
}
