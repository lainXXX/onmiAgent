package top.javarem.omni.controller;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for splitByThinkingTags logic
 */
class SplitByThinkingTagsTest {

    // Use Unicode escapes to ensure exact characters
    private static final String OPEN_TAG = "\u300Cthink\u300E";   // <think>
    private static final String CLOSE_TAG = "\u300C/think\u300E";  // </think>

    private record ChunkPart(String content, boolean isThinking) {}

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
                // Found opening tag
                if (nextOpen > searchFrom) {
                    parts.add(new ChunkPart(text.substring(searchFrom, nextOpen), isThinking));
                }
                isThinking = true;
                searchFrom = nextOpen + OPEN_TAG.length();
            } else {
                // Found closing tag at position nextClose
                // Add content before close tag only if it's not empty
                if (nextClose > searchFrom) {
                    parts.add(new ChunkPart(text.substring(searchFrom, nextClose), isThinking));
                } else if (nextClose == searchFrom && isThinking) {
                    // Adjacent close tag when in thinking mode - switch without empty part
                    // This is the case: </think> (immediately after opening tag in same chunk)
                }
                isThinking = false;
                searchFrom = nextClose + CLOSE_TAG.length();
            }
        }

        return parts;
    }

    // ==================== Basic Tests ====================

    @Test
    void test_pureMessage_noTags() {
        List<ChunkPart> parts = splitByThinkingTags("Hello, this is a message.", false);
        assertEquals(1, parts.size());
        assertEquals("Hello, this is a message.", parts.get(0).content());
        assertFalse(parts.get(0).isThinking());
    }

    @Test
    void test_pureThinking_onlyTags() {
        List<ChunkPart> parts = splitByThinkingTags(OPEN_TAG + "This is thinking." + CLOSE_TAG, false);
        assertEquals(1, parts.size());
        assertEquals("This is thinking.", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
    }

    @Test
    void test_thinkingThenMessage() {
        List<ChunkPart> parts = splitByThinkingTags(OPEN_TAG + "Let me think..." + CLOSE_TAG + "Here is my answer.", false);
        assertEquals(2, parts.size());
        assertEquals("Let me think...", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
        assertEquals("Here is my answer.", parts.get(1).content());
        assertFalse(parts.get(1).isThinking());
    }

    @Test
    void test_messageThenThinking() {
        List<ChunkPart> parts = splitByThinkingTags("Before." + OPEN_TAG + "Thinking..." + CLOSE_TAG + "After.", false);
        assertEquals(3, parts.size());
        assertEquals("Before.", parts.get(0).content());
        assertFalse(parts.get(0).isThinking());
        assertEquals("Thinking...", parts.get(1).content());
        assertTrue(parts.get(1).isThinking());
        assertEquals("After.", parts.get(2).content());
        assertFalse(parts.get(2).isThinking());
    }

    @Test
    void test_multipleThinkingBlocks() {
        String text = OPEN_TAG + "First" + CLOSE_TAG + "text1" + OPEN_TAG + "Second" + CLOSE_TAG + "text2";
        List<ChunkPart> parts = splitByThinkingTags(text, false);

        assertEquals(4, parts.size());
        assertEquals("First", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
        assertEquals("text1", parts.get(1).content());
        assertFalse(parts.get(1).isThinking());
        assertEquals("Second", parts.get(2).content());
        assertTrue(parts.get(2).isThinking());
        assertEquals("text2", parts.get(3).content());
        assertFalse(parts.get(3).isThinking());
    }

    // ==================== Edge Cases ====================

    @Test
    void test_consecutiveTags_closeThenOpen() {
        String text = OPEN_TAG + "Think1" + CLOSE_TAG + OPEN_TAG + "Think2" + CLOSE_TAG + "End";
        List<ChunkPart> parts = splitByThinkingTags(text, false);

        assertEquals(3, parts.size());
        assertEquals("Think1", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
        assertEquals("Think2", parts.get(1).content());
        assertTrue(parts.get(1).isThinking());
        assertEquals("End", parts.get(2).content());
        assertFalse(parts.get(2).isThinking());
    }

    @Test
    void test_emptyContentBetweenTags() {
        // When tags are adjacent, there is no empty content part
        // The thinking mode is entered and immediately exited, but no empty part is added
        String text = OPEN_TAG + CLOSE_TAG + "After";
        List<ChunkPart> parts = splitByThinkingTags(text, false);

        // Should be 1 part: "After" with isThinking=false
        assertEquals(1, parts.size());
        assertEquals("After", parts.get(0).content());
        assertFalse(parts.get(0).isThinking());
    }

    @Test
    void test_continuationFromThinking() {
        String text = CLOSE_TAG + "End of thought";
        List<ChunkPart> parts = splitByThinkingTags(text, true);

        assertEquals(1, parts.size());
        assertEquals("End of thought", parts.get(0).content());
        assertFalse(parts.get(0).isThinking());
    }

    @Test
    void test_nullAndEmpty() {
        assertTrue(splitByThinkingTags(null, false).isEmpty());
        assertTrue(splitByThinkingTags("", false).isEmpty());
    }

    // ==================== Newline Handling ====================

    @Test
    void test_newlinesInContent() {
        String text = "Line1\nLine2\n" + OPEN_TAG + "Line3" + CLOSE_TAG + "Line4\nLine5";
        List<ChunkPart> parts = splitByThinkingTags(text, false);

        assertEquals(3, parts.size());
        assertEquals("Line1\nLine2\n", parts.get(0).content());
        assertFalse(parts.get(0).isThinking());
        assertEquals("Line3", parts.get(1).content());
        assertTrue(parts.get(1).isThinking());
        assertEquals("Line4\nLine5", parts.get(2).content());
        assertFalse(parts.get(2).isThinking());
    }

    @Test
    void test_newlineAfterCloseTag() {
        String text = OPEN_TAG + "Think" + CLOSE_TAG + "\nAfter";
        List<ChunkPart> parts = splitByThinkingTags(text, false);

        assertEquals(2, parts.size());
        assertEquals("Think", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
        assertEquals("\nAfter", parts.get(1).content());
        assertFalse(parts.get(1).isThinking());
    }

    @Test
    void test_newlineBeforeCloseTag() {
        String text = OPEN_TAG + "Think\n" + CLOSE_TAG + "After";
        List<ChunkPart> parts = splitByThinkingTags(text, false);

        assertEquals(2, parts.size());
        assertEquals("Think\n", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
        assertEquals("After", parts.get(1).content());
        assertFalse(parts.get(1).isThinking());
    }

    // ==================== Content Stripping ====================

    @Test
    void test_tagsAreStripped() {
        String text = OPEN_TAG + "Think" + CLOSE_TAG + "Normal";
        List<ChunkPart> parts = splitByThinkingTags(text, false);

        for (ChunkPart part : parts) {
            assertFalse(part.content().contains(OPEN_TAG),
                    "Content should not contain opening tag");
            assertFalse(part.content().contains(CLOSE_TAG),
                    "Content should not contain closing tag");
        }
    }

    // ==================== Real-world Chunk Simulation ====================

    @Test
    void test_simulatedStreamingChunks() {
        String chunk1 = OPEN_TAG + "Par";
        String chunk2 = "tial thin";
        String chunk3 = "king..." + CLOSE_TAG + "Then message";

        List<ChunkPart> parts1 = splitByThinkingTags(chunk1, false);
        assertEquals(1, parts1.size());
        assertEquals("Par", parts1.get(0).content());
        assertTrue(parts1.get(0).isThinking());

        List<ChunkPart> parts2 = splitByThinkingTags(chunk2, true);
        assertEquals(1, parts2.size());
        assertEquals("tial thin", parts2.get(0).content());
        assertTrue(parts2.get(0).isThinking());
    }
}
