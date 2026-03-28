package top.javarem.onmi.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.javarem.onmi.tool.AgentTool;

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
 * - subagent_type: 代理类型（explore/plan/general/code-reviewer/claude-code-guide）
 * - run_in_background: 是否异步执行
 * - resume: 从之前的 agent ID 恢复
 * - isolation: worktree 隔离模式
 */
@Slf4j
@Component
public class AgentToolConfig implements AgentTool {

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
    @Tool(name = "Agent", description = """
        Launch a new agent to handle complex, multi-step tasks autonomously.

        The Agent tool launches specialized agents (subprocesses) that autonomously handle complex tasks.
        Each agent type has specific capabilities and tools available to it.

        Available agent types and the tools they have access to:
        - explore: Fast agent for exploring codebases. Uses Glob, Grep, Read. (Tools: All except Edit, Write, Bash)
        - plan: Software architect agent for designing implementation plans. (Tools: Read, Glob, Grep, Write, Edit)
        - general: General-purpose agent for researching complex questions and executing multi-step tasks. (Tools: *)
        - code-reviewer: Agent for reviewing code against plans and standards. (Tools: Read, Glob, Grep)
        - claude-code-guide: Agent for answering questions about Claude Code CLI, SDK, and API. (Tools: WebSearch, WebFetch, Read)

        ## Parameters
        - description: Short task name (3-5 words) for parallel identification
        - prompt: Detailed instructions for the sub-agent
        - subagent_type: Agent type selection (default: general)
        - run_in_background: Set true for async execution (default: false)
        - resume: Agent ID to resume from (for continuing previous tasks)
        - isolation: Set "worktree" for git worktree isolation

        ## Usage
        1. Call launchAgent with task description and agent type
        2. Receive a taskId
        3. Poll agentOutput with taskId to get results

        When to Use:
        - Complex multi-step tasks that can run independently
        - Tasks requiring different expertise (explore vs plan)
        - Parallel processing of related subtasks

        When NOT to Use:
        - Simple file reads (use Read tool directly)
        - Simple class definitions (use Glob tool)
        - Searching in 2-3 files (use Grep tool directly)
        """)
    public String launchAgent(
            @ToolParam(description = "Task name (3-5 words) for parallel identification") String description,
            @ToolParam(description = "Detailed instructions for the sub-agent") String prompt,
            @ToolParam(description = "Agent type: explore/plan/general/code-reviewer/claude-code-guide", required = false) String subagentType,
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
        final String currentTaskId = java.util.UUID.randomUUID().toString();

        if ("worktree".equalsIgnoreCase(isolation)) {
            try {
                Path basePath = Path.of(System.getProperty("user.dir"));
                worktreePath = worktreeManager.createWorktree(currentTaskId, description != null ? description : "task", basePath);
                log.info("[AgentTool] Worktree created: taskId={}, path={}", currentTaskId, worktreePath);
            } catch (Exception e) {
                log.error("[AgentTool] Failed to create worktree: {}", e.getMessage());
            }
        }

        // 构建任务 - 使用 final 引用
        final Path finalWorktreePath = worktreePath;
        final String taskIdForLambda = currentTaskId;
        Callable<AgentResult> callable = () -> factory.execute(
                taskIdForLambda,
                type,
                prompt,
                "agent-" + taskIdForLambda,
                userId,
                resumeFromTaskId,
                finalWorktreePath
        );

        // 提交任务
        java.util.concurrent.Future<AgentResult> future = executor.submit(callable);

        // 注册任务
        if (worktreePath != null) {
            registry.registerWithWorktree(future, userId, sessionId, type.getValue(), description, prompt, worktreePath.toString());
        } else {
            registry.register(future, userId, sessionId, type.getValue(), description, prompt);
        }

        // 建立 resume 链
        if (resumeFromTaskId != null) {
            registry.linkResume(resumeFromTaskId, taskIdForLambda);
        }

        if (isBackground) {
            // 异步模式：立即返回
            return """
                ✅ 子 Agent 已在后台启动

                task_id: %s
                agent_type: %s
                description: %s
                status: running
                isolation: %s

                使用 agentOutput 工具查询结果：
                agentOutput(taskId="%s", block=false)
                """.formatted(currentTaskId, type.getValue(), description != null ? description : "N/A",
                    isolation != null ? isolation : "none", currentTaskId);
        } else {
            // 同步模式：等待完成
            return """
                ⏳ 子 Agent 任务已提交

                task_id: %s
                agent_type: %s
                description: %s
                status: running

                使用 agentOutput 工具查询结果：
                agentOutput(taskId="%s", block=false)

                或直接阻塞等待：
                agentOutput(taskId="%s", block=true, timeout=180000)
                """.formatted(currentTaskId, type.getValue(), description != null ? description : "N/A",
                    currentTaskId, currentTaskId);
        }
    }

