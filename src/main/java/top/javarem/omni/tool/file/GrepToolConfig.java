package top.javarem.omni.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.AgentTool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Grep 全局代码搜索工具
 * 按照 AI Agent 标准工具集设计规范实现
 */
@Component
@Slf4j
public class GrepToolConfig implements AgentTool {

    /**
     * 默认搜索路径
     */
    private static final String DEFAULT_PATH = ".";

    /**
     * 最大返回结果数
     */
    private static final int MAX_RESULTS = 50;

    /**
     * 需要过滤的目录
     */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist",
            ".idea", ".vscode", "bin", "out", ".gradle"
    );

    /**
     * 需要过滤的文件扩展名
     */
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".class", ".png", ".jpg",
            ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".woff",
            ".woff2", ".ttf", ".eot", ".map", ".min.js", ".min.css"
    );

    /**
     * 工作目录
     */
    private static final String WORKSPACE = System.getProperty("user.dir");

    public GrepToolConfig() {
    }

    /**
     * 在代码库中精确查找文本或正则表达式
     *
     * @param query 搜索的关键词或正则表达式
     * @param path  限定的搜索相对目录，默认当前目录
     * @return 搜索结果列表
     */
    @Tool(name = "grep", description = "代码内容搜索。适用：定位关键词/报错。禁止：找路径(用glob)。返回：路径:行号:内容")
    public String grep(
            @ToolParam(description = "关键词或正则。示例：'void main' 或 'public class.*'") String query,
            @ToolParam(description = "搜索路径。默认当前目录", required = false) String path) {
        log.info("[grep] 开始执行: query={}, path={}", query, path);
        // 1. 参数校验与归一化
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            log.error("[grep] 失败: 搜索关键词为空");
            return buildErrorResponse("搜索关键词不能为空", "请提供有效的搜索内容");
        }

        String searchPath = (path == null || path.trim().isEmpty()) ? DEFAULT_PATH : path.trim();

        // 2. 解析搜索路径并检查权限
        PathCheckResult pathCheck = resolveAndCheckPath(searchPath);
        if (!pathCheck.approved) {
            return pathCheck.errorMessage;
        }
        Path basePath = pathCheck.resolvedPath;

        // 3. 执行搜索
        try {
            List<SearchResult> results = performSearch(normalizedQuery, basePath);

            // 4. 结果处理
            if (results.isEmpty()) {
                log.info("[grep] 完成: query={}, path={}, 结果=0", normalizedQuery, searchPath);
                return buildEmptyResponse(normalizedQuery, searchPath);
            }

            // 5. Token 保护：截断结果
            boolean truncated = false;
            if (results.size() > MAX_RESULTS) {
                results = results.subList(0, MAX_RESULTS);
                truncated = true;
            }

            log.info("[grep] 完成: query={}, path={}, 结果={}", normalizedQuery, searchPath, results.size());
            return buildSuccessResponse(results, normalizedQuery, searchPath, truncated);

        } catch (RuntimeException e) {
            log.error("[grep] 失败: path={}, error={}", searchPath, e.getMessage());
            throw new AgentToolSecurityException(
                    "⚠️ 路径访问被拒绝: " + searchPath,
                    e,
                    "这个路径超出了工作目录范围，且您的访问请求未获得批准。",
                    "请确保所有文件操作都在项目工作目录内进行，这是为了系统安全。如果确实需要访问其他位置的文件，请明确告知用户具体需求。"
            );
        } catch (Exception e) {
            log.error("[grep] 失败: query={}, path={}, error={}", normalizedQuery, searchPath, e.getMessage(), e);
            return buildErrorResponse("搜索失败: " + e.getMessage(),
                    "请尝试使用更简单的搜索词，或检查搜索路径");
        }
    }

    /**
     * 解析路径
     */
    private PathCheckResult resolveAndCheckPath(String relativePath) {
        try {
            Path base = Paths.get(WORKSPACE);
            Path resolved = base.resolve(relativePath).normalize();

            return new PathCheckResult(true, resolved, null);

        } catch (Exception e) {
            return new PathCheckResult(false, null,
                    buildErrorResponse("路径解析失败: " + e.getMessage(),
                            "请检查路径格式是否正确"));
        }
    }

    /**
     * 执行搜索逻辑
     */
    private List<SearchResult> performSearch(String query, Path basePath) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        boolean isRegex = isProbablyRegex(query);

        Pattern pattern = null;
        if (isRegex) {
            try {
                pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                pattern = null;
            }
        }

        Pattern finalPattern = pattern;
        Files.walkFileTree(basePath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (EXCLUDED_DIRS.contains(dirName)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String fileName = file.getFileName() != null ? file.getFileName().toString() : "";
                        String extension = getExtension(fileName);
                        if (EXCLUDED_EXTENSIONS.contains(extension)) {
                            return FileVisitResult.CONTINUE;
                        }

                        try {
                            List<String> lines = Files.readAllLines(file);
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i);
                                boolean matches;

                                if (finalPattern != null) {
                                    matches = finalPattern.matcher(line).find();
                                } else {
                                    matches = line.toLowerCase().contains(query.toLowerCase());
                                }

                                if (matches) {
                                    results.add(new SearchResult(
                                            relativizePath(file),
                                            i + 1,
                                            line.trim()
                                    ));

                                    if (results.size() >= MAX_RESULTS + 10) {
                                        return FileVisitResult.TERMINATE;
                                    }
                                }
                            }
                        } catch (IOException e) {
                            log.warn("读取文件失败: {}", file);
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });

        return results;
    }

    /**
     * 判断是否可能是正则表达式
     */
    private boolean isProbablyRegex(String query) {
        String regexChars = ".*+?[]{}()|^$\\";
        return query.chars().anyMatch(c -> regexChars.indexOf(c) >= 0);
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot).toLowerCase() : "";
    }

    /**
     * 将绝对路径转换为相对路径
     */
    private String relativizePath(Path absolutePath) {
        try {
            Path workspacePath = Paths.get(WORKSPACE);
            return workspacePath.relativize(absolutePath).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return absolutePath.toString().replace("\\", "/");
        }
    }

    /**
     * 构建成功响应
     */
    private String buildSuccessResponse(List<SearchResult> results, String query, String path, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索结果: \"").append(query).append("\" 在 \"").append(path).append("\"\n");
        sb.append("共找到 ").append(results.size()).append(" 处匹配:\n\n");

        Map<String, List<SearchResult>> byFile = results.stream()
                .collect(Collectors.groupingBy(SearchResult::file));

        for (Map.Entry<String, List<SearchResult>> entry : byFile.entrySet()) {
            sb.append("📄 ").append(entry.getKey()).append("\n");
            for (SearchResult r : entry.getValue()) {
                sb.append("   ").append(r.line()).append(": ").append(r.content()).append("\n");
            }
            sb.append("\n");
        }

        if (truncated) {
            sb.append("\n... (结果过多已截断，请缩小搜索范围)");
        }

        return sb.toString();
    }

    /**
     * 构建空结果响应
     */
    private String buildEmptyResponse(String query, String path) {
        return "🔍 搜索结果: \"" + query + "\" 在 \"" + path + "\"\n\n" +
                "❌ 未找到匹配结果\n\n" +
                "建议:\n" +
                "  - 检查拼写是否正确\n" +
                "  - 尝试使用更通用的关键词\n" +
                "  - 确认搜索路径是否正确";
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }

    /**
     * 搜索结果记录
     */
    private record SearchResult(String file, int line, String content) {
    }

    /**
     * 路径检查结果
     */
    private record PathCheckResult(boolean approved, Path resolvedPath, String errorMessage) {
    }

    /**
     * 安全异常 - 包含引导性消息
     */
    public static class AgentToolSecurityException extends RuntimeException {
        private final String userGuidance;

        public AgentToolSecurityException(String message, Throwable cause, String reason, String guidance) {
            super(message + " | 原因: " + reason + " | 指导: " + guidance, cause);
            this.userGuidance = guidance;
        }

        public String getUserGuidance() {
            return userGuidance;
        }
    }
}
