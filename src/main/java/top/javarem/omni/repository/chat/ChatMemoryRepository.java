package top.javarem.omni.repository.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Chat Memory 仓储 - 链表式消息存储
 * <p>核心能力：原子化链表推进 (Save & Move Head) + 递归 CTE 上下文回溯
 */
@Repository
@Slf4j
public class ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatMemoryRepository(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 消息保存入口（按类型） ====================

    /**
     * 保存 UserMessage
     */
    @Transactional
    public String saveUserMessage(String sessionId, String userId, UserMessage message, Usage usage) {
        return saveAndMoveHead(sessionId, userId, message.getText(), "user", null, usage);
    }

    /**
     * 保存 AssistantMessage
     */
    @Transactional
    public String saveAssistantMessage(String sessionId, String userId, AssistantMessage message, Usage usage) {
        return saveAndMoveHead(sessionId, userId, message.getText(), "assistant", serializeObject(message.getToolCalls()), usage);
    }
    @Transactional
    public String saveToolResponseMessage(String sessionId, String userId, ToolResponseMessage message, Usage usage) {
        String serialized = serializeObject(message.getResponses());
        return saveAndMoveHead(sessionId, userId, serialized, "tool", null, usage);
    }

    /**
     * 保存 SystemMessage
     */
    @Transactional
    public String saveSystemMessage(String sessionId, String userId, SystemMessage message, Usage usage) {
        return saveAndMoveHead(sessionId, userId, message.getText(), "system", null, usage);
    }

    // ==================== 通用原子操作 ====================

    /**
     * 【核心方法】保存任意 Message 对象（自动识别类型）
     */
    @Transactional
    public String saveMessage(String sessionId, String userId, Message message, Usage usage) {
        if (message instanceof UserMessage um) {
            return saveUserMessage(sessionId, userId, um, usage);
        } else if (message instanceof AssistantMessage am) {
            return saveAssistantMessage(sessionId, userId, am, usage);
        } else if (message instanceof ToolResponseMessage trm) {
            return saveToolResponseMessage(sessionId, userId, trm, usage);
        } else if (message instanceof SystemMessage sm) {
            return saveSystemMessage(sessionId, userId, sm, usage);
        }
        return saveAndMoveHead(sessionId, userId, message.getText(), "system", null, usage);
    }

    /**
     * 【核心方法】保存消息，并原子性地将 Session 的 Head 指针移动到新消息。
     */
    @Transactional
    public String saveMessageAndMoveHead(String sessionId, String userId, Message message, Usage usage) {
        return saveMessage(sessionId, userId, message, usage);
    }

    @Transactional
    public String saveMessageAndMoveHead(String sessionId, Message message, Usage usage) {
        return saveMessage(sessionId, "system", message, usage);
    }

    // ==================== 上下文获取 ====================

    /**
     * 获取最干净的上下文（递归 CTE，按时间正序）
     */
    public List<Message> getCleanContext(String sessionId) {
        String sql = """
            WITH RECURSIVE context_chain AS (
                SELECT m.* FROM chat_memory m
                INNER JOIN chat_session s ON m.id = s.head_id
                WHERE s.id = ?

                UNION ALL

                SELECT m.* FROM chat_memory m
                INNER JOIN context_chain cc ON m.id = cc.parent_id
            )
            SELECT message_type, content, tool_calls, prompt_tokens, completion_tokens, total_tokens
            FROM context_chain
            ORDER BY created_at ASC
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> restoreMessage(
                rs.getString("message_type"),
                rs.getString("content"),
                rs.getString("tool_calls"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getInt("total_tokens")
        ), sessionId);
    }

    // ==================== 撤销功能 ====================

    /**
     * 撤销最后一条消息（沿 parent_id 回退 head）
     */
    @Transactional
    public void undo(String sessionId) {
        String currentHeadId = getCurrentHeadId(sessionId);
        if (currentHeadId == null) return;

        try {
            String parentId = jdbcTemplate.queryForObject(
                    "SELECT parent_id FROM chat_memory WHERE id = ?", String.class, currentHeadId);
            if (parentId != null) {
                updateSessionHead(sessionId, parentId);
                log.info("[ChatMemory] 会话 {} 已撤销，回退至节点 {}", sessionId, parentId);
            }
        } catch (Exception e) {
            log.warn("Undo failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    private String saveAndMoveHead(String sessionId, String userId, String content,
                                   String messageType, String toolCallsJson, Usage usage) {
        String currentHeadId = getCurrentHeadId(sessionId);
        String newId = UUID.randomUUID().toString();

        int pTokens = usage != null ? usage.getPromptTokens() : 0;
        int cTokens = usage != null ? usage.getCompletionTokens() : 0;
        int tTokens = usage != null ? usage.getTotalTokens() : 0;

        String sql = """
            INSERT INTO chat_memory
            (id, parent_id, session_id, user_id, message_type, content, tool_calls, prompt_tokens, completion_tokens, total_tokens)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql, newId, currentHeadId, sessionId, userId,
                messageType, content, toolCallsJson, pTokens, cTokens, tTokens);
        updateSessionHead(sessionId, newId);

        log.debug("[ChatMemory] 消息 {} 已追加到会话 {}, type={}", newId, sessionId, messageType);
        return newId;
    }

    private String getCurrentHeadId(String sessionId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT head_id FROM chat_session WHERE id = ?", String.class, sessionId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private void updateSessionHead(String sessionId, String headId) {
        String updateSql = "UPDATE chat_session SET head_id = ? WHERE id = ?";
        int rows = jdbcTemplate.update(updateSql, headId, sessionId);

        if (rows == 0) {
            String insertSql = "INSERT INTO chat_session (id, head_id) VALUES (?, ?)";
            jdbcTemplate.update(insertSql, sessionId, headId);
        }
    }

    private Message restoreMessage(String messageType, String content, String toolCallsJson,
                                  int promptTokens, int completionTokens, int totalTokens) {
        Map<String, Object> props = buildProperties(promptTokens, completionTokens, totalTokens);

        return switch (messageType) {
            case "user" -> UserMessage.builder()
                    .text(content)
                    .media(List.of())
                    .metadata(props)
                    .build();
            case "assistant" -> {
                List<AssistantMessage.ToolCall> toolCalls = deserializeList(toolCallsJson, new TypeReference<>() {});
                AssistantMessage.Builder builder = AssistantMessage.builder().content(content);
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    builder.toolCalls(toolCalls);
                }
                if (!props.isEmpty()) {
                    builder.properties(props);
                }
                yield builder.build();
            }
            case "tool" -> {
                List<ToolResponseMessage.ToolResponse> responses = deserializeList(content, new TypeReference<>() {});
                if (responses == null) responses = List.of();
                yield ToolResponseMessage.builder().responses(responses).metadata(props).build();
            }
            default -> new SystemMessage(content);
        };
    }

    private Map<String, Object> buildProperties(int promptTokens, int completionTokens, int totalTokens) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (promptTokens > 0) props.put("promptTokens", promptTokens);
        if (completionTokens > 0) props.put("completionTokens", completionTokens);
        if (totalTokens > 0) props.put("totalTokens", totalTokens);
        return props;
    }

    private String serializeObject(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[ChatMemory] 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private <T> List<T> deserializeList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("[ChatMemory] 反序列化失败: {}", e.getMessage());
            return null;
        }
    }
}