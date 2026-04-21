package top.javarem.omni.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ChatMemoryServiceTest {

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void testSaveMessage() {
        String userId = "test-user";
        ChatSession session = chatSessionService.createSession(userId);
        String sessionId = session.getId();

        ChatMemory msg = chatMemoryService.saveMessage(
                sessionId, MessageType.user, "Hello", null, null, 10, 5);

        assertNotNull(msg.getId());
        assertNull(msg.getParentId());  // 第一条消息无 parent
        assertEquals("Hello", msg.getContent());
        assertEquals(15, msg.getTotalTokens());

        // 验证 HEAD 更新
        ChatMemory head = chatMemoryService.getCurrentHead(sessionId);
        assertEquals(msg.getId(), head.getId());
    }

    @Test
    void testUndo() {
        String userId = "test-user";
        ChatSession session = chatSessionService.createSession(userId);
        String sessionId = session.getId();

        // 保存两条消息
        ChatMemory msg1 = chatMemoryService.saveMessage(
                sessionId, MessageType.user, "Hello", null, null, 10, 5);
        ChatMemory msg2 = chatMemoryService.saveMessage(
                sessionId, MessageType.assistant, "Hi", null, null, 20, 10);

        // Undo
        chatMemoryService.undo(sessionId);

        // 验证 HEAD 回退到 msg1
        ChatMemory head = chatMemoryService.getCurrentHead(sessionId);
        assertEquals(msg1.getId(), head.getId());
    }

    @Test
    void testGetContext() {
        String userId = "test-user";
        ChatSession session = chatSessionService.createSession(userId);
        String sessionId = session.getId();

        chatMemoryService.saveMessage(sessionId, MessageType.user, "Hello", null, null, 10, 5);
        chatMemoryService.saveMessage(sessionId, MessageType.assistant, "Hi", null, null, 20, 10);

        List<ChatMemory> context = chatMemoryService.getContext(sessionId);
        assertEquals(2, context.size());
        assertEquals("Hello", context.get(0).getContent());
        assertEquals("Hi", context.get(1).getContent());
    }
}