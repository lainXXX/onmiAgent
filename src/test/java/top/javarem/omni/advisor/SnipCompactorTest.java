package top.javarem.omni.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnipCompactor TDD Tests
 *
 * <p>RED Phase: Tests define expected compression behavior
 * <p>GREEN Phase: Implementation passes tests
 */
class SnipCompactorTest {

    private SnipCompactor snipCompactor;

    @BeforeEach
    void setUp() {
        snipCompactor = new SnipCompactor();
    }

    @Test
    void compact_withNullMessages_returnsEmptyResult() {
        SnipCompactor.SnipResult result = snipCompactor.compact(null);
        assertNotNull(result.getMessages());
        assertTrue(result.getMessages().isEmpty());
        assertEquals(0, result.getTokensFreed());
    }

    @Test
    void compact_withEmptyList_returnsEmptyResult() {
        SnipCompactor.SnipResult result = snipCompactor.compact(List.of());
        assertNotNull(result.getMessages());
        assertTrue(result.getMessages().isEmpty());
        assertEquals(0, result.getTokensFreed());
    }

    @Test
    void compact_withNormalMessages_preservesAll() {
        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi there")
        );

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(2, result.getMessages().size());
        assertEquals(0, result.getTokensFreed());
    }

    @Test
    void compact_withConsecutiveDuplicates_removesDuplicates() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Hello"));
        messages.add(new UserMessage("Hello"));  // duplicate
        messages.add(new AssistantMessage("Hi"));

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(2, result.getMessages().size());
        assertTrue(result.getTokensFreed() > 0);
    }

    @Test
    void compact_withEmptyMessage_removesEmpty() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Hello"));
        messages.add(new UserMessage(""));  // empty
        messages.add(new AssistantMessage("Hi"));

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(2, result.getMessages().size());
    }

    @Test
    void compact_withWhitespaceMessage_removesWhitespace() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Hello"));
        messages.add(new UserMessage("   "));  // whitespace only
        messages.add(new AssistantMessage("Hi"));

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(2, result.getMessages().size());
    }

    @Test
    void compact_withLongToolOutput_truncatesWithSuffix() {
        String longJson = """
                {
                  "result": "very long content that exceeds maximum output length and should be truncated",
                  "status": "ok",
                  "data": ["item1", "item2", "item3", "item4", "item5", "item6"]
                }
                """;
        List<Message> messages = List.of(
                new AssistantMessage(longJson)
        );

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(1, result.getMessages().size());
        String output = result.getMessages().get(0).getText();
        assertTrue(output.contains("[工具输出已截断"), "Should contain truncation notice");
        assertTrue(output.length() < longJson.length(), "Should be shorter than original");
    }

    @Test
    void compact_withShortToolOutput_preservesContent() {
        String shortJson = "{\"status\": \"ok\"}";
        List<Message> messages = List.of(
                new AssistantMessage(shortJson)
        );

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(1, result.getMessages().size());
        assertEquals(shortJson, result.getMessages().get(0).getText());
    }

    @Test
    void compact_withMixedTypes_preservesOrder() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("User 1"));
        messages.add(new AssistantMessage("Assistant 1"));
        messages.add(new UserMessage("User 2"));
        messages.add(new AssistantMessage("Assistant 2"));

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(4, result.getMessages().size());
        assertInstanceOf(UserMessage.class, result.getMessages().get(0));
        assertInstanceOf(AssistantMessage.class, result.getMessages().get(1));
    }

    @Test
    void compact_withAllDuplicates_keepsOnlyFirst() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Same"));
        messages.add(new UserMessage("Same"));
        messages.add(new UserMessage("Same"));

        SnipCompactor.SnipResult result = snipCompactor.compact(messages);

        assertEquals(1, result.getMessages().size());
        assertEquals("Same", result.getMessages().get(0).getText());
    }
}
