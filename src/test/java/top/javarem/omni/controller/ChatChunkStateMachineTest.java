package top.javarem.omni.controller;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SSE ChatChunk state machine logic
 */
class ChatChunkStateMachineTest {

    record ChunkPart(String content, boolean isThinking) {}

    static List<ChunkPart> splitByThinkingTags(String text, boolean currentlyThinking) {
        List<ChunkPart> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) return parts;

        String openTag1 = "<thinking>";
        String closeTag1 = "</thinking>";
        String openTag2 = "[begin_thought]";
        String closeTag2 = "[end_thought]";

        boolean isThinking = currentlyThinking;
        int searchFrom = 0;

        while (searchFrom < text.length()) {
            int nextOpen = -1, openLen = 0;
            int idx1 = text.indexOf(openTag1, searchFrom);
            int idx2 = text.indexOf(openTag2, searchFrom);
            if (idx1 != -1 && (nextOpen == -1 || idx1 < nextOpen)) { nextOpen = idx1; openLen = openTag1.length(); }
            if (idx2 != -1 && (nextOpen == -1 || idx2 < nextOpen)) { nextOpen = idx2; openLen = openTag2.length(); }

            int nextClose = -1, closeLen = 0;
            int cidx1 = text.indexOf(closeTag1, searchFrom);
            int cidx2 = text.indexOf(closeTag2, searchFrom);
            if (cidx1 != -1 && (nextClose == -1 || cidx1 < nextClose)) { nextClose = cidx1; closeLen = closeTag1.length(); }
            if (cidx2 != -1 && (nextClose == -1 || cidx2 < nextClose)) { nextClose = cidx2; closeLen = closeTag2.length(); }

            if (nextOpen == -1 && nextClose == -1) {
                String remaining = text.substring(searchFrom);
                if (!remaining.isEmpty()) parts.add(new ChunkPart(remaining, isThinking));
                break;
            }

            if (nextOpen != -1 && (nextClose == -1 || nextOpen < nextClose)) {
                if (nextOpen > searchFrom) parts.add(new ChunkPart(text.substring(searchFrom, nextOpen), isThinking));
                isThinking = true;
                searchFrom = nextOpen + openLen;
            } else {
                if (nextClose > searchFrom) parts.add(new ChunkPart(text.substring(searchFrom, nextClose), isThinking));
                isThinking = false;
                searchFrom = nextClose + closeLen;
            }
        }
        return parts;
    }

    @Test
    void testMultiRoundThinking() {
        // Simulate the multi-round scenario:
        // Chunk 1: <thinking>xxx</thinking>normal text
        // Chunk 2: <thinking>yyy</thinking>more text after close
        // Chunk 3: pure message content (after second </thinking>)
        // Chunk 4: more message content

        String[] chunks = {
            "<thinking>thinking1</thinking>normal after tag",
            "<thinking>thinking2</thinking>after close",
            "just normal text",
            "## Markdown content"
        };

        // The key: after processing a </thinking>, isThinking becomes false
        // So chunk 2 starts with currentlyThinking=false
        // Chunk 2 has <thinking> at start, so it enters thinking mode

        boolean currentThinking = false;
        String currentRole = "";
        String currentId = "";
        List<String> outputs = new ArrayList<>();

        for (String text : chunks) {
            List<ChunkPart> parts = splitByThinkingTags(text, currentThinking);
            for (ChunkPart part : parts) {
                if (part.content() == null || part.content().isEmpty()) continue;
                String newRole = part.isThinking() ? "thought" : "message";
                if (!newRole.equals(currentRole)) {
                    currentRole = newRole;
                    currentId = UUID.randomUUID().toString().substring(0, 4);
                    outputs.add("ID_CHANGED:" + currentRole + " id:" + currentId);
                }
                currentThinking = part.isThinking();
                outputs.add("event:" + currentRole + " id:" + currentId + " content:" + part.content());
            }
        }

        System.out.println("=== Multi-round test outputs ===");
        outputs.forEach(System.out::println);

        // Verify the sequence:
        // 1. First chunk: enters thinking, exits, normal text is message
        // 2. Second chunk: re-enters thinking (new ID), exits, after close is message
        // 3. Third chunk: no tags, should be message with NEW ID
        // 4. Fourth chunk: no tags, should be message with SAME ID (continuation)
        assertTrue(outputs.get(0).contains("ID_CHANGED:thought"), "First should be thought");
        assertTrue(outputs.get(1).contains("event:thought"), "Should output thought content");
        assertTrue(outputs.get(2).contains("ID_CHANGED:message"), "After </thinking>, should switch to message");
        assertTrue(outputs.get(3).contains("event:message"), "Should output normal text as message");
        // Third chunk should start with new ID since role changes
        assertTrue(outputs.get(4).contains("ID_CHANGED:thought"), "Third chunk has <thinking> so should be thought");
    }

    @Test
    void testPureMessage() {
        // Test pure message content (no tags)
        List<ChunkPart> parts = splitByThinkingTags("## Hello World", false);
        assertEquals(1, parts.size());
        assertEquals("## Hello World", parts.get(0).content());
        assertFalse(parts.get(0).isThinking());
    }

    @Test
    void testThinkingOnly() {
        // Test thinking only content
        List<ChunkPart> parts = splitByThinkingTags("<thinking>thought content</thinking>", true);
        assertEquals(1, parts.size());
        assertEquals("thought content", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
    }

    @Test
    void testThinkingTagStripped() {
        // Verify tags are stripped from content
        List<ChunkPart> parts = splitByThinkingTags("<thinking>content</thinking>more", false);
        assertEquals(2, parts.size());
        assertEquals("content", parts.get(0).content());
        assertTrue(parts.get(0).isThinking());
        assertEquals("more", parts.get(1).content());
        assertFalse(parts.get(1).isThinking());
    }
}
