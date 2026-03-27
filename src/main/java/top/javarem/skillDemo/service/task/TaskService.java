package top.javarem.skillDemo.service.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.javarem.skillDemo.exception.TaskException;
import top.javarem.skillDemo.model.task.TaskEntity;
import top.javarem.skillDemo.repository.task.TaskRepository;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class TaskService {

    private final TaskRepository repository;

    public TaskService(TaskRepository repository) {
        this.repository = repository;
    }

    public TaskEntity create(String userId, String sessionId, String subject,
                             String description, String priority, LocalDateTime dueDate,
                             List<UUID> dependencies, Map<String, Object> metadata) {

        // 合并 metadata，注入来源标识
        Map<String, Object> mergedMeta = new HashMap<>();
        if (metadata != null) {
            mergedMeta.putAll(metadata);
        }
        mergedMeta.put("agent_id", "system");
        mergedMeta.put("last_action_source", "ai");
        mergedMeta.put("created_at", LocalDateTime.now().toString());

        TaskEntity task = new TaskEntity(
            UUID.randomUUID(),
            userId,
            sessionId,
            subject,
            description != null ? description : "",
            TaskEntity.STATUS_PENDING,
            priority != null ? priority : TaskEntity.PRIORITY_MEDIUM,
            dueDate,
            mergedMeta,
            dependencies != null ? dependencies : Collections.emptyList(),
            LocalDateTime.now(),
            LocalDateTime.now()
        );

        return repository.insert(task);
    }

    public List<TaskEntity> list(String userId, String sessionId, String status,
                                  int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return repository.findByUserAndSession(userId, sessionId, status, pageSize, offset);
    }

    public int count(String userId, String sessionId, String status) {
        return repository.countByUserAndSession(userId, sessionId, status);
    }

    public Map<String, Integer> stats(String userId, String sessionId) {
        return repository.countByStatus(userId, sessionId);
    }

    public TaskEntity get(UUID taskId, String userId, String sessionId) {
        return repository.findById(taskId, userId, sessionId)
            .orElseThrow(() -> TaskException.notFound(taskId));
    }

    public TaskEntity update(UUID taskId, String userId, String sessionId,
                            String subject, String description, String status,
                            String priority, LocalDateTime dueDate,
                            Map<String, Object> metadata, List<UUID> dependencies) {

        TaskEntity existing = repository.findById(taskId, userId, sessionId)
            .orElseThrow(() -> TaskException.notFound(taskId));

        // 依赖检查：状态变为 in_progress 时
        if (status != null && status.equals(TaskEntity.STATUS_IN_PROGRESS)
            && !existing.status().equals(TaskEntity.STATUS_IN_PROGRESS)) {
            checkDependencies(taskId, userId, sessionId);
        }

        // 循环依赖检测
        if (dependencies != null && !dependencies.isEmpty()) {
            checkCircularDependency(taskId, dependencies, userId, sessionId);
        }

        TaskEntity updated = new TaskEntity(
            existing.id(),
            existing.userId(),
            existing.sessionId(),
            subject != null ? subject : existing.subject(),
            description != null ? description : existing.description(),
            status != null ? status : existing.status(),
            priority != null ? priority : existing.priority(),
            dueDate != null ? dueDate : existing.dueDate(),
            metadata != null ? metadata : existing.metadata(),
            dependencies != null ? dependencies : existing.dependencies(),
            existing.createdAt(),
            LocalDateTime.now()
        );

        repository.update(updated);
        return repository.findById(taskId, userId, sessionId).orElse(updated);
    }

    public void delete(UUID taskId, String userId, String sessionId) {
        TaskEntity existing = repository.findById(taskId, userId, sessionId)
            .orElseThrow(() -> TaskException.notFound(taskId));

        // 检查是否有下游依赖
        List<UUID> dependents = repository.findDependents(taskId, userId, sessionId);
        if (!dependents.isEmpty()) {
            throw TaskException.hasDependents(taskId);
        }

        repository.delete(taskId, userId, sessionId);
    }

    private void checkDependencies(UUID taskId, String userId, String sessionId) {
        List<UUID> pending = repository.findUnfinishedDependencies(taskId, userId, sessionId);
        if (!pending.isEmpty()) {
            throw TaskException.dependencyBlocked(pending);
        }
    }

    private void checkCircularDependency(UUID taskId, List<UUID> newDeps,
                                         String userId, String sessionId) {
        // 检测直接依赖自己
        if (newDeps.contains(taskId)) {
            throw TaskException.circularDependency();
        }

        // 深度检测
        Set<UUID> visited = new HashSet<>();
        for (UUID depId : newDeps) {
            if (detectCycle(depId, taskId, userId, sessionId, visited)) {
                throw TaskException.circularDependency();
            }
        }
    }

    private boolean detectCycle(UUID current, UUID target,
                                String userId, String sessionId, Set<UUID> visited) {
        if (current.equals(target)) return true;
        if (visited.contains(current)) return false;

        visited.add(current);

        List<UUID> deps = repository.findDependencies(current, userId, sessionId);
        for (UUID depId : deps) {
            if (detectCycle(depId, target, userId, sessionId, new HashSet<>(visited))) {
                return true;
            }
        }

        return false;
    }
}
