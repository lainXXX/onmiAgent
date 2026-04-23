package top.javarem.omni.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.repository.ChatMemoryRepository;
import top.javarem.omni.chat.repository.ChatSessionRepository;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Override
    public ChatMemory saveMessage(String sessionId, MessageType type, String content,
                                  String toolCallId, String toolName,
                                  Integer promptTokens, Integer completionTokens) {
        // 获取当前链头作为 parent_id
        ChatSession session = chatSessionRepository.findById(sessionId);
        String parentId = session != null ? session.getHeadId() : null;

        // 计算 total_tokens
        Integer totalTokens = (promptTokens != null ? promptTokens : 0) +
                             (completionTokens != null ? completionTokens : 0);

        // 构建消息
        ChatMemory chatMemory = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(parentId)
                .sessionId(sessionId)
                .messageType(type)
                .content(content)
                .toolCallId(toolCallId)
                .toolName(toolName)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .build();

        // 保存
        chatMemoryRepository.save(chatMemory);

        // 更新 session head
        if (session != null) {
            chatSessionRepository.updateHead(sessionId, chatMemory.getId());
        }

        log.debug("[ChatMemory] 保存消息 id={}, type={}, sessionId={}",
                chatMemory.getId(), type, sessionId);

        return chatMemory;
    }

    @Override
    public List<ChatMemory> getContext(String sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return List.of();
        }
        return chatMemoryRepository.findContextBySessionId(sessionId, session.getHeadId());
    }

    @Override
    public ChatMemory getCurrentHead(String sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return null;
        }
        return chatMemoryRepository.findById(session.getHeadId());
    }

    @Override
    public int sumTokens(String sessionId) {
        return chatMemoryRepository.sumTokensBySessionId(sessionId);
    }

    @Override
    public void undo(String sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return;
        }

        ChatMemory currentHead = chatMemoryRepository.findById(session.getHeadId());
        if (currentHead != null && currentHead.getParentId() != null) {
            chatSessionRepository.updateHead(sessionId, currentHead.getParentId());
            log.info("[ChatMemory] Undo 完成, sessionId={}, newHeadId={}",
                    sessionId, currentHead.getParentId());
        }
    }
}