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
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private static final int DEFAULT_START_LINE = 1;
    private static final int DEFAULT_MAX_LINES = 500;
    private static final int MAX_ALLOWED_LINES = 2000;
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".class", ".png", ".jpg",
            ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".woff",
            ".woff2", ".ttf", ".eot", ".map", ".exe", ".dll",
            ".so", ".dylib", ".zip", ".tar", ".gz", ".pdf"
    );

    /**
     * 读取文件内容
     *
     * @param filePath  目标文件路径
     * @param startLine 起始行号，默认1
     * @param maxLines  读取最大行数，默认500
     * @param context   ToolContext，用于获取动态 workspace
     * @return 文件内容
     */
    @Tool(name = "read", description = "读取文件内容。适用：查看代码/配置。禁止：探索结构(先glob)。返回：带行号内容")
    public String read(
            @ToolParam(description = "文件路径。相对/绝对均可") String filePath,
            @ToolParam(description = "起始行号。默认1", required = false) Integer startLine,
            @ToolParam(description = "读取行数。默认500，最大2000", required = false) Integer maxLines,
            ToolContext context) {
        String workspace = extractWorkspace(context);
        log.info("[read] 开始执行: path={}, startLine={}, maxLines={}, workspace={}",
                filePath, startLine, maxLines, workspace);

        // 1. 参数校验
        if (filePath == null || filePath.trim().isEmpty()) {
            return buildErrorResponse("文件路径不能为空", "请提供要读取的文件路径");
        }

        String normalizedPath = filePath.trim();

        // 2. 二进制文件过滤
        if (isBinaryFile(normalizedPath)) {
            return buildErrorResponse("无法读取二进制文件: " + normalizedPath,
                    "此工具仅支持读取文本文件，如代码、配置、文档等");
        }

        // 3. 解析路径
        PathCheckResult pathCheck = resolveAndCheckPath(normalizedPath, workspace);
        if (!pathCheck.approved()) {
            return pathCheck.errorMessage();
        }
        Path targetPath = pathCheck.resolvedPath();

        // 4. 文件存在性检查
        if (!Files.exists(targetPath)) {
            return buildFileNotFoundResponse(normalizedPath, workspace);
        }

        // 5. 文件大小检查
        try {
            long fileSize = Files.size(targetPath);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return buildErrorResponse("文件过大: " + fileSize + " bytes",
                        "文件大小超过 10MB 限制，请使用 grep 搜索特定内容");
            }
        } catch (IOException e) {
            return buildErrorResponse("无法获取文件大小: " + e.getMessage(),
                    "请检查文件路径是否正确");
        }

        // 6. 分页参数归一化
        int start = (startLine == null || startLine < 1) ? DEFAULT_START_LINE : startLine;
        int max = (maxLines == null || maxLines < 1) ? DEFAULT_MAX_LINES
                : Math.min(maxLines, MAX_ALLOWED_LINES);

        // 7. 读取文件
        try {
            return readFileContent(targetPath, normalizedPath, start, max);
        } catch (Exception e) {
            log.error("[read] 失败: path={}, error={}", normalizedPath, e.getMessage(), e);
            return buildErrorResponse("文件读取失败: " + e.getMessage(),
                    "请检查文件路径是否正确，以及是否有读取权限");
        }
    }

    // ============================================================
    // 路径解析
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

    private boolean isBinaryFile(String filePath) {
        String lowerPath = filePath.toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
    }

    // ============================================================
    // 文件读取（流式，单次遍历）
    // ============================================================

    private String readFileContent(Path targetPath, String displayPath, int startLine, int maxLines) throws IOException {
        Charset charset = detectCharset(targetPath);

        // 使用 BufferedReader 替代 Scanner，避免阻塞问题
        List<String> contentLines = new ArrayList<>(maxLines);
        int totalLines = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(targetPath), charset))) {

            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                int lineNum = totalLines;

                if (lineNum < startLine) continue;
                if (contentLines.size() >= maxLines) {
                    // 消耗剩余行以获取总数
                    while (reader.readLine() != null) {
                        totalLines++;
                    }
                    break;
                }

                contentLines.add(line);
            }
        }

        int actualEnd = startLine + contentLines.size() - 1;

        if (contentLines.isEmpty() && startLine > 1) {
            return buildErrorResponse("起始行号超出文件范围",
                    "文件共有 " + totalLines + " 行，请使用 1-" + totalLines + " 之间的行号");
        }

        // 构建响应
        StringBuilder sb = new StringBuilder();
        sb.append("📖 文件: ").append(displayPath).append("\n");
        sb.append("📊 总行数: ").append(totalLines).append("\n");
        sb.append("📖 显示行: ").append(startLine).append("-").append(actualEnd).append("\n");
        sb.append("─────────────────────────────────────────\n\n");

        for (int i = 0; i < contentLines.size(); i++) {
            int lineNum = startLine + i;
            String line = contentLines.get(i);
            sb.append(String.format("%5d  %s\n", lineNum, line));
        }

        if (actualEnd < totalLines) {
            sb.append("\n─────────────────────────────────────────\n");
            sb.append("[文件已截断] 请传入 start_line=").append(actualEnd + 1)
                    .append(" 继续读取剩余 ").append(totalLines - actualEnd).append(" 行");
        }

        log.info("[read] 完成: path={}, lines={}-{}, totalLines={}", displayPath, startLine, actualEnd, totalLines);
        return sb.toString();
    }

    // ============================================================
    // 编码检测（单次遍历）
    // ============================================================

    private Charset detectCharset(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] sample = new byte[8192];
            int len = is.read(sample);

            if (len <= 0) {
                return StandardCharsets.UTF_8;
            }

            // 检测 BOM
            if (len >= 3 && (sample[0] & 0xFF) == 0xEF && (sample[1] & 0xFF) == 0xBB && (sample[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }

            // 检测是否为有效 UTF-8
            if (isValidUtf8(sample, len)) {
                return StandardCharsets.UTF_8;
            }

            // 尝试 GBK
            return Charset.forName("GBK");
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private boolean isValidUtf8(byte[] bytes, int length) {
        int i = 0;
        while (i < length) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= length) return false;
                if ((bytes[i + 1] & 0xC0) != 0x80) return false;
                i += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= length) return false;
                if ((bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80) return false;
                i += 3;
            } else if ((b & 0xF8) == 0xF0) {
                if (i + 3 >= length) return false;
                if ((bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80 || (bytes[i + 3] & 0xC0) != 0x80) return false;
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }

    // ============================================================
    // 响应构建
    // ============================================================

    private String buildFileNotFoundResponse(String filePath, String workspace) {
        StringBuilder sb = new StringBuilder();
        sb.append("❌ 文件不存在: ").append(filePath).append("\n\n");
        sb.append("💡 建议:\n");
        sb.append("  - 检查文件路径是否正确\n");
        sb.append("  - 检查文件名拼写是否有误\n");

        // 尝试列出父目录下的文件
        try {
            Path base = workspace != null ? Paths.get(workspace) : Paths.get(System.getProperty("user.dir"));
            Path parent = base.resolve(filePath).getParent();
            if (parent != null && Files.exists(parent)) {
                sb.append("\n📂 父目录下的文件:\n");
                Files.list(parent)
                        .filter(p -> Files.isRegularFile(p))
                        .limit(10)
                        .forEach(p -> sb.append("  - ").append(p.getFileName()).append("\n"));
            }
        } catch (IOException ignored) {
        }

        return sb.toString();
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

    // ============================================================
    // 内部类
    // ============================================================

    private record PathCheckResult(boolean approved, Path resolvedPath, String errorMessage) {
    }
}
