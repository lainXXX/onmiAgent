package top.javarem.omni.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.repository.ChatMemoryRepository;
import top.javarem.omni.chat.repository.ChatSessionRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMemoryServiceTest {

    @Mock
    private ChatMemoryRepository memoryRepository;

    @Mock
    private ChatSessionRepository sessionRepository;

    private ChatMemoryServiceImpl chatMemoryService;

    @BeforeEach
    void setUp() {
        chatMemoryService = new ChatMemoryServiceImpl(memoryRepository, sessionRepository);
    }

    @Test
    void testSaveMessage() {
        String sessionId = UUID.randomUUID().toString();
        String userId = "test-user";

        // Mock: session exists with headId = null
        ChatSession session = ChatSession.builder()
                .id(sessionId)
                .userId(userId)
                .headId(null)
                .build();
        when(sessionRepository.findById(sessionId)).thenReturn(session);

        // 执行
        ChatMemory result = chatMemoryService.saveMessage(
                sessionId, MessageType.user, "Hello", null, null, 10, 5);

        // 验证
        assertNotNull(result.getId());
        assertNull(result.getParentId());
        assertEquals(sessionId, result.getSessionId());
        assertNull(result.getUserId()); // userId 不保存在 ChatMemory 中，只在 ChatSession 中
        assertEquals(MessageType.user, result.getMessageType());
        assertEquals("Hello", result.getContent());
        assertEquals(15, result.getTotalTokens());

        // 验证 repository 调用
        verify(memoryRepository).save(any(ChatMemory.class));
        verify(sessionRepository).updateHead(eq(sessionId), anyString());
    }

    @Test
    void testUndo() {
        String sessionId = UUID.randomUUID().toString();
        String parentId = UUID.randomUUID().toString();

        // Mock: session exists with current head
        ChatSession session = ChatSession.builder()
                .id(sessionId)
                .userId("test")
                .headId(parentId)
                .build();
        when(sessionRepository.findById(sessionId)).thenReturn(session);

        // Mock: current head has a parent
        ChatMemory currentHead = ChatMemory.builder()
                .id(parentId)
                .parentId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .build();
        when(memoryRepository.findById(parentId)).thenReturn(currentHead);

        // 执行
        chatMemoryService.undo(sessionId);

        // 验证 updateHead 被调用
        verify(sessionRepository).updateHead(eq(sessionId), eq(currentHead.getParentId()));
    }

    @Test
    void testGetContext() {
        String sessionId = UUID.randomUUID().toString();
        String headId = UUID.randomUUID().toString();

        // Mock: session exists
        ChatSession session = ChatSession.builder()
                .id(sessionId)
                .userId("test")
                .headId(headId)
                .build();
        when(sessionRepository.findById(sessionId)).thenReturn(session);

        // Mock: return context
        List<ChatMemory> context = List.of(
                ChatMemory.builder().id("1").content("Hello").build(),
                ChatMemory.builder().id("2").content("Hi").build()
        );
        when(memoryRepository.findContextBySessionId(sessionId, headId)).thenReturn(context);

        // 执行
        List<ChatMemory> result = chatMemoryService.getContext(sessionId);

        // 验证
        assertEquals(2, result.size());
        assertEquals("Hello", result.get(0).getContent());
        assertEquals("Hi", result.get(1).getContent());
    }

    @Test
    void testSumTokens() {
        String sessionId = UUID.randomUUID().toString();
        when(memoryRepository.sumTokensBySessionId(sessionId)).thenReturn(500);

        int sum = chatMemoryService.sumTokens(sessionId);

        assertEquals(500, sum);
    }
}
