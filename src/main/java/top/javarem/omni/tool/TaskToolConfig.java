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

    @Override
    public String getName() {
        return "Task";
    }

    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final Set<String> VALID_STATUSES = Set.of("pending", "in_progress", "completed");

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
            ## 批量调用规则（必须遵守）
            遇到多个任务时，**必须为每个任务单独调用一次此工具**：
            - ✓ 用户列出多项："帮我完成 A、B、C" → 每个任务调用 1 次
            - ✓ 用户说"需要做这几件事" → 每个任务调用 1 次
            - ✓ 任务可拆分为多个独立子目标 → 每个子目标调用 1 次
            - ✗ 不要尝试在单次调用中创建"多个任务"

            使用此工具为当前编码会话创建结构化任务列表。这有助于跟踪进度、组织复杂任务，并向用户展示工作的彻底性。同时帮助用户了解任务进度及请求的整体完成情况。

            ## 何时使用此工具
                        
            在以下场景中主动使用此工具：
                        
            - **复杂的多步任务**：当一个任务需要 3 个或更多独特步骤或动作时。
            - **非琐碎且复杂的任务**：需要仔细规划或多次操作的任务。
            - **计划模式（Plan mode）**：在使用计划模式时，创建任务列表以跟踪工作。
            - **用户明确要求**：当用户直接要求你使用待办事项列表（todo list）时。
            - **用户提供多个任务**：当用户提供了一系列需要完成的事情（编号或逗号分隔）时。
            - **接收到新指令后**：立即将用户需求捕获为任务。
            - **开始执行任务时**：在开始工作**之前**，将任务标记为 `in_progress`。
            - **完成任务后**：将任务标记为 `completed`，并添加在实现过程中发现的任何新后续任务。
                        
            ## 何时不要使用此工具
                        
            在以下情况下跳过使用此工具：
                        
            - 只有单一、简单的任务。
            - 任务过于琐碎，跟踪它无法带来组织上的收益。
            - 任务可以在少于 3 个简单步骤内完成。
            - 任务纯粹是对话性或信息性的。
                        
            **注意：** 如果只有一个琐碎的任务，请不要使用此工具。在这种情况下，直接完成任务效果更好。
                        
            ## 任务字段详解
                        
            - **subject (主题)**：简短、可操作的标题，使用祈使句（例如："修复登录流程中的身份验证 Bug"）。
            - **description (描述)**：详细说明需要完成的工作，包括上下文和验收标准。
            - **activeForm (进行态，可选)**：当任务处于 `in_progress` 状态时，加载动画中显示的现在进行时形式（例如："正在修复身份验证 Bug"）。如果省略，加载动画将显示 `subject`。
                        
            所有任务在创建时初始状态均为 `pending`（待处理）。
                        
            ## 使用技巧
                        
            - **明确具体**：创建具有清晰、具体主题的任务，描述预期的结果。
            - **细节详尽**：在描述中包含足够的细节，以便另一个智能体（Agent）能够理解并完成该任务。
            - **管理依赖**：创建任务后，如果需要，使用 `TaskUpdate` 设置依赖关系（blocks/blockedBy）。
            - **避免重复**：在创建任务前先检查 `TaskList`，以避免创建重复任务。
            """)
    public String taskCreate(
            @ToolParam(description = "简短、具体的标题，建议使用祈使句，如 'Fix authentication bug'") String subject,
            @ToolParam(description = "详细说明需要执行的操作、上下文及验收标准") String description,
            @ToolParam(description = "任务进行时中显示的进行时描述", required = false) String activeForm,
            @ToolParam(description = "附加的任意元数据（键值对格式）", required = false) Map<String, Object> metadata,
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
            if (description == null || description.isBlank()) {
                log.warn("[TaskCreate] 任务描述为空，userId={}, sessionId={}", userId, sessionId);
                return buildError("任务描述不能为空");
            }

            Map<String, Object> taskMetadata = new HashMap<>();
            taskMetadata.put("agent_id", "system");
            taskMetadata.put("last_action_source", "ai");
            taskMetadata.put("created_at", LocalDateTime.now().toString());
            if (activeForm != null && !activeForm.isBlank()) {
                taskMetadata.put("activeForm", activeForm);
            }
            if (metadata != null && !metadata.isEmpty()) {
                taskMetadata.putAll(metadata);
            }

            log.info("[TaskCreate] subject={}, activeForm={}, userId={}, sessionId={}",
                    subject, activeForm, userId, sessionId);

            TaskEntity task = taskService.create(userId, sessionId, subject, description, activeForm, Collections.emptyList(), taskMetadata);

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

            ## Field Updates
            - addBlocks: Mark tasks this task blocks (must complete before others start)
            - addBlockedBy: Mark tasks this task depends on (must complete first)
            - metadata: Merged incrementally (pass null to delete a key)
            """)
    public String taskUpdate(
            @ToolParam(description = "需要更新的任务ID") String taskId,
            @ToolParam(description = "修改任务标题", required = false) String subject,
            @ToolParam(description = "修改任务详细描述", required = false) String description,
            @ToolParam(description = "新状态: pending/in_progress/completed", required = false) String status,
            @ToolParam(description = "修改任务进行时的展示文案", required = false) String activeForm,
            @ToolParam(description = "认领任务（更改任务拥有者的 Agent 名称）", required = false) String owner,
            @ToolParam(description = "标记当前任务完成后，哪些任务才能开始", required = false) List<String> addBlocks,
            @ToolParam(description = "标记哪些任务必须先完成，当前任务才能开始", required = false) List<String> addBlockedBy,
            @ToolParam(description = "合并元数据（传 null 表示删除该键）", required = false) Map<String, Object> metadata,
            ToolContext toolContext) {

        try {
            Map<String, Object> context = toolContext.getContext();
            String userId = context.get(AdvisorContextConstants.USER_ID).toString();
            String sessionId = context.get(AdvisorContextConstants.SESSION_ID).toString();
            if (status != null && !VALID_STATUSES.contains(status)) {
                log.warn("[TaskUpdate] 无效状态={}, taskId={}, userId={}, sessionId={}", status, taskId, userId, sessionId);
                return buildError("无效的状态: " + status + "，可选值: pending/in_progress/completed");
            }

            UUID id = parseUUID(taskId);
            List<UUID> blocks = parseUUIDs(addBlocks);
            List<UUID> blockedBy = parseUUIDs(addBlockedBy);

            log.info("[TaskUpdate] taskId={}, subject={}, status={}, activeForm={}, owner={}, userId={}, sessionId={}",
                    taskId, subject, status, activeForm, owner, userId, sessionId);

            TaskEntity task = taskService.update(id, userId, sessionId, subject, description, status, activeForm, owner, blocks, blockedBy, metadata);

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
            - block: Wait for completion (default: true)
            - timeout: Max wait time in ms (default: 30000, max: 600000)
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
        if (task.activeForm() != null && !task.activeForm().isEmpty()) {
            sb.append("- **进行中**: ").append(task.activeForm()).append("\n");
        }
        if (task.owner() != null && !task.owner().isEmpty()) {
            sb.append("- **认领者**: ").append(task.owner()).append("\n");
        }
        if (!task.blockedBy().isEmpty()) {
            sb.append("- **被阻塞**: ").append(task.blockedBy().size()).append(" 个任务\n");
        }
        if (!task.blocks().isEmpty()) {
            sb.append("- **阻塞**: ").append(task.blocks().size()).append(" 个任务\n");
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
            if (task.owner() != null && !task.owner().isEmpty()) {
                sb.append("认领: ").append(task.owner()).append("\n");
            }
            if (!task.blockedBy().isEmpty()) {
                sb.append("被阻塞: ").append(task.blockedBy().size()).append(" 个\n");
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
