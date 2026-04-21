package top.javarem.omni.chat.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.MessageType;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ChatMemory> ROW_MAPPER = (rs, rowNum) -> ChatMemory.builder()
            .id(rs.getString("id"))
            .parentId(rs.getString("parent_id"))
            .sessionId(rs.getString("session_id"))
            .userId(rs.getString("user_id"))
            .messageType(MessageType.valueOf(rs.getString("message_type")))
            .content(rs.getString("content"))
            .toolCallId(rs.getString("tool_call_id"))
            .toolName(rs.getString("tool_name"))
            .promptTokens(rs.getObject("prompt_tokens") != null ? rs.getInt("prompt_tokens") : 0)
            .completionTokens(rs.getObject("completion_tokens") != null ? rs.getInt("completion_tokens") : 0)
            .totalTokens(rs.getObject("total_tokens") != null ? rs.getInt("total_tokens") : 0)
            .errorCode(rs.getString("error_code"))
            .createdAt(rs.getTimestamp("created_at") != null ?
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    /**
     * 保存消息
     */
    public void save(ChatMemory chatMemory) {
        String sql = """
            INSERT INTO chat_memory (id, parent_id, session_id, user_id, message_type, content,
                                    tool_call_id, tool_name, prompt_tokens, completion_tokens, total_tokens, error_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
                chatMemory.getId(),
                chatMemory.getParentId(),
                chatMemory.getSessionId(),
                chatMemory.getUserId(),
                chatMemory.getMessageType().name(),
                chatMemory.getContent(),
                chatMemory.getToolCallId(),
                chatMemory.getToolName(),
                chatMemory.getPromptTokens(),
                chatMemory.getCompletionTokens(),
                chatMemory.getTotalTokens(),
                chatMemory.getErrorCode());
    }

    /**
     * 根据 ID 查询
     */
    public ChatMemory findById(String id) {
        String sql = "SELECT * FROM chat_memory WHERE id = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, id).stream().findFirst().orElse(null);
    }

    /**
     * 递归查询完整上下文（链表顺序）
     */
    public List<ChatMemory> findContextBySessionId(String sessionId, String headId) {
        String sql = """
            WITH RECURSIVE ctx AS (
                SELECT * FROM chat_memory WHERE id = ?
                UNION ALL
                SELECT m.* FROM chat_memory m INNER JOIN ctx ON ctx.parent_id = m.id
            )
            SELECT * FROM ctx ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER, headId);
    }

    /**
     * 累计 Token 数
     */
    public int sumTokensBySessionId(String sessionId) {
        String sql = """
            WITH RECURSIVE ctx AS (
                SELECT * FROM chat_memory WHERE id = (SELECT head_id FROM chat_session WHERE id = ?)
                UNION ALL
                SELECT m.* FROM chat_memory m INNER JOIN ctx ON ctx.parent_id = m.id
            )
            SELECT COALESCE(SUM(total_tokens), 0) as total FROM ctx
            """;
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, sessionId);
        return result != null ? result : 0;
    }

    /**
     * 根据会话ID查询消息列表（返回 Spring AI Message）
     */
    public List<Message> findMessagesByConversationId(String conversationId) {
        String sql = """
            WITH RECURSIVE ctx AS (
                SELECT * FROM chat_memory WHERE session_id = ? AND parent_id IS NULL
                UNION ALL
                SELECT m.* FROM chat_memory m INNER JOIN ctx ON ctx.id = m.parent_id
            )
            SELECT * FROM ctx ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String type = rs.getString("message_type");
            String content = rs.getString("content");
            return restoreMessage(content, type);
        }, conversationId);
    }

    private Message restoreMessage(String content, String type) {
        if (content == null) {
            content = "";
        }
        return switch (type) {
            case "USER" -> new UserMessage(content);
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM" -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }

    // ==================== 向后兼容方法 (Legacy) ====================

    /**
     * 保存用户消息（兼容旧 API）
     */
    public Long saveUserMessage(String conversationId, String userId, String content) {
        String sql = """
            INSERT INTO chat_memory (id, parent_id, conversation_id, user_id, message_type, content)
            VALUES (?, NULL, ?, ?, 'USER', ?)
            """;
        String id = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(sql, id, conversationId, userId, content);
        // Return auto-increment ID (old behavior)
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * 压缩聊天记忆（兼容旧 API）
     */
    public int compress(String conversationId, int keepHead, int keepTail, String summaryId) {
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
}