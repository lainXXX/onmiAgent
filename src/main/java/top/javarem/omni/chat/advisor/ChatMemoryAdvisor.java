package top.javarem.omni.chat.advisor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.service.ChatMemoryService;
import top.javarem.omni.chat.service.ChatSessionService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Chat Memory Advisor
 * 负责将 AI 调用过程中的消息持久化到 chat_memory 表
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryAdvisor {

    private final ChatMemoryService chatMemoryService;
    private final ChatSessionService chatSessionService;

    // ==================== 消息保存入口 ====================

    /**
     * 保存用户消息
     */
    public void saveUserMessage(ChatClientRequest request) {
        try {
            String sessionId = extractSessionId(request);
            String userId = extractUserId(request);

            if (sessionId == null || userId == null) {
                log.debug("[ChatMemoryAdvisor] sessionId or userId is null, skip saving");
                return;
            }

            // 确保会话存在
            ensureSessionExists(sessionId, userId);

            // 获取用户消息内容
            List<Message> messages = request.prompt().getInstructions();
            List<Message> userMessages = messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .collect(Collectors.toList());

            for (Message msg : userMessages) {
                String content = msg.getText();
                chatMemoryService.saveMessage(sessionId, MessageType.user, content,
                        null, null, null, null);
            }
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存用户消息失败", e);
        }
    }

    /**
     * 保存助手消息
     */
    public void saveAssistantMessage(ChatClientResponse response, ChatClientRequest request) {
        try {
            if (response == null || response.chatResponse() == null) {
                return;
            }

            String sessionId = extractSessionId(request);
            String userId = extractUserId(request);

            if (sessionId == null || userId == null) {
                return;
            }

            // 确保会话存在
            ensureSessionExists(sessionId, userId);

            // 提取助手消息
            var results = response.chatResponse().getResults();
            if (results == null || results.isEmpty()) {
                return;
            }

            for (var result : results) {
                AssistantMessage assistantMessage = result.getOutput();
                if (assistantMessage == null) {
                    continue;
                }

                String content = assistantMessage.getText();
                if (content == null || content.isBlank()) {
                    continue;
                }

                // 提取 token 使用量
                Integer promptTokens = null;
                Integer completionTokens = null;

                if (response.chatResponse().getMetadata() != null
                        && response.chatResponse().getMetadata().getUsage() != null) {
                    var usage = response.chatResponse().getMetadata().getUsage();
                    promptTokens = usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens();
                }

                chatMemoryService.saveMessage(sessionId, MessageType.assistant, content,
                        null, null, promptTokens, completionTokens);
            }
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存助手消息失败", e);
        }
    }

    // ==================== 工具方法 ====================

    private String extractSessionId(ChatClientRequest request) {
        if (request.context() != null && request.context().containsKey("sessionId")) {
            return request.context().get("sessionId").toString();
        }
        return null;
    }

    private String extractUserId(ChatClientRequest request) {
        if (request.context() != null && request.context().containsKey("userId")) {
            return request.context().get("userId").toString();
        }
        return null;
    }

    private void ensureSessionExists(String sessionId, String userId) {
        if (chatSessionService.getSession(sessionId) == null) {
            chatSessionService.createSession(userId);
        }
    }
}
