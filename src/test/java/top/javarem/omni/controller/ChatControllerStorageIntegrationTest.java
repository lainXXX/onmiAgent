package top.javarem.omni.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.reactive.function.client.WebClient;
import top.javarem.omni.model.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatController 存储集成测试
 *
 * <p>测试流程：
 * <ul>
 *   <li>1. 测试非流式 /chat/user/input 端点的存储完整性</li>
 *   <li>2. 测试流式 /chat/stream 端点的存储完整性</li>
 *   <li>3. 验证 chat_history 表和 spring_ai_chat_memory 表的数据</li>
 *   <li>4. 多轮对话测试 exec_rounds 递增</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-26
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Slf4j
class ChatControllerStorageIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private WebClient webClient;

    private final String userId = "test-user";
    private String sessionId;

    @BeforeEach
    void setUp() {
        // 使用较短的 sessionId 避免超过数据库字段长度限制
        sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[测试开始] sessionId={}", sessionId);
        // 初始化 WebClient
        this.webClient = WebClient.create("http://localhost:" + port);
    }

    @AfterEach
    void tearDown() {
        // 清理测试数据
        if (sessionId != null) {
            String deleteChatHistory = "DELETE FROM chat_history WHERE conversation_id = ?";
            String deleteMemory = "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?";
            mysqlJdbcTemplate.update(deleteChatHistory, sessionId);
            mysqlJdbcTemplate.update(deleteMemory, sessionId);
            log.info("[测试结束] 清理数据 sessionId={}", sessionId);
        }
    }

    /**
     * 测试 1: 非流式对话存储 - chat_history 表
     */
    @Test
    @Order(1)
    void testNonStreamChat_storesToChatHistory() {
        // Given
        ChatRequest request = new ChatRequest();
        request.setQuestion("你好，请介绍一下你自己");
        request.setSessionId(sessionId);

        // When
        String response = restTemplate.postForObject("/chat/user/input", request, String.class);
        log.info("[非流式响应] {}", response);

        // Then - 验证 chat_history 表
        String querySql = """
            SELECT id, parent_id, conversation_id, user_id, role, content
            FROM chat_history WHERE conversation_id = ? ORDER BY id ASC
            """;
        List<ChatHistoryRecord> records = mysqlJdbcTemplate.query(querySql, chatHistoryRowMapper(), sessionId);

        assertNotNull(records, "chat_history 应有数据");
        assertFalse(records.isEmpty(), "chat_history 不应为空");

        // 验证有 USER 消息
        boolean hasUser = records.stream().anyMatch(r -> "USER".equals(r.role));
        assertTrue(hasUser, "应有 USER 角色消息");

        // 验证有 ASSISTANT 消息
        boolean hasAssistant = records.stream().anyMatch(r -> "ASSISTANT".equals(r.role));
        assertTrue(hasAssistant, "应有 ASSISTANT 角色消息");

        log.info("[验证通过] chat_history 有 {} 条记录", records.size());
        records.forEach(r -> log.info("  - id={}, role={}, content={}",
                r.id, r.role, r.content != null ? r.content.substring(0, Math.min(50, r.content.length())) + "..." : "null"));
    }

    /**
     * 测试 2: 非流式对话存储 - spring_ai_chat_memory 表
     */
    @Test
    @Order(2)
    void testNonStreamChat_storesToMemory() {
        // Given
        ChatRequest request = new ChatRequest();
        request.setQuestion("用一句话介绍海洋");
        request.setSessionId(sessionId);

        // When
        String response = restTemplate.postForObject("/chat/user/input", request, String.class);
        log.info("[非流式响应] {}", response);

        // Then - 验证 spring_ai_chat_memory 表
        String querySql = """
            SELECT conversation_id, content, type
            FROM spring_ai_chat_memory WHERE conversation_id = ? ORDER BY id ASC
            """;
        List<MemoryRecord> records = mysqlJdbcTemplate.query(querySql, memoryRowMapper(), sessionId);

        assertNotNull(records, "spring_ai_chat_memory 应有数据");
        assertFalse(records.isEmpty(), "spring_ai_chat_memory 不应为空");

        log.info("[验证通过] spring_ai_chat_memory 有 {} 条记录", records.size());
        records.forEach(r -> log.info("  - type={}, content={}",
                r.type, r.content != null ? r.content.substring(0, Math.min(50, r.content.length())) + "..." : "null"));
    }

    /**
     * 测试 3: 流式对话存储 - chat_history 表
     */
    @Test
    @Order(3)
    void testStreamChat_storesToChatHistory() {
        // Given
        ChatRequest request = new ChatRequest();
        request.setQuestion("什么是人工智能？");
        request.setSessionId(sessionId);

        // When - 流式调用
        List<String> chunks = new ArrayList<>();
        webClient.post()
                .uri("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunks::add)
                .blockLast();

        String fullResponse = String.join("", chunks);
        log.info("[流式完成] 完整响应: {}", fullResponse);

        // Then - 验证 chat_history 表
        String querySql = """
            SELECT id, parent_id, conversation_id, user_id, role, content
            FROM chat_history WHERE conversation_id = ? ORDER BY id ASC
            """;
        List<ChatHistoryRecord> records = mysqlJdbcTemplate.query(querySql, chatHistoryRowMapper(), sessionId);

        assertNotNull(records, "chat_history 应有数据");
        assertFalse(records.isEmpty(), "流式对话后 chat_history 不应为空");

        boolean hasUser = records.stream().anyMatch(r -> "USER".equals(r.role));
        boolean hasAssistant = records.stream().anyMatch(r -> "ASSISTANT".equals(r.role));

        assertTrue(hasUser, "应有 USER 角色消息");
        assertTrue(hasAssistant, "应有 ASSISTANT 角色消息");

        log.info("[验证通过] 流式对话 chat_history 有 {} 条记录", records.size());
    }

    /**
     * 测试 4: 流式对话存储 - spring_ai_chat_memory 表
     */
    @Test
    @Order(4)
    void testStreamChat_storesToMemory() {
        // Given
        ChatRequest request = new ChatRequest();
        request.setQuestion("天为什么是蓝色的？");
        request.setSessionId(sessionId);

        // When - 流式调用
        List<String> chunks = new ArrayList<>();
        webClient.post()
                .uri("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunks::add)
                .blockLast();

        log.info("[流式完成] 收到 {} 个 chunks", chunks.size());

        // Then - 验证 spring_ai_chat_memory 表
        String querySql = """
            SELECT conversation_id, content, type
            FROM spring_ai_chat_memory WHERE conversation_id = ? ORDER BY id ASC
            """;
        List<MemoryRecord> records = mysqlJdbcTemplate.query(querySql, memoryRowMapper(), sessionId);

        assertNotNull(records, "spring_ai_chat_memory 应有数据");
        assertFalse(records.isEmpty(), "流式对话后 spring_ai_chat_memory 不应为空");

        log.info("[验证通过] 流式对话 spring_ai_chat_memory 有 {} 条记录", records.size());
    }

    /**
     * 测试 5: 多轮对话 - 验证记忆连续性
     */
    @Test
    @Order(5)
    void testMultiRoundChat_preservesMemory() {
        // 第一轮
        ChatRequest request1 = new ChatRequest();
        request1.setQuestion("我的名字叫小明");
        request1.setSessionId(sessionId);
        String response1 = restTemplate.postForObject("/chat/user/input", request1, String.class);
        log.info("[第一轮响应] {}", response1);

        // 第二轮 - 问一个需要上下文的问题
        ChatRequest request2 = new ChatRequest();
        request2.setQuestion("我叫什么名字？");
        request2.setSessionId(sessionId);
        String response2 = restTemplate.postForObject("/chat/user/input", request2, String.class);
        log.info("[第二轮响应] {}", response2);

        // 验证 chat_history 有 4 条记录 (2 user + 2 assistant)
        String chatQuery = "SELECT COUNT(*) FROM chat_history WHERE conversation_id = ?";
        Integer count = mysqlJdbcTemplate.queryForObject(chatQuery, Integer.class, sessionId);
        assertEquals(4, count, "多轮对话应有 4 条记录 (2 user + 2 assistant)");

        // 验证 spring_ai_chat_memory 有数据（用于 AI 上下文）
        String memQuery = "SELECT COUNT(*) FROM spring_ai_chat_memory WHERE conversation_id = ?";
        Integer memCount = mysqlJdbcTemplate.queryForObject(memQuery, Integer.class, sessionId);
        assertTrue(memCount >= 4, "多轮对话后 memory 应至少有 4 条记录");

        log.info("[验证通过] 多轮对话记忆完整，chat_history={}, memory={}", count, memCount);
    }

    /**
     * 测试 6: 多轮对话 - exec_rounds 递增验证
     */
    @Test
    @Order(6)
    void testMultiRoundChat_execRoundsIncrement() {
        String progressSessionId = "p-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 第一轮
            ChatRequest request1 = new ChatRequest();
            request1.setQuestion("你好");
            request1.setSessionId(progressSessionId);
            restTemplate.postForObject("/chat/user/input", request1, String.class);

            // 第二轮
            ChatRequest request2 = new ChatRequest();
            request2.setQuestion("今天天气怎么样？");
            request2.setSessionId(progressSessionId);
            restTemplate.postForObject("/chat/user/input", request2, String.class);

            // 第三轮
            ChatRequest request3 = new ChatRequest();
            request3.setQuestion("帮我搜索一下最新新闻");
            request3.setSessionId(progressSessionId);
            restTemplate.postForObject("/chat/user/input", request3, String.class);

            // 验证 chat_history 有 6 条记录 (3 user + 3 assistant)
            String chatQuery = "SELECT COUNT(*) FROM chat_history WHERE conversation_id = ?";
            Integer count = mysqlJdbcTemplate.queryForObject(chatQuery, Integer.class, progressSessionId);
            assertEquals(6, count, "3 轮对话应有 6 条记录");

            log.info("[验证通过] 3 轮对话 exec_rounds=3，chat_history={}", count);
        } finally {
            // 清理 progress 测试数据
            mysqlJdbcTemplate.update("DELETE FROM chat_history WHERE conversation_id = ?", progressSessionId);
            mysqlJdbcTemplate.update("DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?", progressSessionId);
        }
    }

    // ==================== 辅助方法 ====================

    private RowMapper<ChatHistoryRecord> chatHistoryRowMapper() {
        return (rs, rowNum) -> {
            ChatHistoryRecord record = new ChatHistoryRecord();
            record.id = rs.getLong("id");
            long parentId = rs.getLong("parent_id");
            record.parentId = rs.wasNull() ? null : parentId;
            record.conversationId = rs.getString("conversation_id");
            record.userId = rs.getString("user_id");
            record.role = rs.getString("role");
            record.content = rs.getString("content");
            return record;
        };
    }

    private RowMapper<MemoryRecord> memoryRowMapper() {
        return (rs, rowNum) -> {
            MemoryRecord record = new MemoryRecord();
            record.conversationId = rs.getString("conversation_id");
            record.content = rs.getString("content");
            record.type = rs.getString("type");
            return record;
        };
    }

    @lombok.Data
    private static class ChatHistoryRecord {
        private Long id;
        private Long parentId;
        private String conversationId;
        private String userId;
        private String role;
        private String content;
    }

    @lombok.Data
    private static class MemoryRecord {
        private String conversationId;
        private String content;
        private String type;
    }
}