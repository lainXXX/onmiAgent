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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Edit 精确文件编辑工具
 * 局部替换代码，是 Agent 产出价值的核心
 * 严禁重写整个文件
 */
@Component
@Slf4j
public class EditToolConfig implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".class", ".png", ".jpg",
            ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".woff",
            ".woff2", ".ttf", ".eot", ".map", ".exe", ".dll",
            ".so", ".dylib", ".zip", ".tar", ".gz", ".pdf"
    );

    /**
     * 编辑文件内容（局部替换）
     *
     * @param filePath  要修改的文件路径
     * @param oldString 要替换的原始代码块（必须精确匹配）
     * @param newString 修改后的新代码块
     * @param context   ToolContext，用于获取动态 workspace
     * @return 编辑结果
     */
    @Tool(name = "edit", description = "局部替换代码。约束：old_string必须精确匹配，匹配多处则拒绝")
    public String edit(
            @ToolParam(description = "目标文件路径") String filePath,
            @ToolParam(description = "原代码块。必须精确匹配(含空格缩进)") String oldString,
            @ToolParam(description = "新代码块") String newString,
            ToolContext context) {
        String workspace = extractWorkspace(context);
        log.info("[edit] 开始执行: path={}, workspace={}", filePath, workspace);

        // 1. 参数校验
        if (filePath == null || filePath.trim().isEmpty()) {
            return buildErrorResponse("文件路径不能为空", "请提供要编辑的文件路径");
        }
        if (oldString == null || oldString.trim().isEmpty()) {
            return buildErrorResponse("原代码不能为空", "请提供要替换的原始代码块");
        }
        if (newString == null) {
            newString = "";
        }

        String normalizedPath = filePath.trim();

        // 2. 二进制文件过滤
        if (isBinaryFile(normalizedPath)) {
            return buildErrorResponse("无法编辑二进制文件: " + normalizedPath,
                    "此工具仅支持编辑文本文件，如代码、配置文件等");
        }

        // 3. 解析路径
        PathCheckResult pathCheck = resolveAndCheckPath(normalizedPath, workspace);
        if (!pathCheck.approved()) {
            return pathCheck.errorMessage();
        }
        Path targetPath = pathCheck.resolvedPath();

        // 4. 文件存在性检查
        if (!Files.exists(targetPath)) {
            return buildErrorResponse("文件不存在: " + normalizedPath,
                    "请检查文件路径是否正确");
        }

        // 5. 文件大小检查
        try {
            long fileSize = Files.size(targetPath);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return buildErrorResponse("文件过大: " + fileSize + " bytes",
                        "大文件编辑风险较高，建议使用 write 工具重写");
            }
        } catch (IOException e) {
            return buildErrorResponse("无法获取文件大小: " + e.getMessage(), null);
        }

        // 6. 原子性编辑（temp + rename），不产生 .bak 污染
        Path tempPath = null;
        try {
            EditResult result = performEdit(targetPath, normalizedPath, oldString, newString);

            if (!result.success) {
                return result.errorMessage;
            }

            log.info("[edit] 完成: path={}, matches={}", normalizedPath, result.matchCount);
            return buildSuccessResponse(normalizedPath, result.matchCount);

        } catch (Exception e) {
            log.error("[edit] 失败: path={}, error={}", normalizedPath, e.getMessage(), e);
            return buildErrorResponse("文件编辑失败: " + e.getMessage(),
                    "请检查文件内容是否正确");
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private PathCheckResult resolveAndCheckPath(String relativePath, String workspace) {
        try {
            // 归一化 Git Bash 风格路径
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
        if (path.matches("^/[a-z]/.*") || path.matches("^/[A-Z]/.*")) {
            char drive = path.charAt(1);
            String rest = path.substring(3).replace("/", "\\");
            return drive + ":\\" + rest;
        }
        return path;
    }

    private boolean isBinaryFile(String filePath) {
        String lowerPath = filePath.toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
    }

    // ============================================================
    // 编辑执行（原子性写入）
    // ============================================================

    private EditResult performEdit(Path targetPath, String displayPath, String oldString, String newString) throws IOException {
        // 读取文件内容
        String content = Files.readString(targetPath);

        // 归一化换行符
        String normalizedContent = normalizeLineEndings(content);
        String normalizedOldString = normalizeLineEndings(oldString);

        // 检查是否匹配多次
        int matchCount = countMatches(normalizedContent, normalizedOldString);

        if (matchCount == 0) {
            return new EditResult(false, 0,
                    buildErrorResponse("未找到要替换的代码块",
                            "请确保 old_string 与文件中的内容完全匹配（注意空格、缩进和换行符）。" +
                                    "建议从文件读取完整行以确保准确性。"));
        }

        if (matchCount > 1) {
            return new EditResult(false, 0,
                    buildErrorResponse("代码块匹配到多处位置",
                            "old_string 在文件中匹配到 " + matchCount + " 处，无法确定要修改哪一处。" +
                                    "请提供更多上下文（包含更多前后代码）以保证唯一性。"));
        }

        // 执行替换
        String updatedContent = normalizedContent.replace(normalizedOldString, newString);

        // 原子性写入：temp file + rename（Windows 兼容，不使用 PosixFilePermissions）
        Path tempPath = Files.createTempFile(targetPath.getParent(), ".edit_tmp_", ".tmp");

        try {
            Files.writeString(tempPath, updatedContent, StandardOpenOption.WRITE);
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            throw e;
        }

        return new EditResult(true, 1, null);
    }

    private String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    private int countMatches(String content, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    // ============================================================
    // 响应构建
    // ============================================================

    private String buildSuccessResponse(String filePath, int matchCount) {
        return "✅ 文件编辑成功\n\n" +
                "📄 文件: " + filePath + "\n" +
                "🔄 已完成 " + matchCount + " 处替换";
    }

    private String buildErrorResponse(String error, String suggestion) {
        if (suggestion == null) {
            return "❌ " + error;
        }
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

    private record EditResult(boolean success, int matchCount, String errorMessage) {
    }

    private record PathCheckResult(boolean approved, Path resolvedPath, String errorMessage) {
    }
}
