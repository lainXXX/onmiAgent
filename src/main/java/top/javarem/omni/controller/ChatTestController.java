package top.javarem.omni.controller;

import jakarta.annotation.Resource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.model.request.ChatRequest;
import top.javarem.omni.tool.ToolsManager;

import java.util.HashMap;
import java.util.Map;

/**
 * @Author: rem
 * @Date: 2026/04/14/23:51
 * @Description:
 */
@RestController
@RequestMapping("/chatTest")
public class ChatTestController {

    private final ChatClient anthropicChatClient;
    @Resource
    private ToolsManager toolsManager;
    public ChatTestController(@Qualifier("anthropicChatClient") ChatClient anthropicChatClient) {
        this.anthropicChatClient = anthropicChatClient;
    }
    @Resource
    private AnthropicChatModel chatModel;
    @PostMapping("/chat")
    public ChatResponse chatTest(@RequestBody ChatRequest request) {
        String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);
        ChatClient.CallResponseSpec call = anthropicChatClient.prompt()
                .toolNames(allToolNames)
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, request.getSessionId())
                        .param(AdvisorContextConstants.ENABLE_SKILL, true)
                        .param(AdvisorContextConstants.USER_ID, "zzw")
                        .param(AdvisorContextConstants.WORKSPACE, request.getWorkspace())
                )
                .toolContext(new HashMap<>(Map.of(
                        ChatMemory.CONVERSATION_ID, request.getSessionId(),
                        AdvisorContextConstants.USER_ID, "zzw",
                        AdvisorContextConstants.WORKSPACE, request.getWorkspace() != null ? request.getWorkspace() : "")))
                .options(AnthropicChatOptions.builder()
                .temperature(1.0)  // Temperature should be set to 1 when thinking is enabled
                .thinking(AnthropicApi.ThinkingType.ENABLED, 2048)  // Must be ≥1024 && < max_tokens
                .build()).user(request.getQuestion()).toolNames(allToolNames).toolContext(new HashMap<>(Map.of("tools", toolsManager.getToolCallbacks()))).call();
        ChatResponse response = call.chatResponse();
        // 3. 提取结果
        var result = response.getResult();
        var output = result.getOutput();

        String answer = output.getText();

        // 4. 从 metadata 中提取思考内容
        String thinking = "";
        if (output.getMetadata().containsKey("thinking")) {
            thinking = (String) output.getMetadata().get("thinking");
        }
        return response;
    }

    @GetMapping("/stream")
    public Flux<String> streamChatTest(String message) {
        String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);
        ChatClient.StreamResponseSpec call = anthropicChatClient.prompt().options(AnthropicChatOptions.builder()
                .temperature(1.0)  // Temperature should be set to 1 when thinking is enabled
                .thinking(AnthropicApi.ThinkingType.ENABLED, 2048)  // Must be ≥1024 && < max_tokens
                .build()).user(message).toolNames(allToolNames).toolContext(new HashMap<>(Map.of("tools", toolsManager.getToolCallbacks()))).stream();
        return call.content();
    }


}
