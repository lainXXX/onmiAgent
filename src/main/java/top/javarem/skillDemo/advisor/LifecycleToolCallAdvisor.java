package top.javarem.skillDemo.advisor;

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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.skillDemo.model.AgentFinishStatus;
import top.javarem.skillDemo.model.context.AdvisorContextConstants;
import top.javarem.skillDemo.repository.chat.ChatHistoryRepository;
import top.javarem.skillDemo.repository.chat.MemoryRepository;

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
        memoryRepository.saveAll(conversationId, List.of(chatClientRequest.prompt().getUserMessage()));
        chatHistoryRepository.saveUserMessage(conversationId, null, chatClientRequest.prompt().getUserMessage().getText());
        return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
    }

    // ==========================================
    // 1. 同步拦截：在 do-while 循环外层建立通信
    // ==========================================
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 创建一个“信使”对象存入 Map。就算后续 Map 被复制，信使的引用也不会变。
        AtomicReference<List<Message>> historyHolder = new AtomicReference<>(new ArrayList<>());
        request.context().put(TOOL_HISTORY_HOLDER, historyHolder);

        // 调用父类，让 Spring AI 去跑那个恶心的 do-while 循环
        ChatClientResponse response = super.adviseCall(request, chain);

        // 循环彻底结束后，直接从信使里拿数据！绝对能拿到！
        request.context().put(AdvisorContextConstants.TOOL_CALL_PHASE, AdvisorContextConstants.Phase.COMPLETED);
        storeMessage(response, request.context(), historyHolder.get());

        return response;
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        String conversationId = (String) chatClientRequest.context().get(ChatMemory.CONVERSATION_ID);
        chatHistoryRepository.saveUserMessage(conversationId, null, chatClientRequest.prompt().getUserMessage().getText());
        memoryRepository.saveAll(conversationId, List.of(chatClientRequest.prompt().getUserMessage()));
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
            storeMessage(completeResponse, request.context(), historyHolder.get());
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

        // 获取信使，并更新里面的数据
        Object holder = request.context().get(TOOL_HISTORY_HOLDER);
        if (holder instanceof AtomicReference ref) {
            ref.set(result.conversationHistory());
        }

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

        // 获取信使，并更新里面的数据
        Object holder = request.context().get(TOOL_HISTORY_HOLDER);
        if (holder instanceof AtomicReference ref) {
            ref.set(result.conversationHistory());
        }

        return messages;
    }

    // ==========================================
    // 4. 纯粹的存储逻辑（与外层解耦）
    // ==========================================
    private void storeMessage(ChatClientResponse response, Map<String, Object> context, List<Message> history) {
        if (response == null || response.chatResponse() == null || history == null || history.isEmpty()) return;

        try {
            // 校验是否正常结束
            String finishReason = response.chatResponse().getResults().get(0).getMetadata().getFinishReason();
            if (!AgentFinishStatus.STOP.equals(AgentFinishStatus.from(finishReason))) {
                return;
            }
            // 找到最后一个用户消息的索引
            int lastUserIdx = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) instanceof UserMessage) {
                    lastUserIdx = i;
                    break;
                }
            }

            // 截取从那个索引之后的所有消息
            List<Message> toolInteractionMessages = history.subList(lastUserIdx + 1, history.size());

            // 获取最终的模型文本输出并追加（创建干净副本，避免 metadata 污染后续对话）
            if (!response.chatResponse().getResults().isEmpty()) {
                AssistantMessage originalAssistant = response.chatResponse().getResults().get(0).getOutput();
                if (originalAssistant.getText() != null && !originalAssistant.getText().isEmpty()) {
                    AssistantMessage cleanAssistant = new AssistantMessage(originalAssistant.getText());
                    toolInteractionMessages.add(cleanAssistant);
                }
            }

            String conversationId = context.get(ChatMemory.CONVERSATION_ID).toString();
            String userId = context.get("userId").toString();
            if (conversationId != null && !toolInteractionMessages.isEmpty()) {
                this.threadPoolExecutor.execute(() -> {
                    try {
                        chatHistoryRepository.saveAssistantMessage(conversationId, null, userId, toolInteractionMessages.get(toolInteractionMessages.size() - 1).getText());
                        memoryRepository.saveAll(conversationId, toolInteractionMessages);
                        log.info("🎯 工具调用记忆存储成功，ID: {}, 共 {} 条消息", conversationId, toolInteractionMessages.size());
                    } catch (Exception e) {
                        log.error("数据库写入失败", e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("解析工具调用历史发生异常", e);
        }
    }

}