    /**
     * 获取子 Agent 输出
     */
    @Tool(name = "agentOutput", description = """
        Get output from a running or completed sub-agent task.

        ## Parameters
        - task_id: The agent task ID returned by launchAgent
        - block: Wait for completion (default: false)
        - timeout: Max wait time in ms (default: 180000 = 3 minutes)

        ## Status Values
        - running: Task is still executing
        - completed: Task finished successfully
        - failed: Task encountered an error
        - not_found: Task ID does not exist

        ## Resume
        To continue a completed task, use the returned task_id as the resume parameter in launchAgent.
        """)
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
            return "❌ 无权访问该任务或任务不存在";
        }

        AgentTaskRegistry.AgentTaskRecord record = registry.get(taskId);
        if (record == null) {
            return "❌ 任务不存在: " + taskId;
        }

        boolean shouldBlock = block != null && block;
        long waitTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT_MS;

        try {
            if (shouldBlock) {
                // 阻塞等待
                log.info("[AgentTool] 阻塞等待任务完成: taskId={}, timeout={}ms", taskId, waitTimeout);
                AgentResult result = record.future().get(waitTimeout, TimeUnit.MILLISECONDS);

                // 清理 worktree
                String worktreePath = registry.getWorktreePath(taskId);
                if (worktreePath != null) {
                    worktreeManager.cleanupWorktree(taskId, true);
                }

                registry.remove(taskId);
                return formatResult(result);
            } else {
                // 非阻塞查询
                if (record.future().isDone()) {
                    AgentResult result = record.future().get(0, TimeUnit.MILLISECONDS);

                    // 清理 worktree
                    String worktreePath = registry.getWorktreePath(taskId);
                    if (worktreePath != null) {
                        worktreeManager.cleanupWorktree(taskId, true);
                    }

                    registry.remove(taskId);
                    return formatResult(result);
                } else {
                    return formatRunningStatus(taskId, record);
                }
            }
        } catch (java.util.concurrent.TimeoutException e) {
            return "⏳ 任务执行超时 (timeout=" + waitTimeout + "ms)\n\n" +
                    formatRunningStatus(taskId, record);
        } catch (Exception e) {
            log.error("[AgentTool] 获取任务结果失败: taskId={}, error={}", taskId, e.getMessage(), e);

            // 清理 worktree
            String worktreePath = registry.getWorktreePath(taskId);
            if (worktreePath != null) {
                worktreeManager.cleanupWorktree(taskId, false);
            }

            registry.remove(taskId);
            return "❌ 任务执行失败: " + e.getMessage();
        }
    }

    private String formatRunningStatus(String taskId, AgentTaskRegistry.AgentTaskRecord record) {
        return """
                ⏳ 任务仍在执行中...

                task_id: %s
                agent_type: %s
                description: %s
                status: running
                """.formatted(taskId, record.agentType(), record.description());
    }

    private String formatResult(AgentResult result) {
        if (result == null) {
            return "❌ 任务结果为空";
        }

        return switch (result.status()) {
            case "completed" -> {
                StringBuilder sb = new StringBuilder();
                sb.append("✅ 子 Agent 任务完成\n\n");
                sb.append("task_id: ").append(result.taskId()).append("\n");
                sb.append("agent_type: ").append(result.agentType()).append("\n");
                sb.append("duration: ").append(result.durationMs()).append("ms\n");
                if (result.worktreePath() != null) {
                    sb.append("worktree: ").append(result.worktreePath()).append("\n");
                }
                sb.append("\n--- 输出内容 ---\n");
                sb.append(result.output());
                yield sb.toString();
            }
            case "failed" -> {
                StringBuilder sb = new StringBuilder();
                sb.append("❌ 子 Agent 任务失败\n\n");
                sb.append("task_id: ").append(result.taskId()).append("\n");
                sb.append("agent_type: ").append(result.agentType()).append("\n");
                if (result.worktreePath() != null) {
                    sb.append("worktree: ").append(result.worktreePath()).append("\n");
                }
                sb.append("\n--- 错误信息 ---\n");
                sb.append(result.error());
                yield sb.toString();
            }
            default -> "❌ 未知状态: " + result.status();
        };
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
