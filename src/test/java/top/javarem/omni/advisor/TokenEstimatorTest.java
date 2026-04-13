package top.javarem.omni.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenEstimator TDD Tests
 *
 * <p>RED Phase: Tests written first to define expected behavior
 * <p>GREEN Phase: Minimal implementation to pass
 */
class TokenEstimatorTest {

    @Test
    void estimateTokens_withEmptyString_returnsZero() {
        assertEquals(0, TokenEstimator.estimateTokens(""));
    }

    @Test
    void estimateTokens_withNull_returnsZero() {
        assertEquals(0, TokenEstimator.estimateTokens(null));
    }

    @Test
    void estimateTokens_withEnglishText_returnsPositiveCount() {
        int tokens = TokenEstimator.estimateTokens("Hello world");
        assertTrue(tokens > 0, "English text should produce tokens");
    }

    @Test
    void estimateTokens_withChineseText_returnsPositiveCount() {
        int tokens = TokenEstimator.estimateTokens("你好世界");
        assertTrue(tokens > 0, "Chinese text should produce tokens");
    }

    @Test
    void estimateTokens_longerText_hasMoreTokens() {
        int shortText = TokenEstimator.estimateTokens("Hello");
        int longText = TokenEstimator.estimateTokens("Hello world this is a longer sentence");
        assertTrue(longText > shortText, "Longer text should have more tokens");
    }

    @Test
    void estimateMessage_withUserMessage_returnsPositiveCount() {
        Message msg = new UserMessage("Hello world");
        int tokens = TokenEstimator.estimateMessage(msg);
        assertTrue(tokens > 0);
    }

    @Test
    void estimateMessage_withNullMessage_returnsZero() {
        assertEquals(0, TokenEstimator.estimateMessage(null));
    }

    @Test
    void estimateMessages_withEmptyList_returnsZero() {
        assertEquals(0, TokenEstimator.estimateMessages(List.of()));
    }

    @Test
    void estimateMessages_withNullList_returnsZero() {
        assertEquals(0, TokenEstimator.estimateMessages(null));
    }

    @Test
    void estimateMessages_withMultipleMessages_sumsAllTokens() {
        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi there"),
                new UserMessage("How are you?")
        );
        long total = TokenEstimator.estimateMessages(messages);
        assertTrue(total > 0, "Multiple messages should sum to positive token count");
    }

    @Test
    void estimateMessages_withMixedContent_handlesCorrectly() {
        List<Message> messages = List.of(
                new UserMessage("Hello world"),
                new AssistantMessage("{\"result\": \"very long content...\"}"),
                new UserMessage("")
        );
        long total = TokenEstimator.estimateMessages(messages);
        // Should not count empty message but should count others
        assertTrue(total > 0);
    }
}
