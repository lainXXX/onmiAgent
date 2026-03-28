package top.javarem.omni.service.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.javarem.omni.exception.TaskException;
import top.javarem.omni.model.task.TaskEntity;
import top.javarem.omni.repository.task.TaskRepository;

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
                             String description, String activeForm,
                             List<UUID> blockedBy, Map<String, Object> metadata) {

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
            activeForm,
            null,
            mergedMeta,
            Collections.emptyList(),
            blockedBy != null ? blockedBy : Collections.emptyList(),
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
                            String activeForm, String owner,
                            List<UUID> blocks, List<UUID> blockedBy,
                            Map<String, Object> metadata) {

        TaskEntity existing = repository.findById(taskId, userId, sessionId)
            .orElseThrow(() -> TaskException.notFound(taskId));

        // 依赖检查：状态变为 in_progress 时
        if (status != null && status.equals(TaskEntity.STATUS_IN_PROGRESS)
            && !existing.status().equals(TaskEntity.STATUS_IN_PROGRESS)) {
            checkDependencies(taskId, userId, sessionId);
        }

        // 循环依赖检测
        if (blockedBy != null && !blockedBy.isEmpty()) {
            checkCircularDependency(taskId, blockedBy, userId, sessionId);
        }

        // 合并 metadata
        Map<String, Object> mergedMeta = new HashMap<>(existing.metadata());
        if (metadata != null) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() == null) {
                    mergedMeta.remove(entry.getKey());
                } else {
                    mergedMeta.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // 合并 blocks
        List<UUID> mergedBlocks = new java.util.ArrayList<>(existing.blocks());
        if (blocks != null) {
            mergedBlocks.addAll(blocks);
        }

        // 合并 blockedBy
        List<UUID> mergedBlockedBy = new java.util.ArrayList<>(existing.blockedBy());
        if (blockedBy != null) {
            mergedBlockedBy.addAll(blockedBy);
        }

        TaskEntity updated = new TaskEntity(
            existing.id(),
            existing.userId(),
            existing.sessionId(),
            subject != null ? subject : existing.subject(),
            description != null ? description : existing.description(),
            status != null ? status : existing.status(),
            activeForm != null ? activeForm : existing.activeForm(),
            owner != null ? owner : existing.owner(),
            mergedMeta,
            mergedBlocks,
            mergedBlockedBy,
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

    private void checkCircularDependency(UUID taskId, List<UUID> newBlockedBy,
                                         String userId, String sessionId) {
        // 检测直接依赖自己
        if (newBlockedBy.contains(taskId)) {
            throw TaskException.circularDependency();
        }

        // 深度检测
        Set<UUID> visited = new HashSet<>();
        for (UUID depId : newBlockedBy) {
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

        List<UUID> blockedBy = repository.findBlockedBy(current, userId, sessionId);
        for (UUID depId : blockedBy) {
            if (detectCycle(depId, target, userId, sessionId, new HashSet<>(visited))) {
                return true;
            }
        }

        return false;
    }
}
