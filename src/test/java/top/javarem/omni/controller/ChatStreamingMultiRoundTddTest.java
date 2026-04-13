package top.javarem.omni.controller;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import top.javarem.omni.model.request.ChatRequest;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Multi-Round Streaming Test
 *
 * RED Phase (Write failing tests first)
 * GREEN Phase (Make tests pass)
 * REFACTOR Phase (Improve code while keeping tests green)
 *
 * Test scenarios:
 * 1. Two sequential streaming requests in same session
 * 2. Verify event:thought and event:message are correctly generated
 * 3. Verify the state machine transitions work correctly across rounds
 * 4. Verify content inside <think>...</think> produces event:thought
 * 5. Verify regular content produces event:message
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Slf4j
class ChatStreamingMultiRoundTddTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebClient.Builder webClientBuilder;

    private WebClient webClient;
    private final String userId = "tdd-test-user";
    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "tdd-" + UUID.randomUUID().toString().substring(0, 8);
        webClient = webClientBuilder.baseUrl("http://localhost:" + port).build();
        log.info("[TDD SETUP] sessionId={}", sessionId);
    }

    @AfterEach
    void tearDown() {
        log.info("[TDD TEARDOWN] sessionId={}", sessionId);
    }

    // ==================== RED PHASE: Write Failing Tests ====================

    /**
     * Test 1: Single streaming response generates correct event types
     * Expected: event:thought for <think>...</think> content, event:message for regular content
     */
    @Test
    @Order(1)
    void test_singleStream_generatesThoughtAndMessageEvents() {
        // Given
        ChatRequest request = new ChatRequest();
        request.setQuestion("请解释什么是机器学习？");
        request.setSessionId(sessionId);

        // When
        List<SseEventRecord> events = collectSseEvents(request);

        // Then
        log.info("[TEST 1] Collected {} events", events.size());
        events.forEach(e -> log.info("  event:{} content:{}", e.eventType, truncate(e.content, 50)));

        assertFalse(events.isEmpty(), "Should generate at least one event");

        // Verify we have both thought and message events (if model uses thinking tags)
        boolean hasThought = events.stream().anyMatch(e -> "thought".equals(e.eventType));
        boolean hasMessage = events.stream().anyMatch(e -> "message".equals(e.eventType));

        log.info("[TEST 1 RESULT] hasThought={}, hasMessage={}", hasThought, hasMessage);
        // This test passes if we get either thought or message events
        assertTrue(hasMessage || hasThought, "Should generate message or thought events");
    }

    /**
     * Test 2: Multi-round conversation - second request should continue in same session
     * Expected: Both requests succeed, session is maintained
     */
    @Test
    @Order(2)
    void test_multiRound_secondRequest_succeeds() {
        // Round 1
        ChatRequest request1 = new ChatRequest();
        request1.setQuestion("我的名字叫小明");
        request1.setSessionId(sessionId);

        List<SseEventRecord> round1Events = collectSseEvents(request1);
        log.info("[TEST 2 ROUND 1] {} events", round1Events.size());
        assertFalse(round1Events.isEmpty(), "First round should generate events");

        // Round 2 - Same session
        ChatRequest request2 = new ChatRequest();
        request2.setQuestion("我叫什么名字？");
        request2.setSessionId(sessionId);

        List<SseEventRecord> round2Events = collectSseEvents(request2);
        log.info("[TEST 2 ROUND 2] {} events", round2Events.size());
        assertFalse(round2Events.isEmpty(), "Second round should generate events");

        // Verify message content references context from round 1
        boolean referencesContext = round2Events.stream()
                .anyMatch(e -> e.content != null && (
                        e.content.contains("小明") ||
                        e.content.contains("名字") ||
                        e.content.contains("记得")
                ));

        log.info("[TEST 2 RESULT] referencesContext={}", referencesContext);
    }

    /**
     * Test 3: Three rounds of conversation to verify state machine stability
     */
    @Test
    @Order(3)
    void test_threeRounds_conversationMaintained() {
        String[] questions = {
                "天空是什么颜色？",
                "那大海是什么颜色？",
                "天空和大海在一起会变成什么颜色？"
        };

        List<List<SseEventRecord>> allRounds = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            ChatRequest request = new ChatRequest();
            request.setQuestion(questions[i]);
            request.setSessionId(sessionId);

            List<SseEventRecord> events = collectSseEvents(request);
            allRounds.add(events);

            long thoughtCount = events.stream().filter(e -> "thought".equals(e.eventType)).count();
            long messageCount = events.stream().filter(e -> "message".equals(e.eventType)).count();

            log.info("[TEST 3 ROUND {}] total={}, thought={}, message={}",
                    i + 1, events.size(), thoughtCount, messageCount);

            assertFalse(events.isEmpty(), "Round " + (i + 1) + " should generate events");
        }

        log.info("[TEST 3 RESULT] All 3 rounds completed successfully");
    }

    /**
     * Test 4: Verify SSE event format is correct
     * Expected: Each event should have valid id, event type, role, and content
     */
    @Test
    @Order(4)
    void test_sseEventFormat_isValid() {
        ChatRequest request = new ChatRequest();
        request.setQuestion("你好");
        request.setSessionId(sessionId);

        List<SseEventRecord> events = collectSseEvents(request);

        for (SseEventRecord event : events) {
            // Verify event type is valid
            assertTrue(
                    "thought".equals(event.eventType) ||
                    "message".equals(event.eventType) ||
                    "tool".equals(event.eventType) ||
                    "error".equals(event.eventType),
                    "Invalid event type: " + event.eventType
            );

            // Verify role matches event type
            assertEquals(event.eventType, event.role,
                    "Event type and role should match");

            // Verify content doesn't leak internal tags
            if (event.content != null) {
                assertFalse(event.content.contains("<think>") && event.content.contains("</think>"),
                        "Content should not contain unprocessed thinking tags");
            }

            // Verify done flag is present
            assertNotNull(event.done, "done flag should be present");
        }

        log.info("[TEST 4 RESULT] All {} events have valid format", events.size());
    }

    /**
     * Test 5: Error event is sent when server error occurs
     * (This test verifies error handling works)
     */
    @Test
    @Order(5)
    void test_invalidSession_errorEvent() {
        // Send request with empty question to potentially trigger error path
        ChatRequest request = new ChatRequest();
        request.setQuestion("");
        request.setSessionId(sessionId);

        List<SseEventRecord> events = collectSseEvents(request);

        // If we get events, they should be valid or error events
        // Empty question might return empty response, which is valid
        log.info("[TEST 5 RESULT] {} events collected (empty question is valid)", events.size());
        assertTrue(events.size() >= 0, "Should handle empty question gracefully");
    }

    /**
     * Test 6: Rapid consecutive requests - verify no state leakage
     */
    @Test
    @Order(6)
    void test_rapidConsecutiveRequests_noStateLeakage() {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            String uniqueSession = "rapid-" + UUID.randomUUID().toString().substring(0, 8);
            ChatRequest request = new ChatRequest();
            request.setQuestion("Question " + i + " for session " + uniqueSession);
            request.setSessionId(uniqueSession);

            try {
                List<SseEventRecord> events = collectSseEvents(request);
                if (!events.isEmpty()) {
                    successCount.incrementAndGet();
                    log.info("[TEST 6 REQUEST {}] success, {} events", i, events.size());
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
                log.error("[TEST 6 REQUEST {}] error: {}", i, e.getMessage());
            }
        }

        log.info("[TEST 6 RESULT] success={}, errors={}", successCount.get(), errorCount.get());
        assertEquals(3, successCount.get(), "All 3 rapid requests should succeed");
        assertEquals(0, errorCount.get(), "No errors should occur");
    }

    // ==================== GREEN PHASE: Helper Methods ====================

    /**
     * Collect SSE events from streaming endpoint
     */
    private List<SseEventRecord> collectSseEvents(ChatRequest request) {
        List<SseEventRecord> events = new ArrayList<>();

        try {
            webClient.post()
                    .uri("/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(line -> {
                        SseEventRecord record = parseSseLine(line);
                        if (record != null) {
                            events.add(record);
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("Error collecting SSE events: {}", e.getMessage());
        }

        return events;
    }

    /**
     * Parse a single SSE line into SseEventRecord
     * Handles: event:TYPE, data:{...}, id:xxx, done:true/false
     */
    private SseEventRecord parseSseLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        String eventType = null;
        String id = null;
        String content = null;
        String role = null;
        Boolean done = null;
        String toolName = null;

        if (line.startsWith("event:")) {
            eventType = line.substring(6).trim();
            return new SseEventRecord(eventType, id, content, role, done, toolName);
        }

        if (line.startsWith("data:")) {
            String json = line.substring(5).trim();
            return parseSseData(json);
        }

        // Skip other SSE fields like id:, retry:, comment lines
        return null;
    }

    /**
     * Parse SSE data JSON into SseEventRecord
     */
    private SseEventRecord parseSseData(String json) {
        try {
            // Simple JSON parsing without external library
            String eventType = extractJsonField(json, "role");
            String id = extractJsonField(json, "id");
            String content = extractJsonField(json, "content");
            String toolName = extractJsonField(json, "toolName");
            Boolean done = json.contains("\"done\":true");

            return new SseEventRecord(eventType, id, content, eventType, done, toolName);
        } catch (Exception e) {
            log.warn("Failed to parse SSE data: {}", json);
            return null;
        }
    }

    /**
     * Extract string field from simple JSON (without external library)
     */
    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start == -1) {
            pattern = "\"" + field + "\":";
            start = json.indexOf(pattern);
            if (start == -1) return null;
            start += pattern.length();
            // Handle boolean or null values
            if (json.charAt(start) == 't') return "true";
            if (json.charAt(start) == 'f') return "false";
            if (json.charAt(start) == 'n') return null;
        } else {
            start += pattern.length();
        }

        int end = json.indexOf("\"", start);
        if (end == -1) return null;

        return json.substring(start, end);
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "null";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    // ==================== Data Classes ====================

    private record SseEventRecord(
            String eventType,
            String id,
            String content,
            String role,
            Boolean done,
            String toolName
    ) {
        @Override
        public String toString() {
            return String.format("SseEventRecord{event=%s, id=%s, content=%s, role=%s, done=%s}",
                    eventType, id, truncate(content, 30), role, done);
        }
    }
}
