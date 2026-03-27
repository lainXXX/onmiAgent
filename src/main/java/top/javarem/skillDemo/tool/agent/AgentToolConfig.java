package top.javarem.skillDemo.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.javarem.skillDemo.tool.AgentTool;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 工具配置
 * 提供子 Agent 启动和结果查询能力
 */
@Slf4j
@Component
public class AgentToolConfig implements AgentTool {

    private final AgentTaskRegistry registry;

    private final SubAgentChatClientFactory factory;

    private final ExecutorService executor;

    private static final long DEFAULT_TIMEOUT_MS = 180000; // 3分钟

    public AgentToolConfig(AgentTaskRegistry registry, @Lazy SubAgentChatClientFactory factory, @Qualifier("agentExecutor") ExecutorService executor) {
        this.registry = registry;
        this.factory = factory;
        this.executor = executor;
    }

    /**
     * 启动子 Agent
     */
    @Tool(name = "Agent", description = """
        Launch a sub-agent to handle independent tasks in parallel.

        ## When to Use
        - Complex multi-step tasks that can run independently
        - Tasks requiring different expertise (explore vs plan)
        - Parallel processing of related subtasks

        ## Agent Types
        - explore: Deep exploration of code structure
        - plan: Create implementation plans
        - general: General purpose problem solving
        - code-reviewer: Code review and optimization suggestions

        ## Usage
        1. Call launchAgent with task description and agent type
        2. Receive a taskId
        3. Poll agentOutput with taskId to get results
        """)
    public String launchAgent(
            @ToolParam(description = "Agent type: explore/plan/general/code-reviewer") String agentType,
            @ToolParam(description = "Task description for the sub-agent") String task,
            ToolContext toolContext
    ) {
        String userId = extractUserId(toolContext);
        String sessionId = extractSessionId(toolContext);
        AgentType type = AgentType.fromValue(agentType);

        log.info("[AgentTool] 启动子Agent: type={}, task={}, userId={}", agentType, task, userId);

        // 先生成 taskId
        String taskId = java.util.UUID.randomUUID().toString();

        // 创建任务（taskId 已确定）
        Callable<AgentResult> callable = () -> factory.execute(taskId, type, task, "agent-" + taskId, userId);

        // 提交任务
        java.util.concurrent.Future<AgentResult> future = executor.submit(callable);

        // 注册任务
        registry.register(future, userId, sessionId, agentType);

        return """
                子 Agent 任务已启动

                task_id: %s
                agent_type: %s
                status: running

                使用 agentOutput 工具查询结果：
                agentOutput(taskId="%s", block=false)
                """.formatted(taskId, agentType, taskId);
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
        boolean shouldBlock = block != null && block;
        long waitTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT_MS;

        try {
            if (shouldBlock) {
                // 阻塞等待
                log.info("[AgentTool] 阻塞等待任务完成: taskId={}, timeout={}ms", taskId, waitTimeout);
                AgentResult result = record.future().get(waitTimeout, TimeUnit.MILLISECONDS);
                registry.remove(taskId);
                return formatResult(result);
            } else {
                // 非阻塞查询
                if (record.future().isDone()) {
                    AgentResult result = record.future().get(0, TimeUnit.MILLISECONDS);
                    registry.remove(taskId);
                    return formatResult(result);
                } else {
                    return "⏳ 任务仍在执行中...\n\ntask_id: " + taskId + "\nstatus: running";
                }
            }
        } catch (java.util.concurrent.TimeoutException e) {
            return "⏳ 任务执行超时 (timeout=" + waitTimeout + "ms)\n\ntask_id: " + taskId + "\nstatus: running";
        } catch (Exception e) {
            log.error("[AgentTool] 获取任务结果失败: taskId={}, error={}", taskId, e.getMessage(), e);
            registry.remove(taskId);
            return "❌ 任务执行失败: " + e.getMessage();
        }
    }

    private String formatResult(AgentResult result) {
        if (result == null) {
            return "❌ 任务结果为空";
        }

        return switch (result.status()) {
            case "completed" -> """
                    ✅ 子 Agent 任务完成

                    task_id: %s
                    agent_type: %s
                    duration: %dms

                    --- 输出内容 ---
                    %s
                    """.formatted(result.taskId(), result.agentType(), result.durationMs(), result.output());
            case "failed" -> """
                    ❌ 子 Agent 任务失败

                    task_id: %s
                    agent_type: %s

                    --- 错误信息 ---
                    %s
                    """.formatted(result.taskId(), result.agentType(), result.error());
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
