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
 * 支持的参数：
 * - description: 任务描述（3-5词），用于并行时识别
 * - prompt: 详细的执行指令
 * - subagent_type: 代理类型（explore/plan/verification/general/code-reviewer/claude-code-guide）
 * - run_in_background: 是否异步执行
 * - resume: 从之前的 agent ID 恢复
 * - isolation: worktree 隔离模式
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

    /**
     * 启动子 Agent
     *
     * @param description 任务描述（3-5词），用于并行识别
     * @param prompt 详细指令
     * @param subagentType 代理类型
     * @param runInBackground 是否异步执行
     * @param resume 从哪个 agent ID 恢复
     * @param isolation 隔离模式（worktree）
     */
    @Tool(name = "Agent", description = "启动一个新的代理，自主处理复杂、多步骤的任务。" +
            "代理工具启动专门的代理（子进程），自主处理复杂任务。每种代理类型都有其特定的功能和可用工具。" +
            "可用的代理类型: explore（代码探索 One-Shot）, plan（计划制定 One-Shot）, " +
            "verification（验收测试）, general（通用）, code-reviewer（代码审查）, claude-code-guide（Claude Code 指南）")
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

        log.info("[AgentTool] 启动子Agent: description={}, type={}, background={}, resume={}, isolation={}, userId={}",
                description, type.getValue(), isBackground, resume, isolation, userId);

        // 处理 resume 逻辑
        final String resumeFromTaskId;
        if (resume != null && !resume.isBlank() && registry.exists(resume)) {
            resumeFromTaskId = resume;
            log.info("[AgentTool] Resume from previous agent: {}", resume);
        } else {
            resumeFromTaskId = null;
            if (resume != null && !resume.isBlank()) {
                log.warn("[AgentTool] Resume ID not found, starting fresh: {}", resume);
            }
        }

        // 处理 worktree 隔离
        Path worktreePath = null;

        // 构建任务 - 先创建 callable（需要 taskId）
        // 注意：taskId 必须先从 registry 获取，以确保 registry 和 factory 使用同一个 ID
        Callable<AgentResult> callable;

        if ("worktree".equalsIgnoreCase(isolation)) {
            try {
                // 生成临时 taskId 用于创建 worktree 目录
                String tempTaskId = java.util.UUID.randomUUID().toString();
                Path basePath = Path.of(System.getProperty("user.dir"));
                worktreePath = worktreeManager.createWorktree(tempTaskId, description != null ? description : "task", basePath);
                log.info("[AgentTool] Worktree created: taskId={}, path={}", tempTaskId, worktreePath);

                final Path finalWorktreePath = worktreePath;
                final String taskIdForLambda = tempTaskId;
                callable = () -> factory.execute(
                        taskIdForLambda,
                        type,
                        prompt,
                        "agent-" + taskIdForLambda,
                        userId,
                        resumeFromTaskId,
                        finalWorktreePath
                );
            } catch (Exception e) {
                log.error("[AgentTool] Failed to create worktree: {}", e.getMessage());
                callable = () -> {
                    throw new IllegalStateException("Failed to create worktree: " + e.getMessage());
                };
            }
        } else {
            final String taskIdForLambda = java.util.UUID.randomUUID().toString();
            final Path finalWorktreePath = worktreePath;
            callable = () -> factory.execute(
                    taskIdForLambda,
                    type,
                    prompt,
                    "agent-" + taskIdForLambda,
                    userId,
                    resumeFromTaskId,
                    finalWorktreePath
            );
        }

        // 提交任务
        java.util.concurrent.Future<AgentResult> future = executor.submit(callable);

        // 注册任务并获取 registry 生成的 taskId（用于后续查询）
        final String registeredTaskId;
        if (worktreePath != null) {
            registeredTaskId = registry.registerWithWorktree(future, userId, sessionId, type.getValue(), description, prompt, worktreePath.toString());
        } else {
            registeredTaskId = registry.register(future, userId, sessionId, type.getValue(), description, prompt);
        }

        // 建立 resume 链
        if (resumeFromTaskId != null) {
            registry.linkResume(resumeFromTaskId, registeredTaskId);
        }

        if (isBackground) {
            // 异步模式：立即返回
            return "\u2705 子 Agent 已在后台启动\n\n" +
                    "task_id: " + registeredTaskId + "\n" +
                    "agent_type: " + type.getValue() + "\n" +
                    "description: " + (description != null ? description : "N/A") + "\n" +
                    "status: running\n" +
                    "isolation: " + (isolation != null ? isolation : "none") + "\n\n" +
                    "使用 agentOutput 工具查询结果：\n" +
                    "agentOutput(taskId=\"" + registeredTaskId + "\", block=false)";
        } else {
            // 同步模式：等待完成
            return "\u23f3 子 Agent 任务已提交\n\n" +
                    "task_id: " + registeredTaskId + "\n" +
                    "agent_type: " + type.getValue() + "\n" +
                    "description: " + (description != null ? description : "N/A") + "\n" +
                    "status: running\n\n" +
                    "使用 agentOutput 工具查询结果：\n" +
                    "agentOutput(taskId=\"" + registeredTaskId + "\", block=false)\n\n" +
                    "或直接阻塞等待：\n" +
                    "agentOutput(taskId=\"" + registeredTaskId + "\", block=true, timeout=180000)";
        }
    }

    /**
     * 获取子 Agent 输出
     */
    @Tool(name = "agentOutput", description = "Get output from a running or completed sub-agent task. " +
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

        // 权限校验
        if (!registry.isOwner(taskId, userId, sessionId)) {
            return "\u274c 无权访问该任务或任务不存在";
        }

        AgentTaskRegistry.AgentTaskRecord record = registry.get(taskId);
        if (record == null) {
            return "\u274c 任务不存在: " + taskId;
        }

        boolean shouldBlock = block != null && block;
        long waitTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT_MS;

        try {
            if (shouldBlock) {
                // 阻塞等待
                log.info("[AgentTool] 阻塞等待任务完成: taskId={}, timeout={}ms", taskId, waitTimeout);
                AgentResult result = record.future().get(waitTimeout, TimeUnit.MILLISECONDS);

                // 清理 worktree（只删物理目录，保留分支以便后续 merge）
                String worktreePath = registry.getWorktreePath(taskId);
                if (worktreePath != null) {
                    worktreeManager.cleanupWorktree(taskId, false);  // false = 保留分支
                }

                registry.remove(taskId);
                return formatResult(result);
            } else {
                // 非阻塞查询
                if (record.future().isDone()) {
                    AgentResult result = record.future().get(0, TimeUnit.MILLISECONDS);

                    // 清理 worktree（只删物理目录，保留分支）
                    String worktreePath = registry.getWorktreePath(taskId);
                    if (worktreePath != null) {
                        worktreeManager.cleanupWorktree(taskId, false);  // false = 保留分支
                    }

                    registry.remove(taskId);
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

            // 清理 worktree
            String worktreePath = registry.getWorktreePath(taskId);
            if (worktreePath != null) {
                worktreeManager.cleanupWorktree(taskId, false);
            }

            registry.remove(taskId);
            return "\u274c 任务执行失败: " + e.getMessage();
        }
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
                "使用 agentOutput(taskId=\"" + taskId + "\", block=true) 阻塞等待完成";
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
                sb.append("worktree: ").append(result.worktreePath()).append("\n");
            }
            sb.append("\n--- 输出内容 ---\n");
            sb.append(result.output());
        } else if ("failed".equals(result.status())) {
            sb.append("\u274c 子 Agent 任务失败\n\n");
            sb.append("task_id: ").append(result.taskId()).append("\n");
            sb.append("agent_type: ").append(result.agentType()).append("\n");
            if (result.worktreePath() != null) {
                sb.append("worktree: ").append(result.worktreePath()).append("\n");
            }
            sb.append("\n--- 错误信息 ---\n");
            sb.append(result.error());
        } else {
            sb.append("\u274c 未知状态: ").append(result.status());
        }
        return sb.toString();
    }

    private String extractUserId(ToolContext toolContext) {
        if (toolContext == null) {
            return "anonymous";
        }
        Object userId = toolContext.getContext().get("userId");
        return userId != null ? userId.toString() : "anonymous";
    }

    private String extractSessionId(ToolContext toolContext) {
        if (toolContext == null) {
            return "anonymous";
        }
        Object sessionId = toolContext.getContext().get("sessionId");
        return sessionId != null ? sessionId.toString() : "anonymous";
    }
}
