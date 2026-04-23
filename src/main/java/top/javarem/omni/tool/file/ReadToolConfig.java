package top.javarem.omni.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.model.context.ReadState;
import top.javarem.omni.model.context.ReadStateHolder;
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
 * 集成 dedup 机制避免重复读取未修改的文件
 */
@Component
@Slf4j
public class ReadToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "Read";
    }

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
    @Tool(name = "Read", description = """
            从本地文件系统读取文件。您可以使用此工具直接访问任何文件。
            假设此工具能够读取机器上的所有文件。如果用户提供了文件的路径，则假定该路径有效。读取不存在的文件是可以的；将返回错误。
            
            用法：
            -file_path参数必须是绝对路径，而不是相对路径
            -默认情况下，它从文件开头最多读取2000行
            -您可以选择指定行偏移和限制（对于长文件特别方便），但建议不提供这些参数来读取整个文件
            -结果使用cat-n格式返回，行号从1开始
            -此工具允许Claude Code读取图像（如PNG、JPG等）。当读取图像文件时，内容会以视觉方式呈现，因为克劳德码是一种多模式LLM。
            -此工具可以读取PDF文件（.PDF）。对于大型PDF（超过10页），您必须提供pages参数以读取特定的页面范围（例如，pages:“1-5”）。不使用pages参数读取大型PDF将失败。每个请求最多20页。
            -此工具可以读取Jupyter笔记本（.ipynb文件），并返回所有单元格及其输出，结合代码、文本和可视化。
            -此工具只能读取文件，不能读取目录。要读取目录，请通过Bash工具使用ls命令。
            -您可以在一个响应中调用多个工具。最好是推测性地并行读取多个潜在有用的文件。
            -您将经常被要求阅读屏幕截图。如果用户提供了屏幕截图的路径，请务必使用此工具查看该路径处的文件。此工具将适用于所有临时文件路径。
            -如果您读取了一个存在但内容为空的文件，您将收到一个系统提醒警告，而不是文件内容。
            """)
    public String read(
            @ToolParam(description = "文件路径。相对/绝对均可") String filePath,
            @ToolParam(description = "起始行号。默认1", required = false) Integer startLine,
            @ToolParam(description = "读取行数。默认500，最大2000", required = false) Integer maxLines,
            ToolContext context) {
        String workspace = extractWorkspace(context);
        log.info("[read] 开始执行: path={}, startLine={}, maxLines={}, workspace={}",
                filePath, startLine, maxLines, workspace);
        // 0. Dedup 状态持有器
        ReadStateHolder stateHolder = ReadStateHolder.fromContext(context);

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

        // 5. 分页参数归一化
        int start = (startLine == null || startLine < 1) ? DEFAULT_START_LINE : startLine;
        int max = (maxLines == null || maxLines < 1) ? DEFAULT_MAX_LINES
                : Math.min(maxLines, MAX_ALLOWED_LINES);

        // 6. Dedup: 检查是否有缓存的读取状态
        ReadState existingState = stateHolder.get(normalizedPath);
        if (existingState != null
                && !existingState.isPartialView()
                && existingState.matchesRange(start, max)) {
            // 文件已被完整读取且范围匹配，检查 mtime 是否变化
            try {
                long currentMtime = Files.getLastModifiedTime(targetPath).toMillis();
                if (existingState.isUnchanged(currentMtime)) {
                    log.info("[read] Dedup 命中: path={}, 文件未修改，返回 unchanged", normalizedPath);
                    return buildUnchangedResponse(normalizedPath);
                }
            } catch (IOException e) {
                // 无法获取 mtime，继续正常读取
                log.warn("[read] 获取文件 mtime 失败: path={}, error={}", normalizedPath, e.getMessage());
            }
        }

        // 7. 文件大小检查
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

        // 8. 读取文件
        try {
            long mtime = Files.getLastModifiedTime(targetPath).toMillis();
            String content = readFileContent(targetPath, normalizedPath, start, max);

            // 9. 更新 dedup 状态（仅对完整读取更新状态）
            if (start == 1 && max >= DEFAULT_MAX_LINES) {
                ReadState newState = new ReadState(content, mtime, start, null);
                stateHolder.put(normalizedPath, newState);
                log.debug("[read] 更新 dedup 状态: path={}, state={}", normalizedPath, newState);
            }

            return content;
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
            if (len >= 3 && (sample[0] & 0xFF) == 0xef && (sample[1] & 0xFF) == 0xBB && (sample[2] & 0xFF) == 0xBF) {
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

    private String buildUnchangedResponse(String filePath) {
        return "📖 文件: " + filePath + "\n\n" +
                "[文件未修改，上次已读取完整内容]";
    }

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
                        .filter(Files::isRegularFile)
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
