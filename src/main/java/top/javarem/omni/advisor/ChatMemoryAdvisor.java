package top.javarem.omni.advisor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.repository.chat.ChatMemoryRepository;

import java.util.Map;

/**
 * Chat Memory Advisor
 * 负责将 AI 调用过程中的消息持久化到 chat_memory 表
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryAdvisor {

    private static final String MEMORY_SAVED_KEY = "omni_memory_saved";
    private static final String CURRENT_USER_MSG_KEY = "omni_current_user_msg";

    private final ChatMemoryRepository chatMemoryRepository;

    // ==================== UserMessage ====================

    /**
     * 保存用户消息
     */
    public void saveUserMessage(ChatClientRequest request) {
        try {
            Map<String, Object> ctx = request != null ? request.context() : null;
            String sessionId = getSessionId(ctx);
            if (sessionId == null) {
                log.debug("[ChatMemoryAdvisor] sessionId is null, skip saving");
                return;
            }

            if (Boolean.TRUE.equals(ctx.get(MEMORY_SAVED_KEY))) {
                log.debug("[ChatMemoryAdvisor] 已在本周期保存过，跳过");
                return;
            }
            ctx.put(MEMORY_SAVED_KEY, true);

            Message msg = (Message) ctx.get(CURRENT_USER_MSG_KEY);
            if (msg == null) {
                msg = request.prompt().getLastUserOrToolResponseMessage();
            }
            if (msg == null) return;

            String userId = getUserId(ctx);
            chatMemoryRepository.saveUserMessage(sessionId, userId, (UserMessage) msg, null);
            log.debug("[ChatMemoryAdvisor] 保存用户消息到会话 {}", sessionId);
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存用户消息失败", e);
        }
    }

    /**
     * 预取并缓存当前轮次的 UserMessage/ToolResponseMessage
     */
    public void cacheCurrentUserMessage(ChatClientRequest request) {
        if (request == null || request.context() == null) return;
        if (request.context().get(MEMORY_SAVED_KEY) != null) return;
        Message msg = request.prompt().getLastUserOrToolResponseMessage();
        request.context().put(CURRENT_USER_MSG_KEY, msg);
    }

    // ==================== 工具循环中的 AssistantMessage ====================

    /**
     * 保存中间 AssistantMessage（工具调用指令）
     */
    public void saveIntermediateAssistant(ChatClientRequest request, ChatClientResponse response) {
        try {
            if (response == null || response.chatResponse() == null) return;
            String sessionId = getSessionId(request);
            if (sessionId == null) return;

            var results = response.chatResponse().getResults();
            if (results == null || results.isEmpty()) return;

            AssistantMessage msg = results.get(0).getOutput();
            if (msg == null) return;

            Usage usage = extractUsage(response);
            chatMemoryRepository.saveAssistantMessage(sessionId, "assistant", msg, usage);
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存中间 Assistant 消息失败", e);
        }
    }

    // ==================== ToolResponseMessage ====================

    /**
     * 保存工具执行结果
     */
    public void saveToolResponses(ChatClientRequest request, ToolExecutionResult toolExecutionResult) {
        try {
            if (toolExecutionResult == null) return;
            String sessionId = getSessionId(request);
            if (sessionId == null) return;

            java.util.List<Message> toolMessages = extractToolMessages(toolExecutionResult);
            for (Message msg : toolMessages) {
                chatMemoryRepository.saveToolResponseMessage(sessionId, "tool", (ToolResponseMessage) msg, null);
            }
            if (!toolMessages.isEmpty()) {
                log.debug("[ChatMemoryAdvisor] 保存 {} 条工具响应到会话 {}", toolMessages.size(), sessionId);
            }
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存工具响应失败", e);
        }
    }

    private java.util.List<Message> extractToolMessages(ToolExecutionResult toolExecutionResult) {
        try {
            Object result = invokeMethod(toolExecutionResult, "getToolResponses", Object.class);
            if (result == null) {
                result = invokeMethod(toolExecutionResult, "returnMessages", Object.class);
            }
            if (result instanceof java.util.List<?> list) {
                return list.stream()
                        .filter(m -> m instanceof Message)
                        .map(m -> (Message) m)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("[ChatMemoryAdvisor] extractToolMessages 失败: {}", e.getMessage());
        }
        return java.util.List.of();
    }

    // ==================== 最终 AssistantMessage ====================

    /**
     * 保存最终 AssistantMessage（同步模式）
     */
    public void saveFinalAssistantMessage(ChatClientResponse response) {
        try {
            if (response == null || response.chatResponse() == null) return;
            String sessionId = getSessionId(response.context());
            if (sessionId == null) return;

            var results = response.chatResponse().getResults();
            if (results == null || results.isEmpty()) return;

            for (var result : results) {
                AssistantMessage msg = result.getOutput();
                if (msg == null || msg.getText() == null || msg.getText().isBlank()) continue;
                Usage usage = extractUsage(response);
                chatMemoryRepository.saveAssistantMessage(sessionId, "assistant", msg, usage);
            }
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存最终 Assistant 消息失败", e);
        }
    }

    /**
     * 保存最终 AssistantMessage（流式聚合模式）
     */
    public void saveFinalStreamAssistantMessage(ChatClientResponse response, AssistantMessage aggregatedMessage) {
        try {
            if (response == null || aggregatedMessage == null) return;
            String sessionId = getSessionId(response.context());
            if (sessionId == null) return;

            Usage usage = extractUsage(response);
            chatMemoryRepository.saveAssistantMessage(sessionId, "assistant", aggregatedMessage, usage);
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存流式聚合 Assistant 消息失败", e);
        }
    }

    // ==================== 工具方法 ====================

    private String getSessionId(ChatClientRequest request) {
        return request != null ? getSessionId(request.context()) : null;
    }

    private String getSessionId(Map<String, Object> context) {
        if (context == null) return null;
        Object o = context.get(AdvisorContextConstants.SESSION_ID);
        return o != null ? o.toString() : null;
    }

    private String getUserId(Map<String, Object> context) {
        if (context == null) return "system";
        Object o = context.get(AdvisorContextConstants.USER_ID);
        return o != null ? o.toString() : "system";
    }

    private Usage extractUsage(ChatClientResponse response) {
        try {
            var metadata = response.chatResponse().getResults().get(0).getMetadata();
            if (metadata == null) return null;
            return invokeMethod(metadata, "getUsage", Usage.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeMethod(Object target, String methodName, Class<T> returnType) {
        try {
            var m = target.getClass().getMethod(methodName);
            var v = m.invoke(target);
            return (T) v;
        } catch (Exception e) {
            return null;
        }
    }
}