package top.javarem.omni.tool.bash;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.AgentTool;

/**
 * Bash 命令执行工具
 */
@Component
@Slf4j
public class BashToolConfig implements AgentTool {

    @Resource
    private BashExecutor executor;

    @Tool(name = "bash", description = """
        执行 Shell 命令（编译构建、运行测试、查看进程、环境探测）。
        约束：
        - 交互式命令（vim/less）会被截断
        - 超时时间最大 600 秒
    """)
    public String bash(
            @ToolParam(description = "完整 Shell 命令。路径有空格需加双引号；建议使用绝对路径") String command,
            @ToolParam(description = "对命令行为的简洁描述（5-10字），便于审核日志", required = false) String description,
            @ToolParam(description = "超时毫秒数。默认120000ms (2分钟)，最大600000ms (10分钟)", required = false) Long timeout,
            @ToolParam(description = "是否后台运行。适用于耗时较长且不需要立即获取输出的任务", required = false) Boolean runInBackground,
            @ToolParam(description = "危险选项：是否禁用沙箱。通常用于需要突破限制的操作", required = false) Boolean dangerouslyDisableSandbox) {

        log.info("[BashToolConfig] 执行命令: {} | 描述: {} | 后台: {} | 禁用沙箱: {}",
                command, description, runInBackground, dangerouslyDisableSandbox);

        // 1. 参数校验
        if (command == null || command.trim().isEmpty()) {
            return "❌ 命令不能为空";
        }

        // 2. 沙箱检查
        if (Boolean.TRUE.equals(dangerouslyDisableSandbox)) {
            log.warn("[BashToolConfig] 沙箱已被禁用: {}", command);
            return "⚠️ 沙箱已禁用，此操作存在风险: " + command;
        }

        // 3. 超时处理
        long timeoutMs = normalizeTimeout(timeout);

        // 4. 执行命令
        try {
            if (Boolean.TRUE.equals(runInBackground)) {
                return executor.executeBackground(command);
            }
            return executor.execute(command, timeoutMs);
        } catch (Exception e) {
            log.error("[BashToolConfig] 执行异常: {}", e.getMessage(), e);
            return "❌ 命令执行异常: " + e.getMessage();
        }
    }

    private long normalizeTimeout(Long timeout) {
        if (timeout == null || timeout < 1) {
            return BashConstants.DEFAULT_TIMEOUT_MS;
        }
        return Math.min(timeout, BashConstants.MAX_TIMEOUT_MS);
    }
}
