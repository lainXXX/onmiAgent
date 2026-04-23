package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.advisor.hook.HookRegistry;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.repository.chat.ChatMemoryRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class LifecycleToolCallAdvisor extends ToolCallAdvisor {

    private final HookRegistry hookRegistry;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final ChatMemoryAdvisor chatMemoryAdvisor; // 建议改名为 ChatMemoryService
    private final ChatMemoryRepository chatMemoryRepository;

    private static final int ORDER = Integer.MAX_VALUE - 1000;

    protected LifecycleToolCallAdvisor(
            ToolCallingManager toolCallingManager,
            HookRegistry hookRegistry,
            ThreadPoolExecutor threadPoolExecutor,
            ChatMemoryRepository chatMemoryRepository,
            ChatMemoryAdvisor chatMemoryAdvisor) {
        super(toolCallingManager, ORDER, true);
        this.hookRegistry = hookRegistry;
        this.threadPoolExecutor = threadPoolExecutor;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemoryAdvisor = chatMemoryAdvisor;
    }

    // ==================== 1. Session Lifecycle (起点) ====================

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        hookRegistry.onSessionStart(request);
        return super.doInitializeLoop(request, chain);
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest request, StreamAdvisorChain chain) {
        hookRegistry.onSessionStart(request);
        return super.doInitializeLoopStream(request, chain);
    }

    // ==================== 2. PreToolUse (调用大模型前) ====================

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest modified = hookRegistry.doPreToolUse(request);
        return super.doBeforeCall(modified != null ? modified : request, chain);
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest modified = hookRegistry.doPreToolUse(request);
        Message message = request.prompt().getLastUserOrToolResponseMessage();
        String userId = request.context().get(AdvisorContextConstants.USER_ID).toString();
        String sessionId = request.context().get(AdvisorContextConstants.SESSION_ID).toString();
        if (message instanceof UserMessage u) {
            chatMemoryRepository.saveUserMessage(sessionId, userId, u, null);
        } else if (message instanceof ToolResponseMessage t) {
            chatMemoryRepository.saveToolResponseMessage(sessionId, userId, t, null);
        }
        return super.doBeforeStream(modified != null ? modified : request, chain);
    }

    // ==================== 3. 工具内部循环 (保存 Assistant 发出的指令 + Tool 执行结果) ====================

    @Override
    protected List<Message> doGetNextInstructionsForToolCall(
            ChatClientRequest request, ChatClientResponse response, ToolExecutionResult toolExecutionResult) {

        return super.doGetNextInstructionsForToolCall(request, response, toolExecutionResult);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(
            ChatClientRequest request, ChatClientResponse response, ToolExecutionResult toolExecutionResult) {

        return super.doGetNextInstructionsForToolCallStream(request, response, toolExecutionResult);
    }

    /**
     * 【修复致命缺陷一】必须同时保存 Assistant 的发号施令 和 Tool 的执行结果
     */
    private void saveIntermediateToolNodes(ChatClientRequest request, ChatClientResponse response, ToolExecutionResult toolResult) {
        // 1. 存入大模型发出的工具调用指令 (AssistantMessage 带有 tool_calls)
        if (response != null && response.chatResponse() != null && !response.chatResponse().getResults().isEmpty()) {
            chatMemoryAdvisor.saveIntermediateAssistant(request, response);
        }

        // 2. 存入工具的执行结果 (ToolResponseMessage)
        if (toolResult != null) {
            chatMemoryAdvisor.saveToolResponses(request, toolResult);
        }
    }

    // ==================== 4. PostToolUse (大模型单次返回后) ====================

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        ChatClientResponse modified = hookRegistry.doPostToolUse(response);
        response.chatResponse().getResult();
        return super.doAfterCall(modified, chain);
    }

    @Override
    protected ChatClientResponse doAfterStream(ChatClientResponse response, StreamAdvisorChain chain) {
        ChatClientResponse modified = hookRegistry.doPostToolUse(response);
        String userId = response.context().get(AdvisorContextConstants.USER_ID).toString();
        String sessionId = response.context().get(AdvisorContextConstants.SESSION_ID).toString();
        AssistantMessage assistantMessage = response.chatResponse().getResult().getOutput();
        Usage usage = response.chatResponse().getMetadata().getUsage();
        chatMemoryRepository.saveAssistantMessage(sessionId, userId, assistantMessage, usage);
        return super.doAfterStream(modified, chain);
    }

    // ==================== 5. Finalize / StopHook (整个大循环结束，终点) ====================

    @Override
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse response, CallAdvisorChain chain) {

        // 2. 触发结束 Hooks
        hookRegistry.doStopHook(response);
        hookRegistry.onSessionEnd(response);

        return super.doFinalizeLoop(response, chain);
    }

    @Override
    protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> flux, StreamAdvisorChain chain) {
        // 【修复致命缺陷二、三】：优雅处理流式聚合与生命周期，不破坏 Flux 消费链
        AtomicReference<StringBuilder> fullText = new AtomicReference<>(new StringBuilder());
        AtomicReference<ChatClientResponse> lastResponse = new AtomicReference<>();

        Flux<ChatClientResponse> interceptedFlux = flux
                .doOnNext(response -> {
                    lastResponse.set(response);
                    if (response.chatResponse() != null && !response.chatResponse().getResults().isEmpty()) {
                        var out = response.chatResponse().getResults().get(0).getOutput();
                        if (out != null && out.getText() != null) {
                            fullText.get().append(out.getText());
                        }
                    }
                })
                .doOnComplete(() -> {

                    // 流处理结束：触发结束 Hooks
                    hookRegistry.doStopHook(lastResponse.get());
                    hookRegistry.onSessionEnd(lastResponse.get());
                });

        return super.doFinalizeLoopStream(interceptedFlux, chain);
    }
}