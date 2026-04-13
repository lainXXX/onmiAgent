package top.javarem.omni.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import top.javarem.omni.model.ChatChunk;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SSE event types (event:thought, event:message)
 * Verifies that content inside <think>...</think> tags produces event:thought
 * and regular content produces event:message
 */
class ChatStreamingSseEventTest {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private record ChunkPart(String content, boolean isThinking) {}

    /**
     * Simulates the SSE event generation logic from ChatController
     */
    private List<ServerSentEvent<ChatChunk>> generateSseEvents(String text, boolean hasToolCalls) {
        List<ServerSentEvent<ChatChunk>> events = new ArrayList<>();
        AtomicReference<String> currentRole = new AtomicReference<>("");
        AtomicReference<String> currentId = new AtomicReference<>(UUID.randomUUID().toString());

        // 1. Process tool calls
        if (hasToolCalls) {
            if (!"tool".equals(currentRole.get())) {
                currentRole.set("tool");
                currentId.set(UUID.randomUUID().toString());
            }
            ChatChunk chunk = ChatChunk.builder()
                    .id(currentId.get())
                    .toolName("test_tool")
                    .role("tool")
                    .done(false)
                    .build();
            events.add(ServerSentEvent.<ChatChunk>builder()
                    .event("tool")
                    .data(chunk)
                    .build());
        }

        // 2. Process text content
        if (text != null && !text.isEmpty()) {
            text = filterToolCallJson(text);

            boolean hasOpenTag = text.contains(OPEN_TAG);
            boolean hasCloseTag = text.contains(CLOSE_TAG);
            boolean wasThinking = "thought".equals(currentRole.get());

            if (hasCloseTag && wasThinking) {
                currentRole.set("message");
                currentId.set(UUID.randomUUID().toString());
            }

            if (hasOpenTag && !wasThinking && !"thought".equals(currentRole.get())) {
                currentRole.set("thought");
                currentId.set(UUID.randomUUID().toString());
            }

            List<ChunkPart> parts = splitByThinkingTags(text, "thought".equals(currentRole.get()));

            for (ChunkPart part : parts) {
                if (part.content == null || part.content.isEmpty()) continue;

                String newRole = part.isThinking ? "thought" : "message";

                if (!newRole.equals(currentRole.get())) {
                    currentRole.set(newRole);
                    currentId.set(UUID.randomUUID().toString());
                }

                ChatChunk chunk = ChatChunk.builder()
                        .id(currentId.get())
                        .content(part.content)
                        .role(newRole)
                        .done(false)
                        .build();
                events.add(ServerSentEvent.<ChatChunk>builder()
                        .event(newRole)
                        .data(chunk)
                        .build());
            }
        }

        return events;
    }

    private List<ChunkPart> splitByThinkingTags(String text, boolean currentlyThinking) {
        List<ChunkPart> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) return parts;

        boolean isThinking = currentlyThinking;
        int searchFrom = 0;

        while (searchFrom < text.length()) {
            int nextOpen = text.indexOf(OPEN_TAG, searchFrom);
            int nextClose = text.indexOf(CLOSE_TAG, searchFrom);

            if (nextOpen == -1 && nextClose == -1) {
                String remaining = text.substring(searchFrom);
                if (!remaining.isEmpty()) {
                    parts.add(new ChunkPart(remaining, isThinking));
                }
                break;
            }

            if (nextOpen != -1 && (nextClose == -1 || nextOpen < nextClose)) {
                if (nextOpen > searchFrom) {
                    parts.add(new ChunkPart(text.substring(searchFrom, nextOpen), isThinking));
                }
                isThinking = true;
                searchFrom = nextOpen + OPEN_TAG.length();
            } else {
                if (nextClose > searchFrom) {
                    parts.add(new ChunkPart(text.substring(searchFrom, nextClose), isThinking));
                }
                isThinking = false;
                searchFrom = nextClose + CLOSE_TAG.length();
            }
        }

