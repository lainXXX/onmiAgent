package top.javarem.skillDemo.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.skillDemo.tool.AgentTool;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Read 文件读取工具
 * 获取文件具体内容，支持分页读取
 */
@Component
@Slf4j
public class ReadToolConfig implements AgentTool {

    /**
     * 默认起始行号
     */
    private static final int DEFAULT_START_LINE = 1;

    /**
     * 默认最大行数
     */
    private static final int DEFAULT_MAX_LINES = 500;

    /**
     * 最大允许的行数
     */
    private static final int MAX_ALLOWED_LINES = 2000;

    /**
     * 需要过滤的二进制文件扩展名
     */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".class", ".png", ".jpg",
            ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".woff",
            ".woff2", ".ttf", ".eot", ".map", ".exe", ".dll",
            ".so", ".dylib", ".zip", ".tar", ".gz", ".pdf"
    );

    /**
     * 工作目录
     */
    private static final String WORKSPACE = System.getProperty("user.dir");

    public ReadToolConfig() {
    }

    /**
     * 读取文件内容
     *
     * @param filePath 目标文件路径
     * @param startLine 起始行号，默认1
     * @param maxLines 读取最大行数，默认500
     * @return 文件内容
     */
    @Tool(name = "read", description = "读取文件内容。适用：查看代码/配置。禁止：探索结构(先glob)。返回：带行号内容")
    public String read(
            @ToolParam(description = "文件路径。相对/绝对均可") String filePath,
            @ToolParam(description = "起始行号。默认1", required = false) Integer startLine,
            @ToolParam(description = "读取行数。默认500，最大2000", required = false) Integer maxLines) {
        log.info("[read] 开始执行: path={}, startLine={}, maxLines={}", filePath, startLine, maxLines);
        // 1. 参数校验
        if (filePath == null || filePath.trim().isEmpty()) {
            log.error("[read] 失败: 文件路径为空");
            return buildErrorResponse("文件路径不能为空", "请提供要读取的文件路径");
        }

        String normalizedPath = filePath.trim();

        // 2. 检查是否为二进制文件
        if (isBinaryFile(normalizedPath)) {
            log.error("[read] 失败: 二进制文件 path={}", normalizedPath);
            return buildErrorResponse("无法读取二进制文件: " + normalizedPath,
                    "此工具仅支持读取文本文件，如代码、配置、文档等");
        }

        // 3. 解析路径并检查权限
        PathCheckResult pathCheck = resolveAndCheckPath(normalizedPath);
        if (!pathCheck.approved()) {
            return pathCheck.errorMessage();
        }
        Path targetPath = pathCheck.resolvedPath();

        // 4. 检查文件是否存在
        if (!Files.exists(targetPath)) {
            log.error("[read] 失败: 文件不存在 path={}", normalizedPath);
            return buildFileNotFoundResponse(normalizedPath);
        }

        // 5. 规范化分页参数
        int start = (startLine == null || startLine < 1) ? DEFAULT_START_LINE : startLine;
        int max = (maxLines == null || maxLines < 1) ? DEFAULT_MAX_LINES : Math.min(maxLines, MAX_ALLOWED_LINES);

        // 6. 读取文件
        try {
            return readFileContent(targetPath, normalizedPath, start, max);
        } catch (GrepToolConfig.AgentToolSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[read] 失败: path={}, error={}", normalizedPath, e.getMessage(), e);
            return buildErrorResponse("文件读取失败: " + e.getMessage(),
                    "请检查文件路径是否正确，以及是否有读取权限");
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
     * 检查是否为二进制文件
     */
    private boolean isBinaryFile(String filePath) {
        String lowerPath = filePath.toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
    }

    /**
     * 读取文件内容
     */
    private String readFileContent(Path targetPath, String displayPath, int startLine, int maxLines) throws IOException {
        // 检测文件编码
        Charset charset = detectCharset(targetPath);

        List<String> allLines = Files.readAllLines(targetPath, charset);
        int totalLines = allLines.size();

        // 计算实际读取范围
        int actualStart = Math.min(startLine, totalLines);
        if (actualStart < 1) actualStart = 1;

        int actualEnd = Math.min(actualStart + maxLines - 1, totalLines);

        if (actualStart > actualEnd) {
            return buildErrorResponse("起始行号超出文件范围",
                    "文件共有 " + totalLines + " 行，请使用 1-" + totalLines + " 之间的行号");
        }

        // 提取指定范围的行
        List<String> contentLines = allLines.subList(actualStart - 1, actualEnd);

        // 构建响应
        StringBuilder sb = new StringBuilder();
        sb.append("📖 文件: ").append(displayPath).append("\n");
        sb.append("📊 总行数: ").append(totalLines).append("\n");
        sb.append("📖 显示行: ").append(actualStart).append("-").append(actualEnd).append("\n");
        sb.append("─────────────────────────────────────────\n\n");

        // 添加行号
        for (int i = 0; i < contentLines.size(); i++) {
            int lineNum = actualStart + i;
            String line = contentLines.get(i);
            sb.append(String.format("%5d  %s\n", lineNum, line));
        }

        // 分页提示
        if (actualEnd < totalLines) {
            sb.append("\n─────────────────────────────────────────\n");
            sb.append("[文件已截断] 请传入 start_line=").append(actualEnd + 1)
                    .append(" 继续读取剩余 ").append(totalLines - actualEnd).append(" 行");
        }

        log.info("[read] 完成: path={}, lines={}-{}, totalLines={}", displayPath, actualStart, actualEnd, totalLines);
        return sb.toString();
    }

    /**
     * 检测文件编码
     */
    private Charset detectCharset(Path filePath) {
        // 尝试 UTF-8
        try {
            Files.readAllLines(filePath, StandardCharsets.UTF_8);
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            // 尝试 GBK（中文 Windows 常见）
            try {
                Files.readAllLines(filePath, Charset.forName("GBK"));
                return Charset.forName("GBK");
            } catch (Exception ex) {
                // 默认 UTF-8
                return StandardCharsets.UTF_8;
            }
        }
    }

    /**
     * 构建文件不存在响应
     */
    private String buildFileNotFoundResponse(String filePath) {
        // 尝试列出父目录下的文件，帮助纠正拼写
        StringBuilder sb = new StringBuilder();
        sb.append("❌ 文件不存在: ").append(filePath).append("\n\n");
        sb.append("💡 建议:\n");
        sb.append("  - 检查文件路径是否正确\n");
        sb.append("  - 检查文件名拼写是否有误\n");

        // 尝试列出附近文件
        try {
            Path base = Paths.get(WORKSPACE);
            Path parent = base.resolve(filePath).getParent();
            if (parent != null && Files.exists(parent)) {
                sb.append("\n📂 父目录下的文件:\n");
                Files.list(parent)
                        .filter(p -> Files.isRegularFile(p))
                        .limit(10)
                        .forEach(p -> sb.append("  - ").append(p.getFileName()).append("\n"));
            }
        } catch (IOException e) {
            // 忽略
        }

        return sb.toString();
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
