package top.javarem.omni.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.model.request.ChatRequest;
import top.javarem.omni.tool.ToolsManager;

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
        log.info("[SYNC] 用户问题：{}", request.getQuestion());
        try {
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
        }
    }

    /**
     * 流式输出接口 (POST)
     * 返回 Flux<String>，SSE 文本流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        log.info("[STREAM] 用户问题：{}", request.getQuestion());
        String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);

        return Flux.fromIterable(Flux.empty().toIterable()) // 空操作，仅用于初始化
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
                                    String text = response.getResult().getOutput().getText();
                                    // 返回带换行的文本块，便于前端处理
                                    return text != null ? text : "";
                                })
                )
                .doOnError(e -> log.error("流式输出异常: {}", e.getMessage()))
                .doOnComplete(() -> log.info("流式输出完成"));
    }
}
