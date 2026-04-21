package top.javarem.omni.chat.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.AroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.ChatClientAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleChatClientAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.chat.metadata.ChatCompletionUsage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.service.ChatMemoryService;
import top.javarem.omni.chat.service.ChatSessionService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chat Memory Advisor
 * 拦截 AI 调用并持久化消息到 chat_memory 表
 */
@Component
@Slf4j
public class ChatMemoryAdvisor implements ChatClientAdvisor {

    private final ChatMemoryService chatMemoryService;
    private final ChatSessionService chatSessionService;

    public ChatMemoryAdvisor(ChatMemoryService chatMemoryService,
                             ChatSessionService chatSessionService) {
        this.chatMemoryService = chatMemoryService;
        this.chatSessionService = chatSessionService;
    }

    @Override
    public int getOrder() {
        return SimpleChatClientAdvisor.super.getOrder();
    }

    // ==================== Around Advisor (支持 before/after) ====================

    @Override
    public ChatClientResponse aroundCall(AroundAdvisorChain chain,
                                         Map<String, Object> attributes) {
        ChatClientRequest request = chain.request();
        ChatClientResponse response = chain.proceed(attributes);

        // 保存用户消息 (before)
        saveUserMessage(request);

        // 保存助手消息 (after)
        saveAssistantMessage(response, request);

        return response;
    }

    @Override
    public Flux<ChatClientResponse> aroundStream(AroundAdvisorChain chain,
                                                  Map<String, Object> attributes) {
        // 流式暂不处理（等 response 完整后再保存）
        return chain.proceed(attributes)
                .doOnNext(response -> {
                    // 保存用户消息
                    saveUserMessage(chain.request());
                    // 保存助手消息
                    saveAssistantMessage(response, chain.request());
                });
    }

    // ==================== 消息保存逻辑 ====================

    private void saveUserMessage(ChatClientRequest request) {
        try {
            String sessionId = extractSessionId(request);
            String userId = extractUserId(request);

            if (sessionId == null || userId == null) {
                return;
            }

            // 确保会话存在
            if (chatSessionService.getSession(sessionId) == null) {
                chatSessionService.createSession(userId);
            }

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

    private void saveAssistantMessage(ChatClientResponse response, ChatClientRequest request) {
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
            if (chatSessionService.getSession(sessionId) == null) {
                chatSessionService.createSession(userId);
            }

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

                if (response.chatResponse().getMetadata() != null) {
                    ChatCompletionUsage usage = response.chatResponse().getMetadata().getUsage();
                    if (usage != null) {
                        promptTokens = usage.getPromptTokens();
                        completionTokens = usage.getCompletionTokens();
                    }
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
}
