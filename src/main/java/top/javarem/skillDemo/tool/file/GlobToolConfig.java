package top.javarem.skillDemo.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.skillDemo.tool.AgentTool;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
/**
 * Glob 目录与文件结构探索工具
 * 按模式查找文件路径，帮助 Agent 建立项目宏观认知
 */
@Component
@Slf4j
public class GlobToolConfig implements AgentTool {

    /**
     * 默认搜索路径
     */
    private static final String DEFAULT_PATH = ".";

    /**
     * 最大返回结果数
     */
    private static final int MAX_RESULTS = 100;

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
            ".woff2", ".ttf", ".eot", ".map"
    );

    /**
     * 工作目录
     */
    private static final String WORKSPACE = System.getProperty("user.dir");

    public GlobToolConfig() {
    }

    /**
     * 按模式查找文件路径
     * @param pattern pattern 匹配模式
     * @param path 搜索起点目录，默认为当前项目根目录
     * @return 匹配的文件路径列表
     */
    @Tool(name = "glob", description = "文件路径搜索。适用：了解结构、定位某类文件。禁止：搜内容(用grep)。返回：路径列表")
    public String glob(
            @ToolParam(description = "Glob模式。示例：**/*.java, src/**/*Controller.java") String pattern,
            @ToolParam(description = "搜索起点。默认当前目录", required = false) String path) {
        log.info("[glob] 开始执行: pattern={}, path={}", pattern, path);
        // 1. 参数校验与归一化
        String normalizedPattern = pattern == null ? "" : pattern.trim();
        if (normalizedPattern.isEmpty()) {
            log.error("[glob] 失败: 匹配模式为空");
            return buildErrorResponse("匹配模式不能为空", "请提供有效的 glob 模式，如 **/*.java");
        }

        String searchPath = (path == null || path.trim().isEmpty()) ? DEFAULT_PATH : path.trim();

        // 2. 解析路径并检查权限
        PathCheckResult pathCheck = resolveAndCheckPath(searchPath);
        if (!pathCheck.approved()) {
            return pathCheck.errorMessage();
        }
        Path basePath = pathCheck.resolvedPath();

        // 3. 执行 glob 搜索
        try {
            List<String> matchedFiles = performGlob(normalizedPattern, basePath);

            if (matchedFiles.isEmpty()) {
                log.info("[glob] 完成: pattern={}, path={}, 结果=0", normalizedPattern, searchPath);
                return buildEmptyResponse(normalizedPattern, searchPath);
            }

            // 限制结果数量
            boolean truncated = false;
            if (matchedFiles.size() > MAX_RESULTS) {
                matchedFiles = matchedFiles.subList(0, MAX_RESULTS);
                truncated = true;
            }

            log.info("[glob] 完成: pattern={}, path={}, 结果={}", normalizedPattern, searchPath, matchedFiles.size());
            return buildSuccessResponse(matchedFiles, normalizedPattern, searchPath, truncated);

        } catch (GrepToolConfig.AgentToolSecurityException e) {
            log.error("[glob] 失败: path={}, error=访问被拒绝", searchPath);
            throw e;
        } catch (Exception e) {
            log.error("[glob] 失败: pattern={}, path={}, error={}", normalizedPattern, searchPath, e.getMessage(), e);
            return buildErrorResponse("搜索失败: " + e.getMessage(),
                    "请检查 glob 模式是否正确");
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
     * 执行 glob 搜索
     */
    private List<String> performGlob(String pattern, Path basePath) throws IOException {
        // 将 glob 模式转换为 PathMatcher
        // 注意：Java 的 glob 模式与常见的 glob 有一些差异
        String regexPattern = globToRegex(pattern);
        Pattern regex = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);

        List<String> results = new ArrayList<>();

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

                        // 过滤二进制文件
                        if (EXCLUDED_EXTENSIONS.contains(extension)) {
                            return FileVisitResult.CONTINUE;
                        }

                        // 检查是否匹配模式
                        String relativePath = relativizePath(file);
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

    /**
     * 将 glob 模式转换为正则表达式
     */
    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");

        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    // * 匹配任意字符（不含路径分隔符）
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        // ** 匹配任意目录
                        regex.append(".*");
                        i++; // 跳过第二个 *
                        if (i + 1 < glob.length() && (glob.charAt(i + 1) == '/' || glob.charAt(i + 1) == '\\')) {
                            regex.append("/?"); // ** 可以匹配零个或多个目录
                            i++;
                        }
                    } else {
                        // 单个 * 不匹配路径分隔符
                        regex.append("[^/\\\\]*");
                    }
                    break;
                case '?':
                    regex.append(".");
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
            }
        }

        regex.append("$");
        return regex.toString();
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

    /**
     * 构建空结果响应
     */
    private String buildEmptyResponse(String pattern, String path) {
        return "📂 Glob 匹配结果: \"" + pattern + "\" 在 \"" + path + "\"\n\n" +
                "❌ 未找到匹配文件\n\n" +
                "建议:\n" +
                "  - 检查 glob 模式是否正确\n" +
                "  - 确认搜索路径是否正确\n" +
                "  - 常见模式示例：**/*.java, src/**/*.kt, **/pom.xml";
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }

    /**
     * 路径检查结果
     */
    private record PathCheckResult(boolean approved, Path resolvedPath, String errorMessage) {
    }
}
