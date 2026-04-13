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
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.config.ContextCompressionProperties;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.repository.chat.MemoryRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ContextCompressionAdvisor extends ToolCallAdvisor {

    private final ChatModel chatModel;
    private final ContextCompressionProperties properties;
    private final SnipCompactor snipCompactor;
    private final MicroCompactor microCompactor;
    private final MemoryRepository memoryRepository;
    private final ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();

    // 电路断路器
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean circuitBreakerOpen = false;

    // 摘要 System Prompt
    private final String summarySystemPrompt = """
            You are a Context Compression Engine. Your task is to update the system's long-term memory based on the ongoing conversation.

            Inputs provided to you may contain an <existing_summary> and recent <new_messages>.

            # Requirements:
            1. MERGE: If an existing summary exists, seamlessly integrate new developments into it. Do NOT lose critical early decisions or constraints.
            2. RETAIN: Keep exact file paths, commands, code structure decisions, and resolved bugs.
            3. PRUNE: Remove outdated next steps that have now been completed. Exclude all conversational filler.
            4. FORMAT: Use dense Markdown bullet points.

            # Output Format:
            Use <analysis> and <summary> tags:

            <analysis>
            [分析新旧内容之间的关系和变化]
            </analysis>

            <summary>
            1. **主要请求和意图**: [详细描述]
            2. **关键技术概念**: [列表]
            3. **文件和代码段**: [文件列表 + 摘要]
            4. **错误和修复**: [错误描述 + 解决方法]
            5. **问题解决**: [已解决 + 进行中]
            6. **所有用户消息**: [消息列表]
            7. **待处理任务**: [任务列表]
            8. **当前工作**: [描述]
            9. **可选的下一步**: [下一步]
            </summary>

            # Constraints:
            - Do NOT call any tools.
            - Do NOT include introductory phrases like "Here is the summary".
            - Output ONLY the raw, updated summary content.
            """;

    protected ContextCompressionAdvisor(
            ToolCallingManager toolCallingManager,
            int advisorOrder,
            boolean conversationHistoryEnabled,
            boolean streamToolCallResponses,
            ChatModel chatModel,
            ContextCompressionProperties properties,
            SnipCompactor snipCompactor,
            MicroCompactor microCompactor,
            MemoryRepository memoryRepository) {
        super(toolCallingManager, advisorOrder, conversationHistoryEnabled, streamToolCallResponses);
        this.chatModel = chatModel;
        this.properties = properties;
        this.snipCompactor = snipCompactor;
        this.microCompactor = microCompactor;
        this.memoryRepository = memoryRepository;
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
        // ========== 关键改进：在 API 调用之前执行压缩 ==========
        if (!properties.isEnabled() || circuitBreakerOpen) {
            return super.doBeforeCall(chatClientRequest, callAdvisorChain);
        }

        try {
            List<Message> allMessages = new ArrayList<>(chatClientRequest.prompt().getInstructions());

            // 提取历史消息（排除系统注入）
            List<Message> historyMessages = extractHistoryMessages(allMessages);
            int historyStartIndex = findHistoryStartIndex(allMessages);

            // 检查是否需要压缩
            if (!isCompressionRequired(historyMessages)) {
                return super.doBeforeCall(chatClientRequest, callAdvisorChain);
            }

            log.info("上下文已达到压缩阈值，开始执行压缩...");

            // 执行压缩
            CompressionResult result = executeCompression(historyMessages);

            if (result.isCompacted()) {
                // 重组消息列表：系统注入 + 压缩后的历史（含摘要）
                List<Message> compressedMessages = rebuildMessages(allMessages, historyStartIndex, result.getMessages());
                // 修改 request
                chatClientRequest = modifyRequestMessages(chatClientRequest, compressedMessages);

                // 异步保存摘要到数据库
                String conversationId = (String) chatClientRequest.context().get(ChatMemory.CONVERSATION_ID);
                if (conversationId != null) {
                    final String convId = conversationId;
                    final CompressionResult finalResult = result;
                    // 异步执行数据库操作
                    new Thread(() -> {
                        try {
                            saveCompressionResult(convId, finalResult);
                        } catch (Exception e) {
                            log.error("保存压缩结果失败", e);
                        }
                    }).start();
                }
            }

            return super.doBeforeCall(chatClientRequest, callAdvisorChain);

        } catch (Exception e) {
            log.error("上下文压缩失败: {}", e.getMessage(), e);
            onFailure();
            return super.doBeforeCall(chatClientRequest, callAdvisorChain);
        }
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
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request);
    }

    @Override
    public String getName() {
        return "ContextCompressionAdvisor";
    }

    @Override
    public int getOrder() {
        return 4000;
    }

    // ==================== 核心方法 ====================

    /**
     * 检测是否为系统注入消息
     */
    private boolean isInjectedMessage(Message msg) {
        Map<String, Object> metadata = msg.getMetadata();
        return metadata != null &&
                Boolean.TRUE.equals(metadata.get(AdvisorContextConstants.OMNI_INJECTED));
    }

    /**
     * 提取历史消息（用于压缩）
     */
    private List<Message> extractHistoryMessages(List<Message> messages) {
        return messages.stream()
                .filter(msg -> !isInjectedMessage(msg))
                .filter(msg -> msg instanceof UserMessage || msg instanceof AssistantMessage)
                .collect(Collectors.toList());
    }

    /**
     * 找到历史消息在列表中的起始位置
     */
    private int findHistoryStartIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!isInjectedMessage(messages.get(i))) {
                return i;
            }
        }
        return messages.size();
    }

    /**
     * 判断当前上下文是否达到压缩阈值
     */
    public boolean isCompressionRequired(List<Message> messages) {
        long tokenCount = TokenEstimator.estimateMessages(messages);
        long contextThreshold = new BigDecimal(properties.getContextWindow())
                .multiply(properties.getThreshold()).longValue();

        if (tokenCount <= contextThreshold) return false;
        if (messages.size() <= properties.getKeepEarliest() + properties.getKeepRecent()) return false;

        return true;
    }

    /**
     * 执行压缩（SnipCompact → MicroCompact → AutoCompact）
     */
    private CompressionResult executeCompression(List<Message> historyMessages) {
        List<Message> compressed = new ArrayList<>(historyMessages);
        long preTokens = TokenEstimator.estimateMessages(compressed);

        // Layer 1: SnipCompact
        if (properties.isSnipEnabled()) {
            SnipCompactor.SnipResult snipResult = snipCompactor.compact(compressed);
            compressed = snipResult.getMessages();
            log.info("SnipCompact: 释放 {} tokens", snipResult.getTokensFreed());
        }

        // Layer 2: MicroCompact
        if (properties.isMicroCompactEnabled()) {
            MicroCompactor.MicroCompactResult microResult = microCompactor.compact(compressed);
            compressed = microResult.getMessages();
            log.info("MicroCompact: 清理 {} 条消息", microResult.getClearedCount());
        }

        // Layer 3: AutoCompact - 生成摘要
        if (!properties.isAutoCompactEnabled()) {
            return CompressionResult.notCompacted(compressed, preTokens);
        }

        // 检查是否仍然需要 AutoCompact
        if (compressed.size() <= properties.getKeepEarliest() + properties.getKeepRecent()) {
            return CompressionResult.notCompacted(compressed, preTokens);
        }

        // 提取要压缩的部分（保留头尾）
        int head = properties.getKeepEarliest();
        int tail = properties.getKeepRecent();
        List<Message> toSummarize = new ArrayList<>(
                compressed.subList(head, compressed.size() - tail));

        // 调用 LLM 生成摘要
        String summary = summarize(toSummarize);
        if (summary == null || summary.isBlank()) {
            log.warn("摘要生成失败，跳过压缩");
            return CompressionResult.failure("摘要生成失败");
        }

        // 构建摘要消息
        String summaryContent = buildSummaryMessage(summary);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AdvisorContextConstants.OMNI_INJECTED, true);
        metadata.put("type", "compression_summary");
        UserMessage summaryMessage = UserMessage.builder()
                .text(summaryContent)
                .metadata(metadata)
                .build();

        // 重组：头部 + 摘要 + 尾部
        List<Message> result = new ArrayList<>();
        result.addAll(compressed.subList(0, head));
        result.add(summaryMessage);
        result.addAll(compressed.subList(compressed.size() - tail, compressed.size()));

        long postTokens = TokenEstimator.estimateMessages(result);

        return CompressionResult.success(result, preTokens, postTokens, summaryMessage, summary);
    }

    /**
     * 调用轻量大模型生成摘要
     */
    private String summarize(List<Message> messages) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(summarySystemPrompt));
        promptMessages.addAll(messages);

        try {
            return ChatClient.builder(chatModel).build()
                    .prompt()
                    .messages(promptMessages)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.1)
                            .maxTokens(properties.getMaxSummaryTokens())
                            .build())
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("生成摘要失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建摘要消息内容
     */
    private String buildSummaryMessage(String summary) {
        return String.format("""
                [上下文压缩摘要]

                %s

                继续对话，不要询问用户。
                """, summary);
    }

    /**
     * 重组消息列表
     */
    private List<Message> rebuildMessages(List<Message> allMessages, int historyStartIndex, List<Message> compressedHistory) {
        List<Message> result = new ArrayList<>();

        // 添加系统注入消息
        for (int i = 0; i < historyStartIndex; i++) {
            result.add(allMessages.get(i));
        }

        // 添加压缩后的历史
        result.addAll(compressedHistory);

        return result;
    }

    /**
     * 修改 request 中的消息列表
     */
    private ChatClientRequest modifyRequestMessages(ChatClientRequest request, List<Message> newMessages) {
        return request.mutate()
                .prompt(request.prompt().mutate().messages(newMessages).build())
                .build();
    }

    /**
     * 保存压缩结果到数据库
     */
    private void saveCompressionResult(String conversationId, CompressionResult result) {
        try {
            // 保存摘要消息
            Long summaryMsgId = memoryRepository.saveUserMessage(
                    conversationId,
                    "system",
                    result.getSummaryContent()
            );

            // 软删除中间记录
            memoryRepository.compress(
                    conversationId,
                    properties.getKeepEarliest(),
                    properties.getKeepRecent(),
                    summaryMsgId.toString()
            );

            log.info("压缩结果已保存到数据库，摘要 ID: {}", summaryMsgId);
            onSuccess();

        } catch (Exception e) {
            log.error("保存压缩结果失败: {}", e.getMessage(), e);
            onFailure();
        }
    }

    // ==================== 电路断路器 ====================

    private void onSuccess() {
        consecutiveFailures.set(0);
    }

    private void onFailure() {
        if (!properties.isCircuitBreakerEnabled()) return;

        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.getMaxConsecutiveFailures()) {
            log.warn("电路断路器触发！连续失败 {} 次，暂停自动压缩",
                    failures);
            circuitBreakerOpen = true;
        }
    }
}
