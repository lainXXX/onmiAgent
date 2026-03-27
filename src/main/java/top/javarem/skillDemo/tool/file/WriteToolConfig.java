package top.javarem.skillDemo.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.skillDemo.tool.AgentTool;

import java.io.IOException;
import java.nio.file.*;

/**
 * Write 文件写入工具
 * 用于创建全新的类文件、配置文件或脚本
 * 支持自动创建父目录
 */
@Component
@Slf4j
public class WriteToolConfig implements AgentTool {

    /**
     * 备份文件扩展名
     */
    private static final String BACKUP_EXTENSION = ".bak";

    /**
     * 工作目录
     */
    private static final String WORKSPACE = System.getProperty("user.dir");

    public WriteToolConfig() {
    }

    /**
     * 创建或覆盖文件
     *
     * @param filePath 文件路径
     * @param content  文件完整内容
     * @return 写入结果
     */
    @Tool(name = "write", description = "创建/覆盖文件。约束：会覆盖原内容，需先read确认")
    public String write(
            @ToolParam(description = "文件路径。父目录不存在自动创建") String filePath,
            @ToolParam(description = "文件完整内容") String content) {
        log.info("[write] 开始执行: path={}, contentLength={}", filePath, content != null ? content.length() : 0);
        // 1. 参数校验
        if (filePath == null || filePath.trim().isEmpty()) {
            log.error("[write] 失败: 文件路径为空");
            return buildErrorResponse("文件路径不能为空", "请提供要写入的文件路径");
        }
        if (content == null) {
            content = "";
        }

        String normalizedPath = filePath.trim();

        // 2. 解析路径并检查权限
        PathCheckResult pathCheck = resolveAndCheckPath(normalizedPath);
        if (!pathCheck.approved()) {
            log.error("[write] 失败: 路径解析失败 path={}", normalizedPath);
            return pathCheck.errorMessage();
        }
        Path targetPath = pathCheck.resolvedPath();

        // 3. 如果文件存在，先创建备份
        String backupPath = null;
        if (Files.exists(targetPath)) {
            backupPath = createBackup(targetPath);
            if (backupPath == null) {
                log.warn("无法创建备份文件: {}", normalizedPath);
            }
        }

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

        // 5. 写入文件
        try {
            Files.writeString(targetPath, content, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            log.info("[write] 完成: path={}, bytes={}", normalizedPath, content.length());
            return buildSuccessResponse(normalizedPath, content, backupPath);

        } catch (GrepToolConfig.AgentToolSecurityException e) {
            // 恢复备份
            if (backupPath != null) {
                restoreFromBackup(targetPath, backupPath);
            }
            throw e;
        } catch (Exception e) {
            log.error("[write] 失败: path={}, error={}", normalizedPath, e.getMessage(), e);
            // 恢复备份
            if (backupPath != null) {
                restoreFromBackup(targetPath, backupPath);
            }
            return buildErrorResponse("文件写入失败: " + e.getMessage(),
                    "请检查路径和权限是否正确");
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
     * 创建备份
     */
    private String createBackup(Path targetPath) {
        try {
            Path backupPath = Paths.get(targetPath.toString() + BACKUP_EXTENSION);
            Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            return backupPath.toString();
        } catch (IOException e) {
            log.warn("创建备份失败: {}", targetPath, e);
            return null;
        }
    }

    /**
     * 从备份恢复
     */
    private void restoreFromBackup(Path targetPath, String backupPath) {
        try {
            Path backup = Paths.get(backupPath);
            if (Files.exists(backup)) {
                Files.copy(backup, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("已从备份恢复文件: {}", targetPath);
            }
        } catch (IOException e) {
            log.error("从备份恢复失败: {}", targetPath, e);
        }
    }

    /**
     * 构建成功响应
     */
    private String buildSuccessResponse(String filePath, String content, String backupPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 文件写入成功\n\n");
        sb.append("📄 文件: ").append(filePath).append("\n");
        sb.append("📊 内容长度: ").append(content.length()).append(" 字符\n");
        sb.append("📝 行数: ").append(content.split("\n").length).append(" 行\n");

        if (backupPath != null) {
            sb.append("💾 原文件已备份: ").append(backupPath).append("\n");
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
