package top.javarem.omni.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.exception.TaskException;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.model.task.TaskEntity;
import top.javarem.omni.service.task.TaskService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 内置任务管理工具
 * 提供任务 CRUD + 后台任务管理功能
 */
@Slf4j
@Component
public class TaskToolConfig implements AgentTool {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final Set<String> VALID_STATUSES = Set.of("pending", "in_progress", "completed");
    private static final Set<String> VALID_PRIORITIES = Set.of("high", "medium", "low");

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    // ==================== 后台任务注册表 ====================
    private record TaskRecord(Future<?> future, String userId, String sessionId, String description, long createdAt) {}
    private final Map<String, TaskRecord> taskRegistry = new ConcurrentHashMap<>();

    public TaskToolConfig(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    // ==================== CreateTask ====================

    @Tool(name = "TaskCreate", description = """
            Create a structured task for your current coding session. Helps track progress, organize complex tasks, and demonstrate thoroughness.

            ## When to Use
            - Complex multi-step tasks (3+ distinct steps)
            - Non-trivial tasks requiring careful planning
            - After receiving new instructions
            - When starting work on a task

            ## When NOT to Use
            - Single, straightforward task
            - Task completable in less than 3 trivial steps
            - Purely conversational or informational

            ## Task Fields
            - subject: Brief, actionable title (e.g., "Fix authentication bug")
            - description: Detailed requirements and acceptance criteria
            - priority: high/medium/low
            - dueDate: ISO-8601 format like 2026-03-31
            - dependencies: List of task IDs this task depends on
            """)
    public String taskCreate(
            @ToolParam(description = "任务标题") String subject,
            @ToolParam(description = "任务描述", required = false) String description,
            @ToolParam(description = "优先级: high/medium/low", required = false) String priority,
            @ToolParam(description = "截止日期，ISO-8601格式如 2026-03-31", required = false) String dueDate,
            @ToolParam(description = "依赖任务ID列表", required = false) List<String> dependencies,
            ToolContext toolContext
            ) {

        try {
            Map<String, Object> context = toolContext.getContext();
            String userId = context.get(AdvisorContextConstants.USER_ID).toString();
            String sessionId = context.get(AdvisorContextConstants.SESSION_ID).toString();
            if (subject == null || subject.isBlank()) {
                log.warn("[TaskCreate] 任务标题为空，userId={}, sessionId={}", userId, sessionId);
                return buildError("任务标题不能为空");
            }
            if (priority != null && !VALID_PRIORITIES.contains(priority)) {
                log.warn("[TaskCreate] 无效优先级={}, userId={}, sessionId={}", priority, userId, sessionId);
                return buildError("无效的优先级: " + priority + "，可选值: high/medium/low");
            }

            List<UUID> deps = parseUUIDs(dependencies);
            LocalDateTime due = parseDateTime(dueDate);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("agent_id", "system");
            metadata.put("last_action_source", "ai");
            metadata.put("created_at", LocalDateTime.now().toString());

            log.info("[TaskCreate] subject={}, priority={}, dueDate={}, dependencies={}, userId={}, sessionId={}",
                    subject, priority, dueDate, dependencies, userId, sessionId);

            TaskEntity task = taskService.create(userId, sessionId, subject, description, priority, due, deps, metadata);

            log.info("[TaskCreate] 任务创建成功 id={}, subject={}", task.id(), task.subject());
            return buildSuccessResponse("任务创建成功", task);
        } catch (TaskException e) {
            return buildError(e);
        } catch (IllegalArgumentException e) {
            return buildError("参数格式错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("创建任务失败", e);
            return buildError("任务创建失败: " + e.getMessage());
        }
    }

    // ==================== TaskList ====================

    @Tool(name = "TaskList", description = """
            List all tasks in the task list.

            ## When to Use
            - See available tasks (pending, no owner, not blocked)
            - Check overall project progress
            - Find blocked tasks needing dependencies resolved
            - After completing a task, find next work

            ## Output
            Returns summary of each task: id, subject, status, owner, blockedBy
            """)
    public String taskList(
            @ToolParam(description = "任务状态: pending/in_progress/completed", required = false) String status,
            @ToolParam(description = "页码，默认1", required = false) Integer page,
            @ToolParam(description = "每页数量，默认20", required = false) Integer pageSize,
            @ToolParam(description = "返回格式: markdown/json", required = false) String format,
            ToolContext toolContext) {

        try {
            Map<String, Object> context = toolContext.getContext();
            String userId = context.get(AdvisorContextConstants.USER_ID).toString();
            String sessionId = context.get(AdvisorContextConstants.SESSION_ID).toString();
            if (status != null && !VALID_STATUSES.contains(status)) {
                log.warn("[TaskList] 无效状态={}, userId={}, sessionId={}", status, userId, sessionId);
                return buildError("无效的状态: " + status + "，可选值: pending/in_progress/completed");
            }

            int p = page != null && page > 0 ? page : 1;
            int size = pageSize != null && pageSize > 0 ? pageSize : 20;

            log.info("[TaskList] status={}, page={}, pageSize={}, userId={}, sessionId={}",
                    status, p, size, userId, sessionId);

            List<TaskEntity> tasks = taskService.list(userId, sessionId, status, p, size);
            int total = taskService.count(userId, sessionId, status);

            log.info("[TaskList] total={}, returned={}", total, tasks.size());

            if ("json".equalsIgnoreCase(format)) {
                return toJson(Map.of("tasks", tasks, "page", p, "pageSize", size, "total", total));
            }

            return buildMarkdownList(tasks, p, size, total);
        } catch (Exception e) {
            log.error("查询任务列表失败", e);
            return buildError("查询失败: " + e.getMessage());
        }
    }

    // ==================== TaskGet ====================

    @Tool(name = "TaskGet", description = """
            Get task details by ID.

            ## When to Use
            - Get full description and context before starting work
            - Understand task dependencies
            - After being assigned a task

            ## Output
            Returns: subject, description, status, blocks, blockedBy
            """)
    public String taskGet(@ToolParam(description = "任务ID") String taskId,
                          ToolContext toolContext) {

        try {
            Map<String, Object> context = toolContext.getContext();
            String userId = context.get(AdvisorContextConstants.USER_ID).toString();
            String sessionId = context.get(AdvisorContextConstants.SESSION_ID).toString();
            UUID id = parseUUID(taskId);
            log.info("[TaskGet] taskId={}, userId={}, sessionId={}", taskId, userId, sessionId);

            TaskEntity task = taskService.get(id, userId, sessionId);

            log.info("[TaskGet] id={}, subject={}, status={}", task.id(), task.subject(), task.status());
            return buildSuccessResponse("任务详情", task);
        } catch (TaskException e) {
            return buildError(e);
        } catch (IllegalArgumentException e) {
            return buildError("无效的任务ID格式: " + taskId);
        } catch (Exception e) {
            log.error("获取任务详情失败", e);
            return buildError("获取失败: " + e.getMessage());
        }
    }

    // ==================== TaskUpdate ====================

    @Tool(name = "TaskUpdate", description = """
            Update a task in the task list.

            ## When to Use
            - Mark task as in_progress when starting work
            - Mark task as completed when finished
            - Update task details or dependencies
            - Delete a task (status: deleted)

            ## Status Workflow
            pending → in_progress → completed
            Use deleted to permanently remove a task

            ## Examples
            Mark in progress: {"taskId": "xxx", "status": "in_progress"}
            Mark completed: {"taskId": "xxx", "status": "completed"}
            Set dependencies: {"taskId": "2", "addBlockedBy": ["1"]}
            """)
    public String taskUpdate(
            @ToolParam(description = "任务ID") String taskId,
            @ToolParam(description = "任务标题", required = false) String subject,
            @ToolParam(description = "任务描述", required = false) String description,
            @ToolParam(description = "状态: pending/in_progress/completed", required = false) String status,
            @ToolParam(description = "优先级: high/medium/low", required = false) String priority,
            @ToolParam(description = "截止日期，ISO-8601格式", required = false) String dueDate,
            @ToolParam(description = "metadata Map，会增量合并", required = false) Map<String, Object> metadata,
            @ToolParam(description = "依赖任务ID列表", required = false) List<String> dependencies,
            ToolContext toolContext) {

        try {
            Map<String, Object> context = toolContext.getContext();
            String userId = context.get(AdvisorContextConstants.USER_ID).toString();
            String sessionId = context.get(AdvisorContextConstants.SESSION_ID).toString();
            if (status != null && !VALID_STATUSES.contains(status)) {
                log.warn("[TaskUpdate] 无效状态={}, taskId={}, userId={}, sessionId={}", status, taskId, userId, sessionId);
                return buildError("无效的状态: " + status + "，可选值: pending/in_progress/completed");
            }
            if (priority != null && !VALID_PRIORITIES.contains(priority)) {
                log.warn("[TaskUpdate] 无效优先级={}, taskId={}, userId={}, sessionId={}", priority, taskId, userId, sessionId);
                return buildError("无效的优先级: " + priority + "，可选值: high/medium/low");
            }

            UUID id = parseUUID(taskId);
            List<UUID> deps = parseUUIDs(dependencies);
            LocalDateTime due = parseDateTime(dueDate);

            log.info("[TaskUpdate] taskId={}, subject={}, status={}, priority={}, userId={}, sessionId={}",
                    taskId, subject, status, priority, userId, sessionId);

            TaskEntity task = taskService.update(id, userId, sessionId, subject, description, status, priority, due, metadata, deps);

            log.info("[TaskUpdate] id={}, newStatus={}", task.id(), task.status());
            return buildSuccessResponse("任务更新成功", task);
        } catch (TaskException e) {
            return buildError(e);
        } catch (IllegalArgumentException e) {
            return buildError("参数格式错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("更新任务失败", e);
            return buildError("更新失败: " + e.getMessage());
        }
    }

    // ==================== TaskOutput ====================

    @Tool(name = "TaskOutput", description = """
            Get output from a running or completed background task.

            ## When to Use
            - Check status of a background task
            - Get result of a completed task
            - Wait for a long-running task to finish

            ## Parameters
            - task_id: The background task ID
            - block: Wait for completion (default: false)
            - timeout: Max wait time in ms (default: 30000)
            """)
    public String taskOutput(
            @ToolParam(description = "任务ID") String taskId,
            @ToolParam(description = "是否阻塞等待完成", required = false) Boolean block,
            @ToolParam(description = "等待超时（毫秒）", required = false) Long timeout,
            ToolContext toolContext) {

        Map<String, Object> context = toolContext.getContext();
        String userId = context.get(AdvisorContextConstants.USER_ID).toString();
        String sessionId = context.get(AdvisorContextConstants.SESSION_ID).toString();
        log.info("[TaskOutput] taskId={}, block={}, timeout={}, userId={}, sessionId={}",
                taskId, block, timeout, userId, sessionId);

        if (taskId == null || taskId.trim().isEmpty()) {
            log.warn("[TaskOutput] taskId为空");
            return buildError("任务ID不能为空", "请提供有效的任务ID");
        }

        TaskRecord record = taskRegistry.get(taskId);
        if (record == null) {
            log.warn("[TaskOutput] 任务不存在: taskId={}", taskId);
            return buildError("任务不存在或已过期", "请检查任务ID是否正确");
        }

        if (!record.userId().equals(userId) || !record.sessionId().equals(sessionId)) {
            log.warn("[TaskOutput] 无权访问: taskId={}, 请求用户={}/{}, 任务所有者={}/{}",
                    taskId, userId, sessionId, record.userId(), record.sessionId());
            return buildError("无权访问此任务", "任务属于其他用户或会话");
        }

        boolean shouldBlock = Boolean.TRUE.equals(block);
        long waitTimeout = timeout != null && timeout > 0 ? timeout : DEFAULT_TIMEOUT_MS;
        Future<?> future = record.future();

        if (shouldBlock) {
            try {
                Object result = future.get(waitTimeout, TimeUnit.MILLISECONDS);
                taskRegistry.remove(taskId);
                String output = result != null ? result.toString() : "任务完成";
                log.info("[TaskOutput] 任务完成: taskId={}, resultLength={}", taskId, output.length());
                return "✅ 任务完成\n\n" + output;
            } catch (TimeoutException e) {
                log.info("[TaskOutput] 任务超时: taskId={}", taskId);
                return "⏳ 任务仍在执行中...";
            } catch (ExecutionException e) {
                taskRegistry.remove(taskId);
                log.error("[TaskOutput] 任务执行失败: taskId={}, error={}", taskId, e.getMessage());
                return buildError("任务执行失败: " + e.getMessage(), "请检查任务是否正确");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[TaskOutput] 任务被中断: taskId={}", taskId);
                return buildError("任务被中断", "请重试");
            }
        } else {
            if (future.isDone()) {
                taskRegistry.remove(taskId);
                try {
                    Object result = future.get();
                    String output = result != null ? result.toString() : "任务完成";
                    log.info("[TaskOutput] 任务完成(非阻塞): taskId={}", taskId);
                    return "✅ 任务完成\n\n" + output;
                } catch (ExecutionException e) {
                    taskRegistry.remove(taskId);
                    log.error("[TaskOutput] 任务执行失败(非阻塞): taskId={}, error={}", taskId, e.getMessage());
                    return buildError("任务执行失败: " + e.getMessage(), "请检查任务是否正确");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return buildError("任务被中断", "请重试");
                }
            } else {
                log.info("[TaskOutput] 任务进行中: taskId={}", taskId);
                return "⏳ 任务仍在执行中，请稍后查询";
            }
        }
    }

    // ==================== TaskStop ====================

    @Tool(name = "TaskStop", description = """
            Stop a running background task by its ID.

            ## When to Use
            - Terminate a long-running task no longer needed
            - Cancel a task that appears stuck
            """)
    public String taskStop(
            @ToolParam(description = "任务ID") String taskId,
            ToolContext toolContext) {

        Map<String, Object> context = toolContext.getContext();
        String userId = context.get(AdvisorContextConstants.USER_ID).toString();
        String sessionId = context.get(AdvisorContextConstants.SESSION_ID).toString();
        log.info("[TaskStop] taskId={}, userId={}, sessionId={}", taskId, userId, sessionId);

        if (taskId == null || taskId.trim().isEmpty()) {
            log.warn("[TaskStop] taskId为空");
            return buildError("任务ID不能为空", "请提供有效的任务ID");
        }

        TaskRecord record = taskRegistry.get(taskId);
        if (record == null) {
            log.warn("[TaskStop] 任务不存在: taskId={}", taskId);
            return buildError("任务不存在或已过期", "请检查任务ID是否正确");
        }

        if (!record.userId().equals(userId) || !record.sessionId().equals(sessionId)) {
            log.warn("[TaskStop] 无权停止: taskId={}, 请求用户={}/{}, 任务所有者={}/{}",
                    taskId, userId, sessionId, record.userId(), record.sessionId());
            return buildError("无权停止此任务", "任务属于其他用户或会话");
        }

        Future<?> future = record.future();
        boolean cancelled = future.cancel(true);
        taskRegistry.remove(taskId);

        if (cancelled) {
            log.info("[TaskStop] 任务已停止: taskId={}", taskId);
            return "✅ 任务 " + taskId + " 已停止";
        } else {
            log.warn("[TaskStop] 任务停止失败(可能已完成): taskId={}", taskId);
            return "⚠️ 任务可能已完成，无法停止";
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 注册后台任务（供其他工具如 BashToolConfig 调用）
     */
    public void registerTask(String taskId, Future<?> future, String userId, String sessionId, String description) {
        log.info("[registerTask] taskId={}, userId={}, sessionId={}, description={}", taskId, userId, sessionId, description);
        taskRegistry.put(taskId, new TaskRecord(future, userId, sessionId, description, System.currentTimeMillis()));
    }

    /**
     * 停止后台任务（供其他工具调用）
     */
    public boolean stopTask(String taskId) {
        TaskRecord record = taskRegistry.remove(taskId);
        return record != null && record.future().cancel(true);
    }

    private UUID parseUUID(String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        return UUID.fromString(uuidStr);
    }

    private List<UUID> parseUUIDs(List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return Collections.emptyList();
        }
        return uuids.stream().map(UUID::fromString).toList();
    }

    private LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr);
        } catch (Exception e) {
            try {
                return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            } catch (Exception ex) {
                throw new IllegalArgumentException("无效的日期格式: " + dateStr + "，请使用 ISO-8601 格式");
            }
        }
    }

    private String buildSuccessResponse(String message, TaskEntity task) {
        return "✅ " + message + "\n\n" +
                "─────────────────────────────────────────\n" +
                formatTask(task);
    }

    private String formatTask(TaskEntity task) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 任务信息\n\n");
        sb.append("- **ID**: `").append(task.id()).append("`\n");
        sb.append("- **标题**: ").append(task.subject()).append("\n");
        sb.append("- **描述**: ").append(task.description() != null && !task.description().isEmpty() ? task.description() : "(无)").append("\n");
        sb.append("- **状态**: ").append(formatStatus(task.status())).append("\n");
        sb.append("- **优先级**: ").append(formatPriority(task.priority())).append("\n");
        if (task.dueDate() != null) {
            sb.append("- **截止日期**: ").append(task.dueDate()).append("\n");
        }
        if (!task.dependencies().isEmpty()) {
            sb.append("- **依赖**: ").append(task.dependencies().size()).append(" 个任务\n");
        }
        sb.append("- **创建时间**: ").append(task.createdAt()).append("\n");
        sb.append("- **更新时间**: ").append(task.updatedAt()).append("\n");
        return sb.toString();
    }

