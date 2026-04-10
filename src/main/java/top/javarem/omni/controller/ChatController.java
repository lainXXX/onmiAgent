package top.javarem.omni.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.model.request.ChatRequest;
import top.javarem.omni.tool.ToolsManager;
import top.javarem.omni.utils.RequestContextHolder;

import java.util.List;
import java.util.Map;

/**
 * @Author: rem
 * @Date: 2026/03/09/22:36
 * @Description: AI Agent 流式交互控制器
 */
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    @Resource
    private ChatClient openAiChatClient;

    @Resource
    private ToolsManager toolsManager;

    @PostMapping("/user/input")
    public String chat(@RequestBody ChatRequest request) {
        if (request.getWorkspace() != null && !request.getWorkspace().isBlank()) {
            RequestContextHolder.setWorkspace(request.getWorkspace());
        }
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
                    )
                    .toolContext(Map.of(ChatMemory.CONVERSATION_ID, request.getSessionId(), AdvisorContextConstants.USER_ID, "zzw"))
                    .call()
                    .content();

            return response;

        } catch (Exception e) {
            log.error("LLM 调用失败，回退到向量分数最高的候选", e);
            return "抱歉，我无法生成答案。";
        } finally {
            RequestContextHolder.clear();
        }
    }

    /**
     * 流式输出接口 (POST)
     * 返回 Flux<String>，SSE 文本流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        if (request.getWorkspace() != null && !request.getWorkspace().isBlank()) {
            RequestContextHolder.setWorkspace(request.getWorkspace());
        }
        log.info("[USER] 用户问题：{}", request.getQuestion());
        String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);

        return Flux.fromIterable(List.of(allToolNames)) // 空操作，仅用于初始化
                .thenMany(
                        openAiChatClient.prompt()
                                .user(request.getQuestion())
                                .toolNames(allToolNames)
                                .advisors(spec -> spec
                                        .param(ChatMemory.CONVERSATION_ID, request.getSessionId())
                                        .param(AdvisorContextConstants.ENABLE_SKILL, true)
                                        .param(AdvisorContextConstants.USER_ID, "zzw")
                                )
                                .toolContext(Map.of(
                                        ChatMemory.CONVERSATION_ID, request.getSessionId(),
                                        AdvisorContextConstants.USER_ID, "zzw"))
                                .stream()
                                .chatResponse()
                                .map(response -> {
                                    if (response.getResult() == null || response.getResult().getOutput() == null) {
                                        return "";
                                    }
                                    String text = response.getResult().getOutput().getText();
                                    // 返回带换行的文本块，便于前端处理
                                    return text != null ? text : "";
                                })
                )
                .onErrorResume(ex -> {
                    // API 限流(429/529)或其他异常时，返回 SSE 格式的错误消息而非抛出异常
                    // 否则前端会收到损坏的流且无法解析
                    log.error("[ChatController] 流式输出异常: {}", ex.getMessage());
                    String errorMsg = extractUserFriendlyMessage(ex);
                    return Flux.just("【系统错误】" + errorMsg);
                })
                .doOnComplete(() -> log.info("流式输出完成"))
                .doFinally(signalType -> RequestContextHolder.clear());
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
