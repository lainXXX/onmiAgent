package top.javarem.skillDemo.repository.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Chat History 仓储
 * 负责存储对话历史记录，以用户消息为根节点（parent_id）
 */
@Repository
@Slf4j
public class ChatHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存用户消息，返回自增 ID 作为 parentId
     */
    @Transactional
    public Long saveUserMessage(String conversationId, String userId, String content) {
        String sql = "INSERT INTO chat_history (parent_id, conversation_id, user_id, role, content) VALUES (NULL, ?, ?, 'USER', ?)";
        jdbcTemplate.update(sql, conversationId, userId, content);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * 保存助手消息，关联用户消息的 parentId
     */
    @Transactional
    public void saveAssistantMessage(String conversationId, Long parentId, String userId, String content) {
        String sql = "INSERT INTO chat_history (parent_id, conversation_id, user_id, role, content) VALUES (?, ?, ?, 'ASSISTANT', ?)";
        jdbcTemplate.update(sql, parentId, conversationId, userId, content);
    }

    /**
     * 根据会话 ID 查询消息历史（返回 Message 列表，用于构建 Prompt）
     */
    public List<ChatHistoryRow> findByConversationId(String conversationId) {
        String sql = """
            SELECT id, parent_id, conversation_id, user_id, role, content, metadata, created_at
            FROM chat_history WHERE conversation_id = ? ORDER BY id ASC
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ChatHistoryRow row = new ChatHistoryRow();
            row.setId(rs.getLong("id"));
            long parentId = rs.getLong("parent_id");
            row.setParentId(rs.wasNull() ? null : parentId);
            row.setConversationId(rs.getString("conversation_id"));
            row.setUserId(rs.getString("user_id"));
            row.setRole(rs.getString("role"));
            row.setContent(rs.getString("content"));
            row.setMetadata(rs.getString("metadata"));
            row.setCreatedAt(rs.getTimestamp("created_at"));
            return row;
        }, conversationId);
    }

    /**
     * 删除指定 parentId 对应的整轮对话
     */
    @Transactional
    public int deleteByParentId(Long parentId) {
        String sql = "DELETE FROM chat_history WHERE id = ? OR parent_id = ?";
        return jdbcTemplate.update(sql, parentId, parentId);
    }

    @lombok.Data
    public static class ChatHistoryRow {
        private Long id;
        private Long parentId;
        private String conversationId;
        private String userId;
        private String role;
        private String content;
        private String metadata;
        private java.sql.Timestamp createdAt;
    }
}
