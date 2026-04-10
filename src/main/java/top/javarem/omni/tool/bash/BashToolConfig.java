package top.javarem.omni.tool.bash;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.tool.AgentTool;

import java.util.Map;

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

        已自动批准的命令（无需用户确认，可直接执行）：
        - Git 系列：git, mvn, ./mvnw, ./mvnw clean, ./mvnw compile
        - Node/NPM 系列：npm, npm install, npm run, node, node -e
        - Python 系列：python, python3, pip
        - 常用命令：ls, cat, grep, find, java, javac

        约束：
        - 交互式命令（vim/less）会被截断
        - 超时时间最大 600 秒
        - 危险命令（如 rm -rf）需要用户审批
        - 绝对不要使用 dangerouslyDisableSandbox 参数 —— 所有常用命令都已自动批准
    """)
    public String bash(
            @ToolParam(description = "完整 Shell 命令。路径有空格需加双引号；建议使用绝对路径") String command,
            @ToolParam(description = "对命令行为的简洁描述（5-10字），便于审核日志", required = false) String description,
            @ToolParam(description = "超时毫秒数。默认120000ms (2分钟)，最大600000ms (10分钟)", required = false) Long timeout,
            @ToolParam(description = "是否后台运行。适用于耗时较长且不需要立即获取输出的任务", required = false) Boolean runInBackground,
            @ToolParam(description = "危险选项：是否禁用沙箱。通常用于需要突破限制的操作", required = false) Boolean dangerouslyDisableSandbox,
            ToolContext toolContext
            ) {

        // 从 ToolContext 获取 workspace（由 ChatController 设置）
        String workspace = extractWorkspace(toolContext);

        log.info("[BashToolConfig] 执行命令: {} | workspace: {} | 描述: {} | 后台: {} | 禁用沙箱: {}",
                command, workspace, description, runInBackground, dangerouslyDisableSandbox);

        // 1. 参数校验
        if (command == null || command.trim().isEmpty()) {
            return "❌ 命令不能为空";
        }

        // 2. 沙箱检查 — 始终拒绝
        if (Boolean.TRUE.equals(dangerouslyDisableSandbox)) {
            log.warn("[BashToolConfig] 沙箱禁用请求被拒绝: {}", command);
            return "❌ 沙箱禁用选项不被支持，拒绝执行危险操作: " + command;
        }

        // 3. 超时处理
        long timeoutMs = normalizeTimeout(timeout);

        // 4. 执行命令
        try {
            if (Boolean.TRUE.equals(runInBackground)) {
                return executor.executeBackground(command, workspace);
            }
            return executor.execute(command, timeoutMs, workspace);
        } catch (Exception e) {
            log.error("[BashToolConfig] 执行异常: {}", e.getMessage(), e);
            return "❌ 命令执行异常: " + e.getMessage();
        }
    }

    private String extractWorkspace(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object workspaceObj = toolContext.getContext().get(AdvisorContextConstants.WORKSPACE);
        if (workspaceObj != null) {
            String workspace = workspaceObj.toString();
            if (!workspace.isBlank()) {
                return workspace;
            }
        }
        return null;
    }

    private long normalizeTimeout(Long timeout) {
        if (timeout == null || timeout < 1) {
            return BashConstants.DEFAULT_TIMEOUT_MS;
        }
        return Math.min(timeout, BashConstants.MAX_TIMEOUT_MS);
    }
}
