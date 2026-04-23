package top.javarem.omni.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.repository.ChatMemoryRepository;
import top.javarem.omni.chat.repository.ChatSessionRepository;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContextCollapseServiceImpl implements ContextCollapseService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Value("${chat.memory.collapse.token-threshold:80000}")
    private int tokenThreshold;

    @Override
    public boolean shouldCollapse(String sessionId) {
        int totalTokens = chatMemoryRepository.sumTokensBySessionId(sessionId);
        return totalTokens > tokenThreshold;
    }

    @Override
    public void collapse(String sessionId, String summary) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return;
        }

        // 找到当前链头的前一条作为 parent
        ChatMemory currentHead = chatMemoryRepository.findById(session.getHeadId());
        if (currentHead == null) {
            return;
        }

        // 创建摘要节点
        ChatMemory collapseNode = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(currentHead.getParentId())  // 指向更早的节点
                .sessionId(sessionId)
                .messageType(MessageType.system)  // 用 system 类型标记摘要
                .content(summary)
                .build();

        chatMemoryRepository.save(collapseNode);
        chatSessionRepository.updateHead(sessionId, collapseNode.getId());

        log.info("[ContextCollapse] 折叠完成, sessionId={}, collapseId={}, tokensThreshold={}",
                sessionId, collapseNode.getId(), tokenThreshold);
    }
}