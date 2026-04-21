package top.javarem.omni.chat.repository;

import org.junit.jupiter.api.Test;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatMemoryRepositoryTest {

    @Test
    void testEntityBuilder() {
        // 测试 Entity 构建正确
        String id = UUID.randomUUID().toString();
        String parentId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        ChatMemory msg = ChatMemory.builder()
                .id(id)
                .parentId(parentId)
                .sessionId(sessionId)
                .userId("test")
                .messageType(MessageType.user)
                .content("Hello")
                .totalTokens(100)
                .build();

        assertEquals(id, msg.getId());
        assertEquals(parentId, msg.getParentId());
        assertEquals(sessionId, msg.getSessionId());
        assertEquals("test", msg.getUserId());
        assertEquals(MessageType.user, msg.getMessageType());
        assertEquals("Hello", msg.getContent());
        assertEquals(100, msg.getTotalTokens());
    }

    @Test
    void testSessionBuilder() {
        String id = UUID.randomUUID().toString();
        String headId = UUID.randomUUID().toString();

        ChatSession session = ChatSession.builder()
                .id(id)
                .userId("test")
                .headId(headId)
                .build();

        assertEquals(id, session.getId());
        assertEquals("test", session.getUserId());
        assertEquals(headId, session.getHeadId());
    }

    @Test
    void testMessageTypeEnum() {
        assertEquals(4, MessageType.values().length);
        assertEquals(MessageType.user, MessageType.valueOf("user"));
        assertEquals(MessageType.assistant, MessageType.valueOf("assistant"));
        assertEquals(MessageType.tool, MessageType.valueOf("tool"));
        assertEquals(MessageType.system, MessageType.valueOf("system"));
    }
}
