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
            启动一个新的代理，自主处理复杂、多步骤的任务。
                    
            代理工具启动专门的代理（子进程），自主处理复杂任务。
            每种代理类型都有其特定的功能和可用工具。
                    
            可用的代理类型及其可用工具：
            - explore：用于探索代码库的快速代理。使用 Glob、Grep、Read。（工具：除编辑、写作、抨击外全部）
            - plan：用于设计实施计划的软件架构代理。（工具：读取、凝聚、Grep、写入、编辑）
            - 通用：用于研究复杂问题和执行多步骤任务的通用代理。（工具：*）
            - 代码审查员：用于根据计划和标准审查代码的代理。（工具：Read、Glob、Grep）
            - claude-code-guide：用于回答关于 Claude Code CLI、SDK 和 API 问题的代理。（工具：WebSearch、WebFetch、Read）
                    
            ## 参数
            - 描述：简短的任务名称（3-5 个单词），用于并行识别
            - 提示词：对子代理的详细说明
            - subagent_type：代理类型选择（默认：通用）
            - run_in_background：设为真以示异步执行（默认：false）
            - 恢复：用于继续之前任务的代理 ID。
            - 隔离：将“worktree”设置为 git 工作树隔离
                    
            ## 用途
            1. 调用带有任务描述和代理类型的 launchAgent
            2. 接收任务 ID
            3. 轮询 agentOutput（带有 taskId）以获取结果
                    
            使用时间：
            - 复杂且多步骤的任务，可以独立运行
            - 需要不同专业知识的任务（探索与规划）
            - 相关子任务的并行处理
                    
            何时不宜使用：
            - 简单文件读取（直接使用读取工具）
            - 简单类定义（使用 Glob 工具）
            - 在 2-3 个文件中搜索（直接使用 Grep 工具）
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
