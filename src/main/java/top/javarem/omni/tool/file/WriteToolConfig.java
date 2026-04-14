package top.javarem.omni.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.model.context.ReadStateHolder;
import top.javarem.omni.tool.AgentTool;

import java.io.IOException;
import java.nio.file.*;

/**
 * Write 文件写入工具
 * 创建全新的类文件、配置文件或脚本
 * 使用原子性写入（temp + rename），不污染工作目录
 */
@Component
@Slf4j
public class WriteToolConfig implements AgentTool {

    private static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5MB 内容限制

    /**
     * 创建或覆盖文件
     *
     * @param filePath 文件路径
     * @param content  文件完整内容
     * @param context  ToolContext，用于获取动态 workspace
     * @return 写入结果
     */
    @Tool(name = "write", description = "创建/覆盖文件。约束：会覆盖原内容，需先read确认")
    public String write(
            @ToolParam(description = "文件路径。父目录不存在自动创建") String filePath,
            @ToolParam(description = "文件完整内容") String content,
            ToolContext context) {
        String workspace = extractWorkspace(context);
        log.info("[write] 开始执行: path={}, contentLength={}, workspace={}",
                filePath, content != null ? content.length() : 0, workspace);

        // 0. 获取 dedup 状态持有器（用于清除缓存）
        ReadStateHolder stateHolder = ReadStateHolder.fromContext(context);

        // 1. 参数校验
        if (filePath == null || filePath.trim().isEmpty()) {
            return buildErrorResponse("文件路径不能为空", "请提供要写入的文件路径");
        }
        if (content == null) {
            content = "";
        }

        // 2. 内容大小校验
        if (content.length() > MAX_FILE_SIZE_BYTES) {
            return buildErrorResponse("文件内容过大: " + content.length() + " bytes",
                    "内容大小限制为 5MB，请拆分内容或减少写入量");
        }

        String normalizedPath = filePath.trim();

        // 3. 解析路径（包含 Git Bash 路径归一化）
        PathCheckResult pathCheck = resolveAndCheckPath(normalizedPath, workspace);
        if (!pathCheck.approved()) {
            return pathCheck.errorMessage();
        }
        Path targetPath = pathCheck.resolvedPath();

        // 4. 确保父目录存在
        try {
            Path parent = targetPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("已自动创建目录: {}", parent);
            }
        } catch (IOException e) {
            return buildErrorResponse("无法创建父目录: " + e.getMessage(),
                    "请检查路径是否正确");
        }

        // 5. 原子性写入（temp file + rename），不产生 .bak 污染
        Path tempPath = null;
        try {
            // 创建临时文件在同一目录下，确保在同一文件系统以便原子性 rename
            // 注意：不使用 PosixFilePermissions（Windows 不支持）
            tempPath = Files.createTempFile(targetPath.getParent(), ".write_tmp_", ".tmp");

            Files.writeString(tempPath, content, StandardOpenOption.WRITE);

            // 原子性替换原文件
            Files.move(tempPath, targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            // 6. 写入成功后清除 read dedup 缓存
            stateHolder.remove(normalizedPath);
            log.debug("[write] 清除 read dedup 缓存: path={}", normalizedPath);

            log.info("[write] 完成: path={}, bytes={}", normalizedPath, content.length());
            return buildSuccessResponse(normalizedPath, content);

        } catch (Exception e) {
            log.error("[write] 失败: path={}, error={}", normalizedPath, e.getMessage(), e);
            // 清理临时文件
            if (tempPath != null) {
                try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            }
            return buildErrorResponse("文件写入失败: " + e.getMessage(),
                    "请检查路径和权限是否正确");
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private PathCheckResult resolveAndCheckPath(String relativePath, String workspace) {
        try {
            // 归一化 Git Bash 风格路径（如 /c/... -> C:\...）
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

    /**
     * 标准化路径，将 Git Bash 风格路径转换为 Windows 原生路径
     */
    private String normalizeGitBashPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        // Git Bash 风格路径检测：/c/... 或 /d/...
        if (path.matches("^/[a-z]/.*") || path.matches("^/[A-Z]/.*")) {
            char drive = path.charAt(1);
            String rest = path.substring(3).replace("/", "\\");
            return drive + ":\\" + rest;
        }
        return path;
    }

    private String buildSuccessResponse(String filePath, String content) {
        return "✅ 文件写入成功\n\n" +
                "📄 文件: " + filePath + "\n" +
                "📊 内容长度: " + content.length() + " 字符\n" +
                "📝 行数: " + content.split("\n").length + " 行";
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
