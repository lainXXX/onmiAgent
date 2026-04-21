package top.javarem.omni.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.repository.ChatSessionRepository;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatSessionRepository chatSessionRepository;

    private static final RowMapper<ChatSession> ROW_MAPPER = (rs, rowNum) -> ChatSession.builder()
            .id(rs.getString("id"))
            .userId(rs.getString("user_id"))
            .headId(rs.getString("head_id"))
            .createdAt(rs.getTimestamp("created_at") != null ?
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    @Override
    public ChatSession createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = ChatSession.builder()
                .id(sessionId)
                .userId(userId)
                .headId(null)  // 初始无链头
                .build();
        chatSessionRepository.save(session);
        log.info("[ChatSession] 创建会话 id={}, userId={}", sessionId, userId);
        return session;
    }

    @Override
    public ChatSession getSession(String sessionId) {
        return chatSessionRepository.findById(sessionId);
    }

    @Override
    public List<ChatSession> getUserSessions(String userId) {
        String sql = "SELECT * FROM chat_session WHERE user_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, userId);
    }
}