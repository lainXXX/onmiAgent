package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.advisor.hook.HookRegistry;
import top.javarem.omni.model.AgentFinishStatus;
import top.javarem.omni.model.context.AdvisorContextConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生命周期工具调用 Advisor
 *
 * <p>继承自 Spring AI 的 ToolCallAdvisor，
 * 负责管理工具调用循环的生命周期。
 *
 * <p>使用 HookRegistry 组合各种 Hook：
 * <ul>
 *   <li>SessionLifecycleHook - 会话开始/结束</li>
 *   <li>PreToolUseHook - 工具调用前</li>
 *   <li>PostToolUseHook - 工具调用后</li>
 *   <li>CompressionHook - 压缩</li>
 *   <li>StopHook - 工具链结束</li>
 * </ul>
 */
@Component
@Slf4j
public class LifecycleToolCallAdvisor extends ToolCallAdvisor {

    private final HookRegistry hookRegistry;
    private final ThreadPoolExecutor threadPoolExecutor;

    private static final String TOOL_HISTORY_HOLDER = "TOOL_HISTORY_HOLDER";
    private static final int ORDER = Integer.MAX_VALUE - 1000;

    protected LifecycleToolCallAdvisor(
            ToolCallingManager toolCallingManager,
            HookRegistry hookRegistry,
            ThreadPoolExecutor threadPoolExecutor) {
        super(toolCallingManager, ORDER, true);
        this.hookRegistry = hookRegistry;
        this.threadPoolExecutor = threadPoolExecutor;
    }

    // ==================== Session Lifecycle ====================

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        // Hook: SessionStart
        hookRegistry.onSessionStart(chatClientRequest);

        return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // Hook: SessionStart
        hookRegistry.onSessionStart(chatClientRequest);

        return super.doInitializeLoopStream(chatClientRequest, streamAdvisorChain);
    }

    // ==================== PreToolUse ====================

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        // 执行 PreToolUse Hook 链
        ChatClientRequest modified = hookRegistry.doPreToolUse(chatClientRequest);
        if (modified == null) {
            // Hook 取消，返回原始请求
            log.warn("PreToolUse Hook 取消了请求");
            return chatClientRequest;
        }
        return super.doBeforeCall(modified, callAdvisorChain);
    }

    // ==================== PostToolUse ====================

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse chatClientResponse, CallAdvisorChain callAdvisorChain) {
        // 执行 PostToolUse Hook 链
        ChatClientResponse modified = hookRegistry.doPostToolUse(chatClientResponse);
        return super.doAfterCall(modified, callAdvisorChain);
    }

    // ==================== Compression (关键点) ====================

    @Override
    protected List<Message> doGetNextInstructionsForToolCall(
            ChatClientRequest chatClientRequest,
            ChatClientResponse chatClientResponse,
            ToolExecutionResult toolExecutionResult) {

        // 1. 执行压缩 Hook
        List<Message> messages = hookRegistry.doCompression(
                chatClientRequest,
                chatClientResponse,
                toolExecutionResult
        );

        // 2. 如果压缩 Hook 返回了消息，使用压缩后的消息
        if (messages != null && !messages.isEmpty()) {
            log.debug("CompressionHook 返回 {} 条消息", messages.size());
        } else {
            // 使用默认逻辑
            messages = super.doGetNextInstructionsForToolCall(
                    chatClientRequest,
                    chatClientResponse,
                    toolExecutionResult
            );
        }

        return messages;
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(
            ChatClientRequest chatClientRequest,
            ChatClientResponse chatClientResponse,
            ToolExecutionResult toolExecutionResult) {

        // 执行压缩 Hook
        List<Message> messages = hookRegistry.doCompression(
                chatClientRequest,
                chatClientResponse,
                toolExecutionResult
        );

        if (messages == null || messages.isEmpty()) {
            messages = super.doGetNextInstructionsForToolCallStream(
                    chatClientRequest,
                    chatClientResponse,
                    toolExecutionResult
            );
        }

        return messages;
    }

    // ==================== StopHook ====================

    @Override
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse chatClientResponse, CallAdvisorChain callAdvisorChain) {
        // Hook: StopHook
        hookRegistry.doStopHook(chatClientResponse);

        // Hook: SessionEnd
        hookRegistry.onSessionEnd(chatClientResponse);

        return super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
    }

    @Override
    protected Flux<ChatClientResponse> doFinalizeLoopStream(
            Flux<ChatClientResponse> flux,
            StreamAdvisorChain streamAdvisorChain) {
        // 注意：流式模式的 finalize 需要特殊处理
        return super.doFinalizeLoopStream(flux, streamAdvisorChain);
    }

    // ==================== 同步调用入口 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = super.adviseCall(request, chain);
        request.context().put(AdvisorContextConstants.TOOL_CALL_PHASE, AdvisorContextConstants.Phase.COMPLETED);
        storeAssistantMessage(response, request.context());
        return response;
    }

    // ==================== 流式调用入口 ====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();
        AtomicReference<List<Message>> historyHolder = new AtomicReference<>(new ArrayList<>());
        request.context().put(TOOL_HISTORY_HOLDER, historyHolder);

        Flux<ChatClientResponse> flux = super.adviseStream(request, chain);
        if (flux == null) {
            return Flux.empty();
        }

        return aggregator.aggregateChatClientResponse(flux, completeResponse -> {
            request.context().put(AdvisorContextConstants.TOOL_CALL_PHASE, AdvisorContextConstants.Phase.COMPLETED);
            storeAssistantMessage(completeResponse, request.context());
        });
    }

    // ==================== 消息存储 ====================

    private void storeAssistantMessage(ChatClientResponse response, Map<String, Object> context) {
        if (response == null || response.chatResponse() == null) return;

        try {
            if (response.chatResponse().getResults().isEmpty()) {
                log.debug("[storeAssistantMessage] 结果为空，跳过保存");
                return;
            }

            String finishReason = response.chatResponse().getResults().get(0).getMetadata().getFinishReason();
            if (!AgentFinishStatus.STOP.equals(AgentFinishStatus.from(finishReason))) {
                log.debug("[storeAssistantMessage] 非正常结束 finishReason={}, 跳过保存", finishReason);
                return;
            }

            AssistantMessage assistantMessage = response.chatResponse().getResults().get(0).getOutput();
            String text = assistantMessage.getText();
            if (text == null || text.isBlank()) {
                log.debug("[storeAssistantMessage] 文本为空, 跳过保存");
                return;
            }

            log.info("[storeAssistantMessage] 助手消息处理完成 textLength={}", text.length());
        } catch (Exception e) {
            log.error("解析工具调用历史发生异常", e);
        }
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return super.doBeforeStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    protected ChatClientResponse doAfterStream(ChatClientResponse chatClientResponse, StreamAdvisorChain streamAdvisorChain) {
        return super.doAfterStream(chatClientResponse, streamAdvisorChain);
    }
}
