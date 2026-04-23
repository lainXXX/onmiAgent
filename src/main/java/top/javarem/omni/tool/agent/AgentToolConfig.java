package top.javarem.omni.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.AgentTool;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agent 工具配置
 * 提供子 Agent 启动和结果查询能力
 *
 * 架构特性：
 * - 采用统一生命周期 ID 贯穿沙盒、执行器与注册表
 * - 支持 Worktree 级别的物理运行环境隔离
 * - 完备的异常处理与现场清理机制 (Graceful Cleanup)
 */
@Slf4j
@Component
public class AgentToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "Agent";
    }

    private final AgentTaskRegistry registry;
    private final SubAgentChatClientFactory factory;
    private final WorktreeManager worktreeManager;
    private final ExecutorService executor;

    private static final long DEFAULT_TIMEOUT_MS = 180000; // 3分钟

    public AgentToolConfig(
            AgentTaskRegistry registry,
            @Lazy SubAgentChatClientFactory factory,
            WorktreeManager worktreeManager,
            @Qualifier("agentExecutor") ExecutorService executor) {
        this.registry = registry;
        this.factory = factory;
        this.worktreeManager = worktreeManager;
        this.executor = executor;
    }

    @Tool(name = "Agent", description = "启动一个新的代理，自主处理复杂、多步骤的任务。" +
            "代理工具启动专门的代理（子进程），自主处理复杂任务。每种代理类型都有其特定的功能和可用工具。" +
            "可用的代理类型: explore（代码探索 One-Shot）, plan（计划制定 One-Shot）, " +
            "verification（验收测试）, general（通用）, code-reviewer（代码审查）")
    public String launchAgent(
            @ToolParam(description = "Task name (3-5 words) for parallel identification") String description,
            @ToolParam(description = "Detailed instructions for the sub-agent") String prompt,
            @ToolParam(description = "Agent type: explore/plan/verification/general/code-reviewer/claude-code-guide", required = false) String subagentType,
            @ToolParam(description = "Run asynchronously in background", required = false) Boolean runInBackground,
            @ToolParam(description = "Agent ID to resume from", required = false) String resume,
            @ToolParam(description = "Isolation mode: worktree", required = false) String isolation,
            ToolContext toolContext
    ) {
        String userId = extractUserId(toolContext);
        String sessionId = extractSessionId(toolContext);
        AgentType type = AgentType.fromValue(subagentType);
        boolean isBackground = runInBackground != null && runInBackground;

        log.info("[AgentTool] 收到启动指令: description={}, type={}, background={}, resume={}, isolation={}, userId={}",
                description, type.getValue(), isBackground, resume, isolation, userId);

        // 1. 生成贯穿生命周期的统一全局 TaskID
        final String taskId = java.util.UUID.randomUUID().toString();

        // 2. 校验和准备 Resume ID
        final String validResumeId;
        if (resume != null && !resume.isBlank() && registry.exists(resume)) {
            validResumeId = resume;
            log.info("[AgentTool] 准备接续之前的任务: resumeId={}", validResumeId);
        } else {
            validResumeId = null;
            if (resume != null && !resume.isBlank()) {
                log.warn("[AgentTool] 请求接续的 ID 不存在，将启动全新任务: {}", resume);
            }
        }

        // 3. 处理 Worktree 物理隔离沙盒
        Path worktreePath = null;
        if ("worktree".equalsIgnoreCase(isolation)) {
            try {
                Path basePath = Path.of(System.getProperty("user.dir"));

                // 净化 description 作为分支名后缀，防止包含空格或特殊字符导致 Git 报错
                String branchSuffix = (description != null && !description.isBlank())
                        ? description.replaceAll("[^a-zA-Z0-9-]", "-")
                        : "task";

                worktreePath = worktreeManager.createWorktree(taskId, branchSuffix, basePath);

                // 优雅降级：如果不是 Git 仓库或发生失败，WorktreeManager 会返回 basePath
                if (worktreePath.equals(basePath)) {
                    log.warn("[AgentTool] 沙盒降级：创建工作树失败或当前不是 Git 仓库，将在主目录下运行");
                    worktreePath = null;
                } else {
                    log.info("[AgentTool] 沙盒创建成功: taskId={}, path={}", taskId, worktreePath);
                }
            } catch (Exception e) {
                log.error("[AgentTool] 初始化 Worktree 沙盒时发生严重异常: {}", e.getMessage());
                worktreePath = null; // 降级为无隔离模式
            }
        }
        final Path finalWorktreePath = worktreePath;

        // 4. 构建底层的 Callable 任务（注入统一的 taskId）
        Callable<AgentResult> callable = () -> factory.execute(
                taskId,
                type,
                prompt,
                "agent-" + taskId, // 内部 conversationId
                userId,
                validResumeId,
                finalWorktreePath
        );

        // 5. 提交异步任务至线程池
        java.util.concurrent.Future<AgentResult> future = executor.submit(callable);

        // 6. 将任务注册至 Registry，主动传入统领全局的 taskId
        if (finalWorktreePath != null) {
            registry.registerWithWorktree(taskId, future, userId, sessionId, type.getValue(), description, prompt, finalWorktreePath.toString());
        } else {
            registry.register(taskId, future, userId, sessionId, type.getValue(), description, prompt);
        }

        // 7. 绑定业务状态链 (Resume Chain)
        if (validResumeId != null) {
            registry.linkResume(validResumeId, taskId);
        }

        // 8. 返回给调用方大模型
        if (isBackground) {
            return "\u2705 子 Agent 已在后台启动\n\n" +
                    "task_id: " + taskId + "\n" +
                    "agent_type: " + type.getValue() + "\n" +
                    "description: " + (description != null ? description : "N/A") + "\n" +
                    "status: running\n" +
                    "isolation: " + (finalWorktreePath != null ? "worktree" : "none") + "\n\n" +
                    "请使用 agentOutput 工具查询结果：\n" +
                    "agentOutput(taskId=\"" + taskId + "\", block=false)";
        } else {
            return "\u23f3 子 Agent 任务已提交并开始执行\n\n" +
                    "task_id: " + taskId + "\n" +
                    "agent_type: " + type.getValue() + "\n" +
                    "description: " + (description != null ? description : "N/A") + "\n" +
                    "status: running\n\n" +
                    "请使用 agentOutput 工具查询结果：\n" +
                    "agentOutput(taskId=\"" + taskId + "\", block=false)\n\n" +
                    "或直接阻塞等待其完成：\n" +
                    "agentOutput(taskId=\"" + taskId + "\", block=true, timeout=" + type.getDefaultTimeoutMs() + ")";
        }
    }

    @Tool(name = "AgentOutput", description = "Get output from a running or completed sub-agent task. " +
            "Parameters: task_id (required), block (default false), timeout (default 180000ms). " +
            "Status: running=still executing, completed=finished successfully, failed=error, not_found=ID not exist.")
    public String agentOutput(
            @ToolParam(description = "Agent task ID") String taskId,
            @ToolParam(description = "Block until completion", required = false) Boolean block,
            @ToolParam(description = "Timeout in milliseconds", required = false) Long timeout,
            ToolContext toolContext
    ) {
        String userId = extractUserId(toolContext);
        String sessionId = extractSessionId(toolContext);

        if (!registry.isOwner(taskId, userId, sessionId)) {
            return "\u274c 无权访问该任务或任务不存在";
        }

        AgentTaskRegistry.AgentTaskRecord record = registry.get(taskId);
        if (record == null) {
            return "\u274c 任务不存在或已过期清理: " + taskId;
        }

        boolean shouldBlock = block != null && block;
        long waitTimeout = resolveTimeout(timeout, record.agentType());

        try {
            if (shouldBlock) {
                // 阻塞等待
                log.info("[AgentTool] 阻塞等待任务完成: taskId={}, timeout={}ms", taskId, waitTimeout);
                AgentResult result = record.future().get(waitTimeout, TimeUnit.MILLISECONDS);
                cleanupTask(taskId); // 完成后清理现场
                return formatResult(result);
            } else {
                // 非阻塞查询
                if (record.future().isDone()) {
                    AgentResult result = record.future().get(0, TimeUnit.MILLISECONDS);
                    cleanupTask(taskId); // 完成后清理现场
                    return formatResult(result);
                } else {
                    return formatRunningStatus(taskId, record);
                }
            }
        } catch (java.util.concurrent.TimeoutException e) {
            return "\u23f3 任务执行超时 (timeout=" + waitTimeout + "ms)\n\n" +
                    formatRunningStatus(taskId, record);
        } catch (Exception e) {
            log.error("[AgentTool] 获取任务结果失败: taskId={}, error={}", taskId, e.getMessage(), e);
            cleanupTask(taskId); // 失败时依然强制清理现场
            return "\u274c 任务执行异常崩溃: " + e.getMessage();
        }
    }

    /**
     * 极致简化的现场统一清理逻辑
     */
    private void cleanupTask(String taskId) {
        // 由于所有模块已经统一共用同一个 taskId，清理操作变得绝对精准无误
        if (worktreeManager.hasWorktree(taskId)) {
            worktreeManager.cleanupWorktree(taskId, true);
        }
        registry.remove(taskId);
        log.debug("[AgentTool] 任务生命周期结束，环境与内存已清理: taskId={}", taskId);
    }

    private String formatRunningStatus(String taskId, AgentTaskRegistry.AgentTaskRecord record) {
        long elapsedMs = System.currentTimeMillis() - record.createdAt();
        long elapsedSec = elapsedMs / 1000;
        String elapsedStr = elapsedSec < 60
                ? elapsedSec + "s"
                : (elapsedSec / 60) + "m " + (elapsedSec % 60) + "s";

        return "\u23f3 任务仍在执行中...\n\n" +
                "task_id: " + taskId + "\n" +
                "agent_type: " + record.agentType() + "\n" +
                "description: " + record.description() + "\n" +
                "status: running\n" +
                "elapsed: " + elapsedStr + "\n\n" +
                "提示：使用 agentOutput(taskId=\"" + taskId + "\", block=true) 可阻塞等待任务结束";
    }

    /**
     * 解析超时配置：优先使用传入的超时，否则使用类型特定的默认值
     */
    private long resolveTimeout(Long requestedTimeout, String agentType) {
        if (requestedTimeout != null && requestedTimeout > 0) {
            return requestedTimeout;
        }
        return AgentType.fromValue(agentType).getDefaultTimeoutMs();
    }

    private String formatResult(AgentResult result) {
        if (result == null) {
            return "\u274c 任务结果为空";
        }

        StringBuilder sb = new StringBuilder();
        if ("completed".equals(result.status())) {
            sb.append("\u2705 子 Agent 任务完成\n\n");
            sb.append("task_id: ").append(result.taskId()).append("\n");
            sb.append("agent_type: ").append(result.agentType()).append("\n");
            sb.append("duration: ").append(result.durationMs()).append("ms\n");
            if (result.worktreePath() != null) {
                sb.append("worktree: 独立沙盒代码已生成，环境已自动清理。如有需要合并，请执行 Review 或 Merge。\n");
            }
            sb.append("\n--- 输出内容 ---\n");
            sb.append(result.output());
        } else if ("failed".equals(result.status())) {
            sb.append("\u274c 子 Agent 任务失败\n\n");
            sb.append("task_id: ").append(result.taskId()).append("\n");
            sb.append("agent_type: ").append(result.agentType()).append("\n");
            if (result.worktreePath() != null) {
                sb.append("worktree: 沙盒环境已强制清理并释放\n");
            }
            sb.append("\n--- 错误信息 ---\n");
            sb.append(result.error());
        } else {
            sb.append("\u274c 未知状态: ").append(result.status());
        }
        return sb.toString();
    }

    private String extractUserId(ToolContext toolContext) {
        if (toolContext == null) return "anonymous";
        Object userId = toolContext.getContext().get("userId");
        return userId != null ? userId.toString() : "anonymous";
    }

    private String extractSessionId(ToolContext toolContext) {
        if (toolContext == null) return "anonymous";
        Object sessionId = toolContext.getContext().get("sessionId");
        return sessionId != null ? sessionId.toString() : "anonymous";
    }
}