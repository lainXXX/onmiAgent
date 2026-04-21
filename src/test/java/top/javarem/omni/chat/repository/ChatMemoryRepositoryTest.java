package top.javarem.omni.chat.repository;

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
class ChatMemoryRepositoryTest {

    @Autowired
    private ChatMemoryRepository repository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Test
    void testSaveAndFind() {
        String sessionId = UUID.randomUUID().toString();

        // 创建会话
        sessionRepository.save(ChatSession.builder()
                .id(sessionId)
                .userId("test")
                .headId(null)
                .build());

        // 保存消息1 (根节点)
        ChatMemory msg1 = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(null)
                .sessionId(sessionId)
                .userId("test")
                .messageType(MessageType.user)
                .content("Hello")
                .totalTokens(10)
                .build();
        repository.save(msg1);
        sessionRepository.updateHead(sessionId, msg1.getId());

        // 保存消息2
        ChatMemory msg2 = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(msg1.getId())
                .sessionId(sessionId)
                .userId("test")
                .messageType(MessageType.assistant)
                .content("Hi there")
                .totalTokens(20)
                .build();
        repository.save(msg2);
        sessionRepository.updateHead(sessionId, msg2.getId());

        // 验证递归查询
        List<ChatMemory> context = repository.findContextBySessionId(sessionId, msg2.getId());
        assertEquals(2, context.size());
        assertEquals("Hello", context.get(0).getContent());
        assertEquals("Hi there", context.get(1).getContent());
    }

    @Test
    void testSumTokens() {
        String sessionId = UUID.randomUUID().toString();

        sessionRepository.save(ChatSession.builder()
                .id(sessionId)
                .userId("test")
                .headId(null)
                .build());

        ChatMemory msg1 = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(null)
                .sessionId(sessionId)
                .userId("test")
                .messageType(MessageType.user)
                .totalTokens(100)
                .build();
        repository.save(msg1);
        sessionRepository.updateHead(sessionId, msg1.getId());

        int sum = repository.sumTokensBySessionId(sessionId);
        assertEquals(100, sum);
    }
}