        return parts;
    }

    private String filterToolCallJson(String text) {
        if (text == null || text.isBlank()) return text;
        text = text.replaceAll("\\{\\s*\"command\"\\s*:\\s*\"[^\"]*\"\\s*,[^}]*\\}", "");
        text = text.replaceAll("\\{\\s*\"name\"\\s*:\\s*\"[^\"]*\"\\s*,[^}]*\\}", "");
        return text.trim();
    }

    @Test
    void testPureMessage_noTags_returnsMessageEvent() {
        String text = "Hello, this is a regular message without any thinking tags.";
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents(text, false);

        assertFalse(events.isEmpty(), "Should generate at least one event");
        for (ServerSentEvent<ChatChunk> event : events) {
            assertEquals("message", event.event(), "Pure message should have event:message");
            assertNotNull(event.data());
            assertEquals("message", event.data().getRole());
            assertFalse(event.data().getContent().isEmpty());
        }
        System.out.println("testPureMessage: " + events.size() + " event(s) with event:message");
    }

    @Test
    void testPureThought_onlyThinkingTags_returnsThoughtEvent() {
        String text = "<think>This is my thought process...</thinking>";
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents(text, false);

        assertFalse(events.isEmpty(), "Should generate at least one event");
        for (ServerSentEvent<ChatChunk> event : events) {
            assertEquals("thought", event.event(), "Thinking content should have event:thought");
            assertEquals("thought", event.data().getRole());
            // Tags should be stripped
            assertFalse(event.data().getContent().contains("<think>"));
            assertFalse(event.data().getContent().contains("</think>"));
        }
        System.out.println("testPureThought: " + events.size() + " event(s) with event:thought");
    }

    @Test
    void testMixedContent_thinkingThenMessage_generatesBothEventTypes() {
        String text = "<think>Let me think about this...</thinking>Here's my response.";
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents(text, false);

        assertTrue(events.size() >= 2, "Should generate at least 2 events");

        boolean hasThought = false, hasMessage = false;
        for (ServerSentEvent<ChatChunk> event : events) {
            if ("thought".equals(event.event())) hasThought = true;
            if ("message".equals(event.event())) hasMessage = true;
        }

        assertTrue(hasThought, "Should have at least one event:thought");
        assertTrue(hasMessage, "Should have at least one event:message");

        System.out.println("testMixedContent: " + events.size() + " events - hasThought=" + hasThought + ", hasMessage=" + hasMessage);
        events.forEach(e -> System.out.println("  event:" + e.event() + " content:" + e.data().getContent()));
    }

    @Test
    void testMultipleThinkingBlocks_generatesCorrectSequence() {
        String text = "<think>First thought...</thinking>Message after first." +
                      "<think>Second thought...</thinking>Message after second.";
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents(text, false);

        System.out.println("testMultipleThinkingBlocks: " + events.size() + " events");
        events.forEach(e -> System.out.println("  event:" + e.event() + " content:" + e.data().getContent()));

        // Verify the sequence: thought -> message -> thought -> message
        assertEquals("thought", events.get(0).event());
        assertEquals("message", events.get(1).event());
        assertEquals("thought", events.get(2).event());
        assertEquals("message", events.get(3).event());
    }

    @Test
    void testToolCallEvent_hasToolEventType() {
        String text = "Regular message";
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents(text, true);

        assertTrue(events.size() >= 1, "Should have at least the tool event");
        assertEquals("tool", events.get(0).event(), "Tool call should have event:tool");
        assertEquals("tool", events.get(0).data().getRole());
        System.out.println("testToolCallEvent: first event is event:tool");
    }

    @Test
    void testContentOutsideTags_doesNotContainTags() {
        String text = "<think>Thinking content</thinking> and then normal text.";
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents(text, false);

        for (ServerSentEvent<ChatChunk> event : events) {
            String content = event.data().getContent();
            assertFalse(content.contains("<think>"), "Content should not contain opening tag");
            assertFalse(content.contains("</think>"), "Content should not contain closing tag");
        }
        System.out.println("testContentOutsideTags: All tags properly stripped");
    }

    @Test
    void testEmptyText_producesNoEvents() {
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents("", false);
        assertTrue(events.isEmpty(), "Empty text should produce no events");
        System.out.println("testEmptyText: No events generated for empty text");
    }

    @Test
    void testNullText_producesNoEvents() {
        List<ServerSentEvent<ChatChunk>> events = generateSseEvents(null, false);
        assertTrue(events.isEmpty(), "Null text should produce no events");
        System.out.println("testNullText: No events generated for null text");
    }
}
