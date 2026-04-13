package top.javarem.omni.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import top.javarem.omni.model.ChatChunk;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.model.request.ChatRequest;
import top.javarem.omni.tool.ToolsManager;

import java.util.*;

/**
 * @Author: rem
 * @Description: AI Agent 流式交互控制器 (完美解析版)
 */
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    @Resource
    private ChatClient openAiChatClient;

    @Resource
    private ToolsManager toolsManager;

    @PostMapping("/user/input")
    public String chat(@RequestBody ChatRequest request) {
        try {
            log.info("[SYNC] 用户问题：{}", request.getQuestion());
            String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);
            String response = openAiChatClient.prompt()
                    .user(request.getQuestion())
                    .toolNames(allToolNames)
                    .advisors(spec -> spec
                            .param(ChatMemory.CONVERSATION_ID, request.getSessionId())
                            .param(AdvisorContextConstants.ENABLE_SKILL, true)
                            .param(AdvisorContextConstants.USER_ID, "zzw")
                            .param(AdvisorContextConstants.WORKSPACE, request.getWorkspace())
                    )
                    .toolContext(Map.of(
                            ChatMemory.CONVERSATION_ID, request.getSessionId(),
                            AdvisorContextConstants.USER_ID, "zzw",
                            AdvisorContextConstants.WORKSPACE, request.getWorkspace() != null ? request.getWorkspace() : ""))
                    .call()
                    .content();
            return response;

        } catch (Exception e) {
            log.error("LLM 调用失败，回退到向量分数最高的候选", e);
            return "抱歉，我无法生成答案。";
        }
    }

    /**
     * 流式输出接口 (POST)
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatChunk>> streamChat(@RequestBody ChatRequest request) {
        log.info("[USER] 用户问题：{}", request.getQuestion());
        String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);

        // Flux.defer 确保每次新请求进来，都会创建一个全新的状态机 (StreamState)
        return Flux.defer(() -> {
            StreamState state = new StreamState();

            return openAiChatClient.prompt()
                    .user(request.getQuestion())
                    .toolNames(allToolNames)
                    .advisors(spec -> spec
                            .param(ChatMemory.CONVERSATION_ID, request.getSessionId())
                            .param(AdvisorContextConstants.ENABLE_SKILL, true)
                            .param(AdvisorContextConstants.USER_ID, "zzw")
                            .param(AdvisorContextConstants.WORKSPACE, request.getWorkspace())
                    )
                    .toolContext(Map.of(
                            ChatMemory.CONVERSATION_ID, request.getSessionId(),
                            AdvisorContextConstants.USER_ID, "zzw",
                            AdvisorContextConstants.WORKSPACE, request.getWorkspace() != null ? request.getWorkspace() : ""))
                    .stream()
                    .chatResponse()
                    .filter(response -> response.getResult() != null && response.getResult().getOutput() != null)
                    // 使用 concatMap 保证严格的顺序处理
                    .concatMap(response -> {
                        AssistantMessage output = (AssistantMessage) response.getResult().getOutput();
                        List<ServerSentEvent<ChatChunk>> events = new ArrayList<>();

                        // 1. 处理工具调用 (Tool Calls)
                        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
                        if (toolCalls != null && !toolCalls.isEmpty()) {
                            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                                // 防重复触发：只在第一次遇到该 toolCall 时发送事件
                                if (state.markToolReported(toolCall.id())) {
                                    events.add(createEvent("tool", UUID.randomUUID().toString(), null, toolCall.name()));
                                }
                            }
                        }

                        // 2. 处理流式文本 (Text)
                        String text = output.getText();
                        if (text != null && !text.isEmpty()) {
                            // 将新来的文本喂给状态机，状态机会返回解析好的事件列表
                            events.addAll(state.processDelta(text));
                        }

                        return Flux.fromIterable(events);
                    })
                    // 3. 极其重要：流结束时，将缓冲区最后残留的内容排空 (Flush)
                    .concatWith(Flux.defer(() -> Flux.fromIterable(state.flush())))
                    .onErrorResume(ex -> {
                        log.error("[ChatController] 流式输出异常", ex);
                        return Flux.just(createEvent("error", UUID.randomUUID().toString(), "【系统错误】" + extractUserFriendlyMessage(ex), null));
                    })
                    .doOnComplete(() -> log.info("流式输出完成"));
        });
    }

    /**
     * 流式状态机：专门解决标签被切碎在不同 Chunk 中的问题
     */
    private static class StreamState {
        private boolean isThinking = false;
        private final StringBuilder buffer = new StringBuilder();
        private String currentMessageId = UUID.randomUUID().toString();
        private String currentThoughtId = UUID.randomUUID().toString();
        private final Set<String> reportedTools = new HashSet<>();

        public boolean markToolReported(String toolId) {
            return reportedTools.add(toolId != null ? toolId : UUID.randomUUID().toString());
        }

        public List<ServerSentEvent<ChatChunk>> processDelta(String delta) {
            List<ServerSentEvent<ChatChunk>> events = new ArrayList<>();
            buffer.append(delta);

            while (buffer.length() > 0) {
                if (!isThinking) {
                    int openIdx = buffer.indexOf(OPEN_TAG);
                    if (openIdx != -1) {
                        // 发现 <think> 标签！把标签前面的文本作为 message 发送出去
                        String textToEmit = buffer.substring(0, openIdx);
                        if (!textToEmit.isEmpty()) {
                            events.add(createEvent("message", currentMessageId, textToEmit, null));
                        }
                        isThinking = true;
                        currentThoughtId = UUID.randomUUID().toString(); // 进入思考阶段，换新 ID
                        buffer.delete(0, openIdx + OPEN_TAG.length()); // 从缓冲区删掉已经处理的内容和标签
                    } else {
                        // 没发现完整标签，但是要检查：缓冲区末尾是不是包含残缺的 <th 标签？
                        int safeLen = getSafeLength(buffer.toString(), OPEN_TAG);
                        if (safeLen > 0) {
                            String textToEmit = buffer.substring(0, safeLen);
                            if (!textToEmit.isEmpty()) {
                                events.add(createEvent("message", currentMessageId, textToEmit, null));
                            }
                            buffer.delete(0, safeLen); // 仅仅保留可能构成标签的残缺部分在缓冲区里
                        }
                        break; // 剩下的不够解析，跳出循环，等待下一个 Delta 进来
                    }
                } else {
                    int closeIdx = buffer.indexOf(CLOSE_TAG);
                    if (closeIdx != -1) {
                        // 发现 </think> 标签！把标签前面的文本作为 thought 发送出去
                        String textToEmit = buffer.substring(0, closeIdx);
                        if (!textToEmit.isEmpty()) {
                            events.add(createEvent("thought", currentThoughtId, textToEmit, null));
                        }
                        isThinking = false;
                        currentMessageId = UUID.randomUUID().toString(); // 思考结束，回到正文，换新 ID
                        buffer.delete(0, closeIdx + CLOSE_TAG.length());
                    } else {
                        // 检查缓冲区末尾是否包含残缺的 </th 标签
                        int safeLen = getSafeLength(buffer.toString(), CLOSE_TAG);
                        if (safeLen > 0) {
                            String textToEmit = buffer.substring(0, safeLen);
                            if (!textToEmit.isEmpty()) {
                                events.add(createEvent("thought", currentThoughtId, textToEmit, null));
                            }
                            buffer.delete(0, safeLen);
                        }
                        break;
                    }
                }
            }
            return events;
        }

        // 排空缓冲区（当整个流结束时调用）
        public List<ServerSentEvent<ChatChunk>> flush() {
            List<ServerSentEvent<ChatChunk>> events = new ArrayList<>();
            if (buffer.length() > 0) {
                String textToEmit = buffer.toString();
                String role = isThinking ? "thought" : "message";
                String id = isThinking ? currentThoughtId : currentMessageId;
                events.add(createEvent(role, id, textToEmit, null));
                buffer.setLength(0);
            }
            return events;
        }

        // 计算当前缓冲区中，多少长度的字符串是“绝对安全、不包含目标标签任何前缀”的
        private int getSafeLength(String text, String targetTag) {
            // 从最长的可能前缀开始检查 (例如先查有没有 "<thin"，再查 "<thi")
            for (int i = targetTag.length() - 1; i > 0; i--) {
                if (text.endsWith(targetTag.substring(0, i))) {
                    return text.length() - i; // 遇到残缺标签，返回残缺标签前的位置
                }
            }
            return text.length(); // 没有任何标签前缀，全部安全，可以全部发给前端
        }
    }

    private static ServerSentEvent<ChatChunk> createEvent(String eventName, String id, String content, String toolName) {
        ChatChunk chunk = ChatChunk.builder()
                .id(id)
                .content(content)
                .role(eventName)
                .toolName(toolName)
                .done(false)
                .build();
        return ServerSentEvent.<ChatChunk>builder()
                .event(eventName)
                .data(chunk)
                .build();
    }

    /**
     * 从异常中提取用户友好的错误信息
     */
    private String extractUserFriendlyMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return "API 请求失败，请稍后重试";
        }
        if (msg.contains("429") || msg.contains("Too Many Requests")) {
            return "API 请求过于频繁，请稍后重试（429 Too Many Requests）";
        }
        if (msg.contains("529") || msg.contains("Unknown status code")) {
            return "API 服务暂不可用，请稍后重试（529）";
        }
        if (msg.contains("timeout") || msg.contains("Timeout")) {
            return "API 请求超时，请检查网络或稍后重试";
        }
        if (msg.contains("connection") || msg.contains("Connection")) {
            return "网络连接失败，请检查网络后重试";
        }
        // 截断过长消息
        if (msg.length() > 100) {
            return msg.substring(0, 100) + "...";
        }
        return msg;
    }
}