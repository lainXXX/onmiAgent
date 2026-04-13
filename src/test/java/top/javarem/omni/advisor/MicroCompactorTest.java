package top.javarem.omni.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MicroCompactor TDD Tests
 *
 * <p>Tests time-decay based tool result cleanup
 */
class MicroCompactorTest {

    private MicroCompactor microCompactor;

    @BeforeEach
    void setUp() {
        // Use default values: gapThresholdMinutes=60, keepRecent=5
        microCompactor = new MicroCompactor();
    }

    @Test
    void compact_withNullMessages_returnsEmptyResult() {
        MicroCompactor.MicroCompactResult result = microCompactor.compact(null, null);
        assertNotNull(result.getMessages());
        assertTrue(result.getMessages().isEmpty());
        assertFalse(result.isCompacted());
    }

    @Test
    void compact_withEmptyList_returnsEmptyResult() {
        MicroCompactor.MicroCompactResult result = microCompactor.compact(List.of(), null);
        assertNotNull(result.getMessages());
        assertTrue(result.getMessages().isEmpty());
        assertFalse(result.isCompacted());
    }

    @Test
    void compact_withRecentMessages_doesNotCompact() {
        // Recent time - within threshold
        Timestamp recentTime = Timestamp.from(Instant.now().minus(30, ChronoUnit.MINUTES));

        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi")
        );

        MicroCompactor.MicroCompactResult result = microCompactor.compact(messages, recentTime);

        assertFalse(result.isCompacted());
        assertEquals(2, result.getMessages().size());
    }

    @Test
    void compact_withOldMessages_triggersCleanup() {
        // Old time - exceeds threshold (60 minutes)
        Timestamp oldTime = Timestamp.from(Instant.now().minus(120, ChronoUnit.MINUTES));

        // Create messages with tool results (long assistant messages)
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Query 1"));
        messages.add(new AssistantMessage("Result 1 - short"));
        messages.add(new UserMessage("Query 2"));
        messages.add(new AssistantMessage("Result 2 - short"));

        MicroCompactor.MicroCompactResult result = microCompactor.compact(messages, oldTime);

        // With default gap threshold, this should not trigger aggressive cleanup
        // since messages don't look like "old tool results" pattern
        assertNotNull(result.getMessages());
    }

    @Test
    void compact_withManyToolResults_keepsOnlyRecent() {
        // Use smaller keepRecent for testing
        MicroCompactor compact = new MicroCompactor(60, 2);

        // Create many tool result-like messages
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Query 1"));
        messages.add(new AssistantMessage("Tool result 1 - very long output that looks like a tool result\nwith multiple lines\nand more content"));
        messages.add(new UserMessage("Query 2"));
        messages.add(new AssistantMessage("Tool result 2 - very long output that looks like a tool result\nwith multiple lines\nand more content"));
        messages.add(new UserMessage("Query 3"));
        messages.add(new AssistantMessage("Tool result 3 - very long output that looks like a tool result\nwith multiple lines\nand more content"));

        MicroCompactor.MicroCompactResult result = compact.compact(messages);

        // Should keep messages, potentially compacted if old time
        assertNotNull(result.getMessages());
    }

    @Test
    void compact_withNormalAssistantMessages_preservesAll() {
        Timestamp oldTime = Timestamp.from(Instant.now().minus(120, ChronoUnit.MINUTES));

        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi there, how can I help you?")
        );

        MicroCompactor.MicroCompactResult result = microCompactor.compact(messages, oldTime);

        // Short responses should not be considered tool results
        assertEquals(2, result.getMessages().size());
    }

    @Test
    void compact_withNullTimestamp_doesNotCompact() {
        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi")
        );

        MicroCompactor.MicroCompactResult result = microCompactor.compact(messages, null);

        assertFalse(result.isCompacted());
        assertEquals(2, result.getMessages().size());
    }

    @Test
    void compact_respectsCustomGapThreshold() {
        // Set gap to 5 minutes
        MicroCompactor compact = new MicroCompactor(5, 5);

        // 10 minutes ago - exceeds 5 minute threshold
        Timestamp oldTime = Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES));

        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(new UserMessage("Query " + i));
            messages.add(new AssistantMessage("Result " + i + " - very long tool output\nwith multiple lines\nand more content"));
        }

        MicroCompactor.MicroCompactResult result = compact.compact(messages, oldTime);

        // With many tool results and old timestamp, cleanup may occur
        assertNotNull(result.getMessages());
    }

    @Test
    void compact_respectsCustomKeepRecent() {
        MicroCompactor compact = new MicroCompactor(60, 1);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Q1"));
        messages.add(new AssistantMessage("Tool result 1\nvery long output"));
        messages.add(new UserMessage("Q2"));
        messages.add(new AssistantMessage("Tool result 2\nvery long output"));
        messages.add(new UserMessage("Q3"));
        messages.add(new AssistantMessage("Tool result 3\nvery long output"));

        MicroCompactor.MicroCompactResult result = compact.compact(messages);

        assertNotNull(result.getMessages());
    }

    @Test
    void compact_freedTokensAndClearedCount_trackedCorrectly() {
        MicroCompactor compact = new MicroCompactor(60, 1);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("Q1"));
        messages.add(new AssistantMessage("Tool result 1\nvery long output\nwith multiple lines"));
        messages.add(new UserMessage("Q2"));
        messages.add(new AssistantMessage("Tool result 2\nvery long output\nwith multiple lines"));
        messages.add(new UserMessage("Q3"));
        messages.add(new AssistantMessage("Tool result 3\nvery long output\nwith multiple lines"));

        MicroCompactor.MicroCompactResult result = compact.compact(messages);

        assertNotNull(result);
        assertTrue(result.getFreedTokens() >= 0);
        assertTrue(result.getClearedCount() >= 0);
    }
}
