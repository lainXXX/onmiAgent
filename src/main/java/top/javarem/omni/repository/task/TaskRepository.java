package top.javarem.onmi.repository.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.javarem.onmi.model.task.TaskEntity;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public TaskEntity insert(TaskEntity task) {
        String sql = """
            INSERT INTO ai_tasks (id, user_id, session_id, subject, description, status, priority, due_date, metadata, dependencies, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
            task.id().toString(),  // UUID 转 String
            task.userId(),
            task.sessionId(),
            task.subject(),
            task.description() != null ? task.description() : "",
            task.status(),
            task.priority(),
            task.dueDate(),
            toJson(task.metadata()),
            toJson(task.dependencies()),
            task.createdAt(),
            task.updatedAt()
        );

        return task;
    }

    public Optional<TaskEntity> findById(UUID id, String userId, String sessionId) {
        String sql = "SELECT * FROM ai_tasks WHERE id = ? AND user_id = ? AND session_id = ? AND status != 'deleted'";
        List<TaskEntity> results = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), id.toString(), userId, sessionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<TaskEntity> findByUserAndSession(String userId, String sessionId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ai_tasks WHERE user_id = ? AND session_id = ? AND status != 'deleted'");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(sessionId);

        if (status != null && !status.isEmpty()) {
            sql.append(" AND status = ?");
            params.add(status);
        }

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
    }

    public int countByUserAndSession(String userId, String sessionId, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ai_tasks WHERE user_id = ? AND session_id = ? AND status != 'deleted'");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(sessionId);

        if (status != null && !status.isEmpty()) {
            sql.append(" AND status = ?");
            params.add(status);
        }

        return jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
    }

    public Map<String, Integer> countByStatus(String userId, String sessionId) {
        String sql = """
            SELECT status, COUNT(*) as count
            FROM ai_tasks
            WHERE user_id = ? AND session_id = ? AND status != 'deleted'
            GROUP BY status
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId, sessionId);
        Map<String, Integer> result = new HashMap<>();
        result.put("pending", 0);
        result.put("in_progress", 0);
        result.put("completed", 0);

        for (Map<String, Object> row : rows) {
            String status = (String) row.get("status");
            Integer count = ((Number) row.get("count")).intValue();
            result.put(status, count);
        }

        return result;
    }

    public int update(TaskEntity task) {
        String sql = """
            UPDATE ai_tasks SET
                subject = ?,
                description = ?,
                status = ?,
                priority = ?,
                due_date = ?,
                metadata = ?,
                dependencies = ?,
                updated_at = ?
            WHERE id = ? AND user_id = ? AND session_id = ?
            """;

        return jdbcTemplate.update(sql,
            task.subject(),
            task.description() != null ? task.description() : "",
            task.status(),
            task.priority(),
            task.dueDate(),
            toJson(task.metadata()),
            toJson(task.dependencies()),
            LocalDateTime.now(),
            task.id().toString(),
            task.userId(),
            task.sessionId()
        );
    }

    public int updateStatus(UUID id, String userId, String sessionId, String status) {
        String sql = "UPDATE ai_tasks SET status = ?, updated_at = ? WHERE id = ? AND user_id = ? AND session_id = ?";
        return jdbcTemplate.update(sql, status, LocalDateTime.now(), id.toString(), userId, sessionId);
    }

    public int delete(UUID id, String userId, String sessionId) {
        String sql = "UPDATE ai_tasks SET status = 'deleted', updated_at = ? WHERE id = ? AND user_id = ? AND session_id = ?";
        return jdbcTemplate.update(sql, LocalDateTime.now(), id.toString(), userId, sessionId);
    }

    public List<UUID> findUnfinishedDependencies(UUID taskId, String userId, String sessionId) {
        // First get the dependencies list from the task
        List<UUID> depIds = findDependencies(taskId, userId, sessionId);
        if (depIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Then find unfinished dependencies
        StringBuilder sql = new StringBuilder("SELECT id FROM ai_tasks WHERE id IN (");
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < depIds.size(); i++) {
            placeholders.add("?");
        }
        sql.append(String.join(",", placeholders)).append(") AND status != 'completed' AND user_id = ? AND session_id = ?");

        List<Object> params = new ArrayList<>();
        for (UUID depId : depIds) {
            params.add(depId.toString());
        }
        params.add(userId);
        params.add(sessionId);

        List<String> results = jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray());
        return results.stream().map(UUID::fromString).toList();
    }

    public List<UUID> findDependents(UUID taskId, String userId, String sessionId) {
        // MySQL doesn't have JSON operators, so we need to search in the JSON string
        String searchPattern = "\"" + taskId.toString() + "\"";
        String sql = """
            SELECT id FROM ai_tasks
            WHERE id != ?
            AND user_id = ? AND session_id = ? AND status != 'deleted'
            AND dependencies LIKE ?
            """;

        List<String> depIds = jdbcTemplate.queryForList(sql, String.class,
            taskId.toString(), userId, sessionId, "%" + searchPattern + "%");

        return depIds.stream().map(UUID::fromString).toList();
    }

    public List<UUID> findDependencies(UUID taskId, String userId, String sessionId) {
        String sql = "SELECT dependencies FROM ai_tasks WHERE id = ? AND user_id = ? AND session_id = ?";
        List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("dependencies"),
            taskId.toString(), userId, sessionId);

        if (results.isEmpty() || results.get(0) == null || results.get(0).isEmpty()) {
            return Collections.emptyList();
        }

        List<String> depIds = fromJson(results.get(0), new TypeReference<List<String>>() {});
        if (depIds == null) {
            return Collections.emptyList();
        }
        return depIds.stream().map(UUID::fromString).toList();
    }

    private TaskEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> deps = fromJson(rs.getString("dependencies"), new TypeReference<List<String>>() {});
        return new TaskEntity(
            UUID.fromString(rs.getString("id")),
            rs.getString("user_id"),
            rs.getString("session_id"),
            rs.getString("subject"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getTimestamp("due_date") != null ? rs.getTimestamp("due_date").toLocalDateTime() : null,
            fromJson(rs.getString("metadata"), new TypeReference<Map<String, Object>>() {}),
            deps != null ? deps.stream().map(UUID::fromString).toList() : Collections.emptyList(),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null
        );
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return null;
        }
    }
}
