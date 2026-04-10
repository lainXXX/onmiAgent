package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.model.AgentFinishStatus;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.repository.chat.ChatHistoryRepository;
import top.javarem.omni.repository.chat.MemoryRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class LifecycleToolCallAdvisor extends ToolCallAdvisor {

    private final ThreadPoolExecutor threadPoolExecutor;
    private final MemoryRepository memoryRepository;
    private final ChatHistoryRepository chatHistoryRepository;

    private static final String TOOL_HISTORY_HOLDER = "TOOL_HISTORY_HOLDER";
    private final static int ORDER = Integer.MAX_VALUE - 1000;

    protected LifecycleToolCallAdvisor(ToolCallingManager toolCallingManager, ThreadPoolExecutor threadPoolExecutor, MemoryRepository memoryRepository, ChatHistoryRepository chatHistoryRepository) {
        super(toolCallingManager, ORDER, true);
        this.memoryRepository = memoryRepository;
        this.threadPoolExecutor = threadPoolExecutor;
        this.chatHistoryRepository = chatHistoryRepository;
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        // 保存用户消息
        String conversationId = (String) chatClientRequest.context().get(ChatMemory.CONVERSATION_ID);
        String userId = (String) chatClientRequest.context().get(AdvisorContextConstants.USER_ID);
        memoryRepository.saveAll(conversationId, userId, List.of(chatClientRequest.prompt().getUserMessage()));
        chatHistoryRepository.saveUserMessage(conversationId, null, chatClientRequest.prompt().getUserMessage().getText());
        return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
    }

    // ==========================================
    // 1. 同步拦截：在 do-while 循环外层建立通信
    // ==========================================
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

        // 调用父类，让 Spring AI 去跑那个恶心的 do-while 循环
        ChatClientResponse response = super.adviseCall(request, chain);

        // 循环彻底结束后，直接从信使里拿数据！绝对能拿到！
        request.context().put(AdvisorContextConstants.TOOL_CALL_PHASE, AdvisorContextConstants.Phase.COMPLETED);
        storeAssistantMessage(response, request.context());

        return response;
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        String conversationId = (String) chatClientRequest.context().get(ChatMemory.CONVERSATION_ID);
        String userId = (String) chatClientRequest.context().get(AdvisorContextConstants.USER_ID);
        chatHistoryRepository.saveUserMessage(conversationId, null, chatClientRequest.prompt().getUserMessage().getText());
        memoryRepository.saveAll(conversationId, userId, List.of(chatClientRequest.prompt().getUserMessage()));
        return super.doInitializeLoopStream(chatClientRequest, streamAdvisorChain);
    }

    // ==========================================
    // 2. 异步拦截：流式模式的处理
    // ==========================================


    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();
        AtomicReference<List<Message>> historyHolder = new AtomicReference<>(new ArrayList<>());
        request.context().put(TOOL_HISTORY_HOLDER, historyHolder);

        Flux<ChatClientResponse> flux = super.adviseStream(request, chain);
        if (flux == null) {
            return Flux.empty();
        }

        // 使用 ChatClientMessageAggregator 聚合流，在流结束后再执行存储逻辑
        // 这样 completeResponse 包含完整聚合后的响应
        return aggregator.aggregateChatClientResponse(flux, completeResponse -> {
            request.context().put(AdvisorContextConstants.TOOL_CALL_PHASE, AdvisorContextConstants.Phase.COMPLETED);
            storeAssistantMessage(completeResponse, request.context());
        });
    }

    // ==========================================
    // 3. 循环内部：把数据装进信使（Call 模式）
    // ==========================================
    @Override
    @SuppressWarnings("unchecked")
    protected List<Message> doGetNextInstructionsForToolCall(ChatClientRequest request,
                                                             ChatClientResponse response,
                                                             ToolExecutionResult result) {
        List<Message> messages = super.doGetNextInstructionsForToolCall(request, response, result);

        saveToolExecutionHistory(request, result);

        return messages;
    }

    // ==========================================
    // 4. 循环内部：把数据装进信使（Stream 模式）
    // ==========================================
    @Override
    @SuppressWarnings("unchecked")
    protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest request,
                                                                   ChatClientResponse response,
                                                                   ToolExecutionResult result) {
        List<Message> messages = super.doGetNextInstructionsForToolCallStream(request, response, result);

        saveToolExecutionHistory(request, result);

        return messages;
    }

    private void saveToolExecutionHistory(ChatClientRequest request, ToolExecutionResult result) {
        Object sessionIdObj = request.context().get(AdvisorContextConstants.SESSION_ID);
        Object userIdObj = request.context().get(AdvisorContextConstants.USER_ID);

        // 安全获取变量，防止空指针
        String sessionId = sessionIdObj != null ? sessionIdObj.toString() : null;
        String userId = userIdObj != null ? userIdObj.toString() : null;

        var history = result.conversationHistory();
        int size = history.size();

        if (sessionId != null && size >= 2) {
            // 截取最后两条并映射转换 (通常是一条 Assistant 的 ToolCall，一条 ToolResultMessage)
            List<Message> toolMessage = history.subList(size - 2, size);
            memoryRepository.saveAll(sessionId, userId, toolMessage);
        }
    }

    private void storeAssistantMessage(ChatClientResponse response, Map<String, Object> context) {
        if (response == null || response.chatResponse() == null) return;

        try {
            // 校验是否正常结束
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

            String conversationId = context.get(ChatMemory.CONVERSATION_ID).toString();
            String userId = context.get(AdvisorContextConstants.USER_ID).toString();
            log.info("[保存大模型输出内容] conversationId={}, userId={}, textLength={}", conversationId, userId, text.length());
            this.threadPoolExecutor.execute(() -> {
                try {
                    chatHistoryRepository.saveAssistantMessage(conversationId, null, userId, text);
                    memoryRepository.save(conversationId, userId, assistantMessage);
                } catch (Exception e) {
                    log.error("数据库写入失败", e);
                }
            });
        } catch (Exception e) {
            log.error("解析工具调用历史发生异常", e);
        }
    }

}