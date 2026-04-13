package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.config.ContextCompressionProperties;
import top.javarem.omni.repository.chat.MemoryRepository;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ContextCompressionAdvisor extends ToolCallAdvisor {

    private final ChatModel chatModel;

    private final ContextCompressionProperties properties;

    private final ThreadPoolExecutor threadPoolExecutor;

    private final MemoryRepository memoryRepository;

    private final ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();

    private final VectorStore vectorStore;

    //   翻译：你将长对话压缩成简洁、事实性的摘要。捕获关键决策、实体、意图和未解决事项。
    private final String summarySystemPrompt = """
            You are a Context Compression Engine. Your task is to update the system's long-term memory based on the ongoing conversation.
                        
            Inputs provided to you may contain an <existing_summary> and recent <new_messages>.
                        
            # Requirements:
            1. MERGE: If an existing summary exists, seamlessly integrate new developments into it. Do NOT lose critical early decisions or constraints.
            2. RETAIN: Keep exact file paths, commands, code structure decisions, and resolved bugs.
            3. PRUNE: Remove outdated next steps that have now been completed. Exclude all conversational filler.
            4. FORMAT: Use dense Markdown bullet points.
                        
            # Constraints:
            - Do NOT call any tools.
            - Do NOT include introductory phrases like "Here is the summary".
            - Output ONLY the raw, updated summary content.
            """;

    protected ContextCompressionAdvisor(ToolCallingManager toolCallingManager, int advisorOrder, boolean conversationHistoryEnabled, boolean streamToolCallResponses, ChatModel chatModel, ContextCompressionProperties properties, ThreadPoolExecutor threadPoolExecutor, MemoryRepository memoryRepository, VectorStore vectorStore) {
        super(toolCallingManager, advisorOrder, conversationHistoryEnabled, streamToolCallResponses);
        this.chatModel = chatModel;
        this.properties = properties;
        this.threadPoolExecutor = threadPoolExecutor;
        this.memoryRepository = memoryRepository;
        this.vectorStore = vectorStore;
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCall(ChatClientRequest chatClientRequest, ChatClientResponse chatClientResponse, ToolExecutionResult toolExecutionResult) {
        return super.doGetNextInstructionsForToolCall(chatClientRequest, chatClientResponse, toolExecutionResult);
    }

    @Override
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse chatClientResponse, CallAdvisorChain callAdvisorChain) {
        return super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        return super.doBeforeCall(chatClientRequest, callAdvisorChain);
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse chatClientResponse, CallAdvisorChain callAdvisorChain) {
        return super.doAfterCall(chatClientResponse, callAdvisorChain);
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return super.doInitializeLoopStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return super.doBeforeStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    protected ChatClientResponse doAfterStream(ChatClientResponse chatClientResponse, StreamAdvisorChain streamAdvisorChain) {
        return super.doAfterStream(chatClientResponse, streamAdvisorChain);
    }

    @Override
    protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> chatClientResponseFlux, StreamAdvisorChain streamAdvisorChain) {
        return super.doFinalizeLoopStream(chatClientResponseFlux, streamAdvisorChain);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest chatClientRequest, ChatClientResponse chatClientResponse, ToolExecutionResult toolExecutionResult) {
        return super.doGetNextInstructionsForToolCallStream(chatClientRequest, chatClientResponse, toolExecutionResult);
    }


    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        return after(request, response);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 1. 创建一个临时的聚合器
        ChatClientMessageAggregator localAggregator = new ChatClientMessageAggregator();
        // 2. 用来存放聚合后的最终完整响应
        AtomicReference<ChatClientResponse> fullResponseRef = new AtomicReference<>();

        return chain.nextStream(request)
                .doOnNext(response -> {
                    // 每来一个碎片，都扔进聚合器里攒着
                    // 注意：aggregate 方法内部会处理流的合并逻辑
                    localAggregator.aggregateChatClientResponse(Flux.just(response), fullResponseRef::set)
                            .subscribe(); // 触发聚合动作
                })
                .doOnTerminate(() -> {
                    // 当整个流结束（完成或错误）时
                    ChatClientResponse completeResponse = fullResponseRef.get();
                    if (completeResponse != null && completeResponse.chatResponse() != null) {
                        // 异步执行压缩逻辑，不阻塞主流返回
                        threadPoolExecutor.execute(() -> {
                            try {
                                after(request, completeResponse);
                            } catch (Exception e) {
                                log.error("流结束后异步压缩失败", e);
                            }
                        });
                    }
                });
    }

    @Override
    public String getName() {
        return "ContextCompressionAdvisor";
    }

    @Override
    public int getOrder() {
        return 4000;
    }

    private ChatClientResponse after(ChatClientRequest request, ChatClientResponse response) {
        if (!properties.isEnabled()) {
            log.info("上下文压缩功能未启用");
            return response;
        }
//        if (!AdvisorContextConstants.Phase.COMPLETED.equals(response.context().get(AdvisorContextConstants.TOOL_CALL_PHASE))) {
//            return response;
//        }
        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        // 获取本次对话返回的 Assistant 消息
        String lastAssistantMessage = response.chatResponse().getResult().getOutput().getText();
        messages.add(new AssistantMessage(lastAssistantMessage));
        List<Message> conversationMessage = messages.stream().filter(m -> m instanceof UserMessage || m instanceof AssistantMessage).collect(Collectors.toList());

        if (isCompressionRequired(conversationMessage)) {
            String conversationId = (String) request.context().get(ChatMemory.CONVERSATION_ID);
            if (conversationId == null) {
                log.warn("未找到 conversationId，跳过压缩");
                return response;
            }
            log.info("上下文已达到压缩阈值，进行压缩处理 会话ID {}", conversationId);
            int head = properties.getKeepEarliest();
            int tail = properties.getKeepRecent();
            List<Message> toSummarize = new ArrayList<>(conversationMessage.subList(head, conversationMessage.size() - tail));
            threadPoolExecutor.execute(() -> {
                try {
                    String summarizedContext = summarize(toSummarize);
                    saveSummary(conversationId, summarizedContext);
                    // 删除中间历史记录
                    deleteRecords(conversationId, head, tail);
                } catch (Exception e) {
                    log.error("上下文压缩失败: {}", e.getMessage(), e);
                }
            });

        }
        return response;
    }

    /**
     * 判断当前 Prompt 是否达到需要进行上下文压缩的临界点
     *
     * @return true 表示需要立即进行压缩处理
     */
    public boolean isCompressionRequired(List<Message> messages) {
        long tokenCount = estimateTokens(messages);
        long contextThreshold = new BigDecimal(properties.getContextWindow()).multiply(properties.getThreshold()).longValue();
        // 如果当前对话的 token 数量未达到阈值，则不进行压缩
        if (tokenCount <= contextThreshold) return false;
        // 当总消息数小于等于保留数时，才不需要压缩
        if (messages.size() <= properties.getKeepEarliest() + properties.getKeepRecent()) return false;
        return true;
    }

    /**
     * 调用轻量大模型对话进行压缩
     */
    private String summarize(List<Message> messages) {
        messages.add(0, new SystemMessage(summarySystemPrompt));
        return ChatClient.builder(chatModel).build()
                .prompt()
                .messages(messages)
                .options(OpenAiChatOptions.builder()
                        // 设置低温度，输出结果稳定
                        .temperature(0.1)
//                        .maxTokens(1200)
                        .build())
                .call()
                .content();
    }

    /**
     * 估算消息的 token 数量（粗略估算）
     * 中文约 1 字符/token，英文约 0.75 单词/token
     */
    private long estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }


        int totalChars = 0;
        for (Message msg : messages) {
            String content = msg.getText();
            if (content != null) {
                totalChars += content.length();
            }
        }
        // 粗略估算token
        return (long) (totalChars * 0.75);
    }

    /**
     * 删除中间历史记录
     * @param conversationId 会话ID
     * @param keepHead 保留头部记录数
     * @param keepTail 保留尾部记录数
     */
    public void deleteRecords(String conversationId, int keepHead, int keepTail) {
        int deletedCount = memoryRepository.compress(conversationId, keepHead, keepTail);
        log.info("压缩完成，共删除了 {} 条中间历史记录。", deletedCount);
    }

    /**
     * 将摘要保存到向量数据库
     *
     * @param conversationId 会话ID
     * @param summary        摘要内容
     */
    private void saveSummary(String conversationId, String summary) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("conversation_id", conversationId);
            metadata.put("type", "summary");
            metadata.put("timestamp", System.currentTimeMillis());

            // 使用 UUID 作为 ID
            Document document = Document.builder()
                    .id(UUID.randomUUID().toString())
                    .text(summary)
                    .metadata(metadata)
                    .build();

            vectorStore.add(List.of(document));
            log.info("摘要已存入向量数据库, conversationId: {}", conversationId);
        } catch (Exception e) {
            log.error("存入向量数据库失败: {}", e.getMessage(), e);
        }
    }
}