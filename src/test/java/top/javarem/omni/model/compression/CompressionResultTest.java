package top.javarem.omni.model.compression;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CompressionResult TDD Tests
 *
 * <p>Tests factory methods and data integrity
 */
class CompressionResultTest {

    @Test
    void notCompacted_createsCorrectResult() {
        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Hi")
        );
        long tokenCount = 100L;

        CompressionResult result = CompressionResult.notCompacted(messages, tokenCount);

        assertFalse(result.isCompacted());
        assertEquals(messages, result.getMessages());
        assertEquals(tokenCount, result.getPreCompressionTokens());
        assertEquals(tokenCount, result.getPostCompressionTokens());
        assertEquals(0, result.getFreedTokens());
        assertTrue(result.isSuccess());
        assertNull(result.getFailureReason());
        assertNull(result.getSummaryMessage());
        assertNull(result.getSummaryContent());
    }

    @Test
    void success_createsCorrectResult() {
        List<Message> messages = List.of(
                new UserMessage("Hello"),
                new AssistantMessage("Summary content")
        );
        long preTokens = 1000L;
        long postTokens = 200L;
        Message summaryMsg = new UserMessage("Compressed summary");
        String summaryContent = "This is the summary";

        CompressionResult result = CompressionResult.success(
                messages, preTokens, postTokens, summaryMsg, summaryContent);

        assertTrue(result.isCompacted());
        assertEquals(messages, result.getMessages());
        assertEquals(preTokens, result.getPreCompressionTokens());
        assertEquals(postTokens, result.getPostCompressionTokens());
        assertEquals(preTokens - postTokens, result.getFreedTokens());
        assertTrue(result.isSuccess());
        assertEquals(summaryMsg, result.getSummaryMessage());
        assertEquals(summaryContent, result.getSummaryContent());
        assertNull(result.getFailureReason());
    }

    @Test
    void failure_createsCorrectResult() {
        String reason = "Token estimation failed";

        CompressionResult result = CompressionResult.failure(reason);

        assertFalse(result.isSuccess());
        assertFalse(result.isCompacted());
        assertEquals(reason, result.getFailureReason());
        assertNull(result.getMessages());
        assertNull(result.getSummaryMessage());
    }

    @Test
    void success_withZeroFreedTokens_calculatesCorrectly() {
        List<Message> messages = List.of(new UserMessage("Hello"));
        long preTokens = 100L;
        long postTokens = 100L;
        Message summaryMsg = new UserMessage("Summary");
        String summaryContent = "Summary";

        CompressionResult result = CompressionResult.success(
                messages, preTokens, postTokens, summaryMsg, summaryContent);

        assertEquals(0, result.getFreedTokens());
    }

    @Test
    void notCompacted_withEmptyList_handlesCorrectly() {
        CompressionResult result = CompressionResult.notCompacted(List.of(), 0L);

        assertNotNull(result.getMessages());
        assertTrue(result.getMessages().isEmpty());
        assertEquals(0, result.getPreCompressionTokens());
    }
}
