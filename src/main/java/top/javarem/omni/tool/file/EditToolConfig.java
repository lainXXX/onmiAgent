package top.javarem.onmi.tool.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.onmi.tool.AgentTool;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Edit 精确文件编辑工具
 * 局部替换代码，是 Agent 产出价值的核心
 * 严禁重写整个文件
 */
@Component
@Slf4j
public class EditToolConfig implements AgentTool {

    /**
     * 备份文件扩展名
     */
    private static final String BACKUP_EXTENSION = ".bak";

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

    public EditToolConfig() {
    }

    /**
     * 编辑文件内容（局部替换）
     *
     * @param filePath  要修改的文件路径
     * @param oldString 要替换的原始代码块（必须精确匹配）
     * @param newString 修改后的新代码块
     * @return 编辑结果
     */
    @Tool(name = "edit", description = "局部替换代码。约束：old_string必须精确匹配，匹配多处则拒绝")
    public String edit(
            @ToolParam(description = "目标文件路径") String filePath,
            @ToolParam(description = "原代码块。必须精确匹配(含空格缩进)") String oldString,
            @ToolParam(description = "新代码块") String newString) {
        log.info("[edit] 开始执行: path={}", filePath);
        // 1. 参数校验
        if (filePath == null || filePath.trim().isEmpty()) {
            log.error("[edit] 失败: 文件路径为空");
            return buildErrorResponse("文件路径不能为空", "请提供要编辑的文件路径");
        }
        if (oldString == null || oldString.trim().isEmpty()) {
            log.error("[edit] 失败: 原代码为空");
            return buildErrorResponse("原代码不能为空", "请提供要替换的原始代码块");
        }
        if (newString == null) {
            newString = "";
        }

        String normalizedPath = filePath.trim();

        // 2. 检查是否为二进制文件
        if (isBinaryFile(normalizedPath)) {
            log.error("[edit] 失败: 二进制文件 path={}", normalizedPath);
            return buildErrorResponse("无法编辑二进制文件: " + normalizedPath,
                    "此工具仅支持编辑文本文件，如代码、配置文件等");
        }

        // 3. 解析路径并检查权限（编辑需要更高权限）
        PathCheckResult pathCheck = resolveAndCheckPath(normalizedPath);
        if (!pathCheck.approved()) {
            return pathCheck.errorMessage();
        }
        Path targetPath = pathCheck.resolvedPath();

        // 4. 检查文件是否存在
        if (!Files.exists(targetPath)) {
            log.error("[edit] 失败: 文件不存在 path={}", normalizedPath);
            return buildErrorResponse("文件不存在: " + normalizedPath,
                    "请检查文件路径是否正确");
        }

        // 5. 创建备份
        String backupPath = createBackup(targetPath);
        if (backupPath == null) {
            log.warn("无法创建备份文件: {}", normalizedPath);
        }

        // 6. 执行编辑
        try {
            EditResult result = performEdit(targetPath, normalizedPath, oldString, newString);

            if (result.success) {
                log.info("[edit] 完成: path={}, matches={}", normalizedPath, result.matchCount);
                return buildSuccessResponse(normalizedPath, result.matchCount, backupPath);
            } else {
                // 编辑失败，恢复备份
                log.error("[edit] 失败: path={}, error={}", normalizedPath, result.errorMessage);
                if (backupPath != null) {
                    restoreFromBackup(targetPath, backupPath);
                }
                return result.errorMessage;
            }

        } catch (GrepToolConfig.AgentToolSecurityException e) {
            // 恢复备份
            if (backupPath != null) {
                restoreFromBackup(targetPath, backupPath);
            }
            throw e;
        } catch (Exception e) {
            log.error("[edit] 失败: path={}, error={}", normalizedPath, e.getMessage(), e);
            // 恢复备份
            if (backupPath != null) {
                restoreFromBackup(targetPath, backupPath);
            }
            return buildErrorResponse("文件编辑失败: " + e.getMessage(),
                    "请检查文件内容是否正确");
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
     * 执行编辑操作
     */
    private EditResult performEdit(Path targetPath, String displayPath, String oldString, String newString) throws IOException {
        // 读取文件内容
        String content = Files.readString(targetPath);

        // 归一化换行符（处理 Windows 和 Linux 差异）
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

        // 写回文件
        Files.writeString(targetPath, updatedContent);

        return new EditResult(true, 1, null);
    }

    /**
     * 归一化换行符
     */
    private String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 统计匹配次数
     */
    private int countMatches(String content, String pattern) {
        // 使用简单字符串计数（避免正则表达式特殊字符问题）
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    /**
     * 构建成功响应
     */
    private String buildSuccessResponse(String filePath, int matchCount, String backupPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 文件编辑成功\n\n");
        sb.append("📄 文件: ").append(filePath).append("\n");
        sb.append("🔄 已完成 ").append(matchCount).append(" 处替换\n");

        if (backupPath != null) {
            sb.append("💾 备份文件: ").append(backupPath).append("\n");
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
     * 编辑结果
     */
    private record EditResult(boolean success, int matchCount, String errorMessage) {
    }

    /**
     * 路径检查结果
     */
    private record PathCheckResult(boolean approved, Path resolvedPath, String errorMessage) {
    }
}
