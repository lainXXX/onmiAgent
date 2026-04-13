package top.javarem.omni.repository.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.javarem.omni.utils.MessageConvert;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Repository
@Slf4j
public class MemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    public MemoryRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 聊天记忆表 chat_memory 操作 ====================

    /**
     * 保存用户消息，返回自增 ID
     */
    @Transactional
    public Long saveUserMessage(String conversationId, String userId, String content) {
        String sql = "INSERT INTO chat_memory (parent_id, conversation_id, user_id, role, content) VALUES (NULL, ?, ?, 'USER', ?)";
        jdbcTemplate.update(sql, conversationId, userId, content);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * 保存助手消息
     */
    @Transactional
    public void saveAssistantMessage(String conversationId, Long parentId, String userId, String content) {
        String sql = "INSERT INTO chat_memory (parent_id, conversation_id, user_id, role, content) VALUES (?, ?, ?, 'ASSISTANT', ?)";
        jdbcTemplate.update(sql, parentId, conversationId, userId, content);
    }

    /**
     * 根据会话 ID 查询消息历史（所有轮次）
     */
    public List<ChatHistoryRow> findByConversationId(String conversationId) {
        String sql = """
            SELECT id, parent_id, conversation_id, user_id, role, content, metadata, created_at
            FROM chat_memory WHERE conversation_id = ? ORDER BY id ASC
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
     * 根据会话 ID 查询消息历史（返回 Message 列表，用于构建 Prompt）
     */
    public List<Message> findMessagesByConversationId(String conversationId) {
        String sql = "SELECT role, content FROM chat_memory WHERE conversation_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String role = rs.getString("role");
            String content = rs.getString("content");
            return restoreMessage(content, role, null);
        }, conversationId);
    }

    /**
     * 根据会话 ID 查询所有轮次的首条用户消息
     */
    public List<ChatHistoryRow> findUserMessagesByConversationId(String conversationId) {
        String sql = """
            SELECT id, parent_id, conversation_id, user_id, role, content, metadata, created_at
            FROM chat_memory WHERE conversation_id = ? AND role = 'USER' ORDER BY id ASC
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
     * 根据 parentId 查询某一轮的所有消息
     */
    public List<ChatHistoryRow> findByParentId(Long parentId) {
        String sql = """
            SELECT id, parent_id, conversation_id, user_id, role, content, metadata, created_at
            FROM chat_memory WHERE id = ? OR parent_id = ? ORDER BY id ASC
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ChatHistoryRow row = new ChatHistoryRow();
            row.setId(rs.getLong("id"));
            long pid = rs.getLong("parent_id");
            row.setParentId(rs.wasNull() ? null : pid);
            row.setConversationId(rs.getString("conversation_id"));
            row.setUserId(rs.getString("user_id"));
            row.setRole(rs.getString("role"));
            row.setContent(rs.getString("content"));
            row.setMetadata(rs.getString("metadata"));
            row.setCreatedAt(rs.getTimestamp("created_at"));
            return row;
        }, parentId, parentId);
    }

    /**
     * 根据 parentId 查询某一轮的所有消息（返回 Message 对象）
     */
    public List<Message> findMessagesByParentId(Long parentId) {
        String sql = """
            SELECT id, parent_id, role, content FROM chat_memory WHERE id = ? OR parent_id = ? ORDER BY id ASC
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            long pid = rs.getLong("parent_id");
            Long parentIdVal = rs.wasNull() ? null : pid;
            String role = rs.getString("role");
            String content = rs.getString("content");
            return restoreMessage(content, role, parentIdVal);
        }, parentId, parentId);
    }

    /**
     * 删除指定 parentId 对应的整轮对话
     */
    @Transactional
    public int deleteByParentId(Long parentId) {
        String sql = "DELETE FROM chat_memory WHERE id = ? OR parent_id = ?";
        return jdbcTemplate.update(sql, parentId, parentId);
    }

    /**
     * 压缩聊天记忆：软删除中间记录，保留头部和尾部
     *
     * <p>使用软删除（is_compressed=1）替代物理删除，防止进程 Crash 导致数据丢失
     */
    @Transactional
    public int compress(String conversationId, int keepHead, int keepTail) {
        return compress(conversationId, keepHead, keepTail, null);
    }

    /**
     * 压缩聊天记忆：软删除中间记录
     *
     * @param conversationId 会话 ID
     * @param keepHead 保留头部记录数
     * @param keepTail 保留尾部记录数
     * @param summaryId 摘要 ID（用于 compressed_by 字段）
     * @return 被标记为压缩的记录数
     */
    @Transactional
    public int compress(String conversationId, int keepHead, int keepTail, String summaryId) {
        if (keepHead < 0 || keepTail < 0) {
            throw new IllegalArgumentException("keepHead and keepTail must be non-negative");
        }
        String sql = """
            UPDATE chat_memory
            SET is_compressed = 1,
                compressed_by = ?,
                compressed_at = NOW()
            WHERE conversation_id = ?
            AND id NOT IN (
              SELECT id FROM (SELECT id FROM chat_memory WHERE conversation_id = ? ORDER BY id ASC LIMIT ?) as h
              UNION
              SELECT id FROM (SELECT id FROM chat_memory WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) as t
            )
            AND is_compressed = 0
            """;
        return jdbcTemplate.update(sql, summaryId, conversationId, conversationId, keepHead, conversationId, keepTail);
    }

    /**
     * 可选：定期物理删除已压缩的旧记录
     *
     * @param daysOld 删除多少天前的压缩记录
     * @return 删除的记录数
     */
    @Transactional
    public int purgeCompressedRecords(int daysOld) {
        String sql = """
            DELETE FROM chat_memory
            WHERE is_compressed = 1
            AND compressed_at < DATE_SUB(NOW(), INTERVAL ? DAY)
            """;
        return jdbcTemplate.update(sql, daysOld);
    }

    /**
     * 批量插入聊天记录
     */
    @Transactional
    public void saveAll(String conversationId, String userId, List<Message> messages) {
        String sql = "INSERT INTO chat_memory (conversation_id, user_id, role, content) VALUES (?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Message message = messages.get(i);
                ps.setString(1, conversationId);
                ps.setString(2, userId);
                ps.setString(3, message.getMessageType().name());
                ps.setString(4, MessageConvert.convertMessage(message));
            }

            @Override
            public int getBatchSize() {
                return messages.size();
            }
        });
    }

    public void save(String conversationId, String userId, Message message) {
        String sql = "INSERT INTO chat_memory (conversation_id, user_id, role, content) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql, conversationId, userId, message.getMessageType().name(), MessageConvert.convertMessage(message));
    }

    // ==================== 工具方法 ====================

    /**
     * 根据 content 和 type 还原 Message 对象
     */
    private Message restoreMessage(String content, String type, Long parentId) {
        return switch (type) {
            case "USER" -> new UserMessage(content != null ? content : "");
            case "ASSISTANT" -> new AssistantMessage(content != null ? content : "");
            case "SYSTEM" -> new SystemMessage(content != null ? content : "");
            default -> new UserMessage(content != null ? content : "");
        };
    }

    // ==================== 内部类 ====================

    @Data
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
