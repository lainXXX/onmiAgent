package top.javarem.omni.chat.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import top.javarem.omni.chat.entity.ChatSession;

import java.sql.ResultSet;
import java.time.LocalDateTime;

@Repository
@Slf4j
@RequiredArgsConstructor
public class ChatSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ChatSession> ROW_MAPPER = (rs, rowNum) -> ChatSession.builder()
            .id(rs.getString("id"))
            .userId(rs.getString("user_id"))
            .headId(rs.getString("head_id"))
            .createdAt(rs.getTimestamp("created_at") != null ?
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    /**
     * 创建会话
     */
    public void save(ChatSession chatSession) {
        String sql = """
            INSERT INTO chat_session (id, user_id, head_id)
            VALUES (?, ?, ?)
            """;
        jdbcTemplate.update(sql,
                chatSession.getId(),
                chatSession.getUserId(),
                chatSession.getHeadId());
    }

    /**
     * 根据 ID 查询
     */
    public ChatSession findById(String id) {
        String sql = "SELECT * FROM chat_session WHERE id = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, id).stream().findFirst().orElse(null);
    }

    /**
     * 更新 HEAD
     */
    public void updateHead(String sessionId, String newHeadId) {
        String sql = "UPDATE chat_session SET head_id = ? WHERE id = ?";
        jdbcTemplate.update(sql, newHeadId, sessionId);
    }
}