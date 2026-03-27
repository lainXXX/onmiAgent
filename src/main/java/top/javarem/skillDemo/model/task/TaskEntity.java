package top.javarem.skillDemo.model.task;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务实体类 (Record)
 * 用于存储和传输任务的核心信息，包含任务生命周期、优先级及依赖关系。
 * * @param id           任务唯一标识 (UUID)
 * @param userId       所属用户 ID
 * @param sessionId    关联的会话 ID（用于追踪任务来源或上下文）
 * @param subject      任务标题/主题
 * @param description  任务详细描述
 * @param status       任务当前状态 (pending, in_progress, completed, deleted)
 * @param priority     任务优先级 (high, medium, low)
 * @param dueDate      截止日期
 * @param metadata     扩展元数据（用于存储插件或自定义业务数据）
 * @param dependencies 依赖的任务 ID 列表（当前任务开始前需完成的前置任务）
 * @param createdAt    创建时间
 * @param updatedAt    最后更新时间
 */
public record TaskEntity(
        UUID id,
        String userId,
        String sessionId,
        String subject,
        String description,
        String status,
        String priority,
        LocalDateTime dueDate,
        Map<String, Object> metadata,
        List<UUID> dependencies,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    // --- 状态常量定义 ---
    /** 待处理 */
    public static final String STATUS_PENDING = "pending";
    /** 进行中 */
    public static final String STATUS_IN_PROGRESS = "in_progress";
    /** 已完成 */
    public static final String STATUS_COMPLETED = "completed";
    /** 已删除 (软删除标记) */
    public static final String STATUS_DELETED = "deleted";

    // --- 优先级常量定义 ---
    /** 高优先级 */
    public static final String PRIORITY_HIGH = "high";
    /** 中优先级 */
    public static final String PRIORITY_MEDIUM = "medium";
    /** 低优先级 */
    public static final String PRIORITY_LOW = "low";

    /**
     * 检查任务是否处于“已完成”状态
     * @return 如果状态等于 completed 则返回 true
     */
    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    /**
     * 检查任务是否已被标记为“已删除”
     * @return 如果状态等于 deleted 则返回 true
     */
    public boolean isDeleted() {
        return STATUS_DELETED.equals(status);
    }
}