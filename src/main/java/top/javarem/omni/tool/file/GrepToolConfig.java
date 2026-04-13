package top.javarem.omni.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.tool.AgentTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grep 全局代码搜索工具
 * 流式搜索，支持正则表达式，避免大文件内存问题
 */
@Component
@Slf4j
public class GrepToolConfig implements AgentTool {

    private static final String DEFAULT_PATH = ".";
    private static final int MAX_RESULTS = 50;
    private static final int MAX_LINE_LENGTH = 2000; // 单行最大长度限制
    private static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5MB 文件大小限制

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist",
            ".idea", ".vscode", "bin", "out", ".gradle",
            ".svn", "CVS", ".hg"
    );

    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".class", ".png", ".jpg",
            ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".woff",
            ".woff2", ".ttf", ".eot", ".map", ".min.js", ".min.css"
    );

    /**
     * 在代码库中精确查找文本或正则表达式
     *
     * @param query   搜索的关键词或正则表达式
     * @param path    限定的搜索相对目录，默认当前目录
     * @param context ToolContext，用于获取动态 workspace
     * @return 搜索结果列表
     */
    @Tool(name = "grep", description = "代码内容搜索。适用：定位关键词/报错。禁止：找路径(用glob)。返回：路径:行号:内容")
    public String grep(
            @ToolParam(description = "关键词或正则。示例：'void main' 或 'public class.*'") String query,
            @ToolParam(description = "搜索路径。默认当前目录", required = false) String path,
            ToolContext context) {
        String workspace = extractWorkspace(context);
        log.info("[grep] 开始执行: query={}, path={}, workspace={}", query, path, workspace);

        // 1. 参数归一化
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            return buildErrorResponse("搜索关键词不能为空", "请提供有效的搜索内容");
        }

        String searchPath = (path == null || path.trim().isEmpty()) ? DEFAULT_PATH : path.trim();

        // 2. 解析路径
        PathCheckResult pathCheck = resolveAndCheckPath(searchPath, workspace);
        if (!pathCheck.approved) {
            return pathCheck.errorMessage;
        }
        Path basePath = pathCheck.resolvedPath;

        // 3. 执行搜索
        try {
            List<SearchResult> results = performSearch(normalizedQuery, basePath);

            if (results.isEmpty()) {
                log.info("[grep] 完成: query={}, path={}, 结果=0", normalizedQuery, searchPath);
                return buildEmptyResponse(normalizedQuery, searchPath);
            }

            boolean truncated = false;
            if (results.size() > MAX_RESULTS) {
                results = new ArrayList<>(results.subList(0, MAX_RESULTS));
                truncated = true;
            }

            log.info("[grep] 完成: query={}, path={}, 结果={}", normalizedQuery, searchPath, results.size());
            return buildSuccessResponse(results, normalizedQuery, searchPath, truncated);

        } catch (Exception e) {
            log.error("[grep] 失败: query={}, path={}, error={}", normalizedQuery, searchPath, e.getMessage(), e);
            return buildErrorResponse("搜索失败: " + e.getMessage(),
                    "请尝试使用更简单的搜索词，或检查搜索路径");
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private PathCheckResult resolveAndCheckPath(String relativePath, String workspace) {
        try {
            Path base = workspace != null ? Paths.get(workspace) : Paths.get(System.getProperty("user.dir"));
            Path resolved = base.resolve(relativePath).normalize();
            return new PathCheckResult(true, resolved, null);
        } catch (Exception e) {
            return new PathCheckResult(false, null,
                    buildErrorResponse("路径解析失败: " + e.getMessage(),
                            "请检查路径格式是否正确"));
        }
    }

    private String extractWorkspace(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object workspace = context.getContext().get(AdvisorContextConstants.WORKSPACE);
        return workspace != null ? workspace.toString() : null;
    }

    // ============================================================
    // 搜索执行（流式，不加载整个文件）
    // ============================================================

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
        final Pattern finalPattern = pattern;

        Files.walkFileTree(basePath, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
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
                        // 跳过 symlink
                        if (attrs.isSymbolicLink()) {
                            return FileVisitResult.CONTINUE;
                        }

                        String fileName = file.getFileName() != null ? file.getFileName().toString() : "";
                        String extension = getExtension(fileName);
                        if (EXCLUDED_EXTENSIONS.contains(extension)) {
                            return FileVisitResult.CONTINUE;
                        }

                        // 文件大小检查
                        if (attrs.size() > MAX_FILE_SIZE_BYTES) {
                            return FileVisitResult.CONTINUE;
                        }

                        // 流式搜索单个文件
                        try {
                            searchInFile(file, query, finalPattern, results);
                        } catch (IOException e) {
                            log.warn("读取文件失败: {}", file);
                        }

                        if (results.size() >= MAX_RESULTS + 10) {
                            return FileVisitResult.TERMINATE;
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });

        return results;
    }

    private void searchInFile(Path file, String query, Pattern pattern, List<SearchResult> results) throws IOException {
        Charset charset = detectCharset(file);

        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String line;
            int lineNum = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;

                // 跳过超长行（可能为二进制或序列化数据）
                if (line.length() > MAX_LINE_LENGTH) {
                    continue;
                }

                boolean matches;
                if (pattern != null) {
                    matches = pattern.matcher(line).find();
                } else {
                    matches = line.toLowerCase().contains(query.toLowerCase());
                }

                if (matches) {
                    results.add(new SearchResult(
                            relativizePath(file),
                            lineNum,
                            line.trim()
                    ));
                }
            }
        }
    }

    private boolean isProbablyRegex(String query) {
        String regexChars = ".*+?[]{}()|^$\\";
        return query.chars().anyMatch(c -> regexChars.indexOf(c) >= 0);
    }

    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot).toLowerCase() : "";
    }

    private String relativizePath(Path absolutePath) {
        try {
            Path workspacePath = Paths.get(System.getProperty("user.dir"));
            return workspacePath.relativize(absolutePath).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return absolutePath.toString().replace("\\", "/");
        }
    }

    private Charset detectCharset(Path file) {
        // 简单实现：默认 UTF-8，GBK 用于中文 Windows
        return StandardCharsets.UTF_8;
    }

    // ============================================================
    // 响应构建
    // ============================================================

    private String buildSuccessResponse(List<SearchResult> results, String query, String path, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索结果: \"").append(query).append("\" 在 \"").append(path).append("\"\n");
        sb.append("共找到 ").append(results.size()).append(" 处匹配:\n\n");

        Map<String, List<SearchResult>> byFile = new LinkedHashMap<>();
        for (SearchResult r : results) {
            byFile.computeIfAbsent(r.file(), k -> new ArrayList<>()).add(r);
        }

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

    private String buildEmptyResponse(String query, String path) {
        return "🔍 搜索结果: \"" + query + "\" 在 \"" + path + "\"\n\n" +
                "❌ 未找到匹配结果\n\n" +
                "建议:\n" +
                "  - 检查拼写是否正确\n" +
                "  - 尝试使用更通用的关键词\n" +
                "  - 确认搜索路径是否正确";
    }

    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" + "💡 建议: " + suggestion;
    }

    // ============================================================
    // 内部类
    // ============================================================

    private record SearchResult(String file, int line, String content) {
    }

    private record PathCheckResult(boolean approved, Path resolvedPath, String errorMessage) {
    }
}