    private String formatStatus(String status) {
        return switch (status) {
            case "pending" -> "📋 待处理";
            case "in_progress" -> "🔄 进行中";
            case "completed" -> "✅ 已完成";
            default -> status;
        };
    }

    private String formatPriority(String priority) {
        return switch (priority) {
            case "high" -> "🔴 高";
            case "medium" -> "🟡 中";
            case "low" -> "🟢 低";
            default -> priority != null ? priority : "中";
        };
    }

    private String buildMarkdownList(List<TaskEntity> tasks, int page, int pageSize, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 任务列表\n\n");
        sb.append("─────────────────────────────────────────\n");

        if (tasks.isEmpty()) {
            sb.append("\n_暂无任务_");
            return sb.toString();
        }

        sb.append("\n**共 ").append(total).append(" 条任务** (第 ").append(page)
          .append(" 页，每页 ").append(pageSize).append(" 条)\n\n");

        for (TaskEntity task : tasks) {
            sb.append("### ").append(formatStatus(task.status())).append(" ")
              .append(task.subject()).append("\n\n");
            sb.append("```\n");
            sb.append("ID: ").append(task.id()).append("\n");
            sb.append("优先级: ").append(formatPriority(task.priority())).append("\n");
            if (task.dueDate() != null) {
                sb.append("截止: ").append(task.dueDate().toLocalDate()).append("\n");
            }
            sb.append("```\n\n");
        }

        return sb.toString();
    }

    private String buildError(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }

    private String buildError(String message) {
        return "❌ " + message;
    }

    private String buildError(TaskException e) {
        StringBuilder sb = new StringBuilder();
        sb.append("❌ ").append(e.getMessage());
        if (e.getBlockedBy() != null && !e.getBlockedBy().isEmpty()) {
            sb.append("\n\n**被以下任务阻塞**:\n");
            for (UUID id : e.getBlockedBy()) {
                sb.append("- `").append(id).append("`\n");
            }
        }
        return sb.toString();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
