package top.javarem.onmi.exception;

import java.util.List;
import java.util.UUID;

public class TaskException extends RuntimeException {

    private final String code;
    private final List<UUID> blockedBy;

    public TaskException(String message) {
        super(message);
        this.code = "TASK_ERROR";
        this.blockedBy = null;
    }

    public TaskException(String code, String message) {
        super(message);
        this.code = code;
        this.blockedBy = null;
    }

    public TaskException(String code, String message, List<UUID> blockedBy) {
        super(message);
        this.code = code;
        this.blockedBy = blockedBy;
    }

    public String getCode() {
        return code;
    }

    public List<UUID> getBlockedBy() {
        return blockedBy;
    }

    public static TaskException notFound(UUID taskId) {
        return new TaskException("NOT_FOUND", "任务不存在: " + taskId);
    }

    public static TaskException dependencyBlocked(List<UUID> blockedBy) {
        return new TaskException("DEPENDENCY_BLOCKED",
            "前置任务未完成，无法执行此操作", blockedBy);
    }

    public static TaskException circularDependency() {
        return new TaskException("CIRCULAR_DEPENDENCY", "检测到循环依赖，请检查任务依赖关系");
    }

    public static TaskException hasDependents(UUID taskId) {
        return new TaskException("HAS_DEPENDENTS",
            "有其他任务依赖此任务，请先解除依赖关系后再删除", List.of(taskId));
    }
}
