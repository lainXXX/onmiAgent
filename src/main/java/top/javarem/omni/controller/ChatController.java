package top.javarem.onmi.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import top.javarem.onmi.model.context.AdvisorContextConstants;
import top.javarem.onmi.model.request.ChatRequest;
import top.javarem.onmi.tool.ToolsManager;

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
                    // 传递参数给已有的 advisors（不会覆盖 AiConfig 中的配置）
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
     * 使用 ReadableStream + SSE 协议实现实时打字机效果
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        log.info("[STREAM] 用户问题：{}", request.getQuestion());
        String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);
        return openAiChatClient.prompt()
                .user(request.getQuestion())
                .toolNames(allToolNames)
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, request.getSessionId())
                        .param(AdvisorContextConstants.ENABLE_SKILL, true)
                        .param(AdvisorContextConstants.USER_ID, "zzw")
                )
                .toolContext(Map.of(ChatMemory.CONVERSATION_ID, request.getSessionId(), AdvisorContextConstants.USER_ID, "zzw"))
                .stream()
                .chatResponse() // 获取完整的响应对象流
                .map(response -> {
                    var output = response.getResult().getOutput();

                    return output.getText();
                })
                .doOnError(e -> {
                    log.error("流式输出异常: {}", e.getMessage());
                    // 注意：SSE 中无法直接发送带 [ERROR] 前缀的响应，
                    // 异常会被 Spring 转换为 HTTP 错误响应
                })
                .doOnComplete(() -> log.info("流式输出完成"));

        // 前端需识别以下错误场景：
        // 1. HTTP 状态码非 2xx - 表示服务端异常
        // 2. 响应体中的错误前缀（如 data: [ERROR] xxx）
    }

}
