package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.config.ContextCompressionProperties;
import top.javarem.omni.model.compression.CompressionResult;
import top.javarem.omni.model.compression.MicroCompactor;
import top.javarem.omni.model.compression.SnipCompactor;
import top.javarem.omni.model.compression.TokenEstimator;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.repository.chat.MemoryRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ContextCompressionAdvisor implements BaseAdvisor {

    private final ChatModel chatModel;
    private final ContextCompressionProperties properties;
    private final SnipCompactor snipCompactor;
    private final MicroCompactor microCompactor;
    private final MemoryRepository memoryRepository;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean circuitBreakerOpen = false;

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
            @Qualifier("minimaxChatModel") ChatModel chatModel,
            ContextCompressionProperties properties,
            SnipCompactor snipCompactor,
            MicroCompactor microCompactor,
            MemoryRepository memoryRepository) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.snipCompactor = snipCompactor;
        this.microCompactor = microCompactor;
        this.memoryRepository = memoryRepository;
    }

    // ==================== BaseAdvisor 接口实现 ====================

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        if (!properties.isEnabled() || circuitBreakerOpen) {
            return chatClientRequest;
        }

        try {
            List<Message> allMessages = new ArrayList<>(chatClientRequest.prompt().getInstructions());
            List<Message> historyMessages = extractHistoryMessages(allMessages);
            int historyStartIndex = findHistoryStartIndex(allMessages);

            long preTokens = TokenEstimator.estimateMessages(historyMessages);
            log.info("[ContextCompression] 压缩前: {} 条消息, {} tokens", historyMessages.size(), preTokens);

            if (!isCompressionRequired(historyMessages)) {
                return chatClientRequest;
            }

            log.info("上下文已达到压缩阈值，开始执行压缩...");
            CompressionResult result = executeCompression(historyMessages);

            if (result.isCompacted()) {
                List<Message> compressedMessages = rebuildMessages(allMessages, historyStartIndex, result.getMessages());
                long postTokens = TokenEstimator.estimateMessages(compressedMessages);
                log.info("[ContextCompression] 压缩后: {} 条消息, {} tokens (释放: {}, 降幅: {:.1f}%)",
                        compressedMessages.size(),
                        postTokens,
                        result.getFreedTokens(),
                        100.0 * result.getFreedTokens() / preTokens);

                // 调试：检查前3条和后3条消息的结构
                log.debug("[ContextCompression] 压缩后消息结构:");
                for (int i = 0; i < Math.min(3, compressedMessages.size()); i++) {
                    Message msg = compressedMessages.get(i);
                    log.debug("  [{}] {}: text长度={}, hasToolCalls={}",
                            i, msg.getClass().getSimpleName(),
                            msg.getText() != null ? msg.getText().length() : 0,
                            msg instanceof AssistantMessage ?
                                    ((AssistantMessage) msg).getToolCalls() != null &&
                                            !((AssistantMessage) msg).getToolCalls().isEmpty() : false);
                }
                if (compressedMessages.size() > 6) {
                    for (int i = Math.max(6, compressedMessages.size() - 3); i < compressedMessages.size(); i++) {
                        Message msg = compressedMessages.get(i);
                        log.debug("  [{}] {}: text长度={}, hasToolCalls={}",
                                i, msg.getClass().getSimpleName(),
                                msg.getText() != null ? msg.getText().length() : 0,
                                msg instanceof AssistantMessage ?
                                        ((AssistantMessage) msg).getToolCalls() != null &&
                                                !((AssistantMessage) msg).getToolCalls().isEmpty() : false);
                    }
                }

                ChatClientRequest modifiedRequest = modifyRequestMessages(chatClientRequest, compressedMessages);

                // 异步保存压缩结果
                String conversationId = (String) modifiedRequest.context().get(ChatMemory.CONVERSATION_ID);
                if (conversationId != null) {
                    asyncSaveCompressionResult(conversationId, result);
                }

                return modifiedRequest;
            }

            return chatClientRequest;

        } catch (Exception e) {
            log.error("上下文压缩失败: {}", e.getMessage(), e);
            onFailure();
            return chatClientRequest;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 500;
    }

    // ==================== 工具方法 ====================

    private boolean isInjectedMessage(Message msg) {
        Map<String, Object> metadata = msg.getMetadata();
        return metadata != null &&
                Boolean.TRUE.equals(metadata.get(AdvisorContextConstants.OMNI_INJECTED));
    }

    private List<Message> extractHistoryMessages(List<Message> messages) {
        return messages.stream()
                .filter(msg -> !isInjectedMessage(msg))
                .filter(msg -> msg instanceof UserMessage || msg instanceof AssistantMessage)
                .collect(Collectors.toList());
    }

    private int findHistoryStartIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!isInjectedMessage(messages.get(i))) {
                return i;
            }
        }
        return messages.size();
    }

    public boolean isCompressionRequired(List<Message> messages) {
        long tokenCount = TokenEstimator.estimateMessages(messages);
        long contextThreshold = new BigDecimal(properties.getContextWindow())
                .multiply(BigDecimal.valueOf(properties.getThreshold())).longValue();

        if (tokenCount <= contextThreshold) return false;
        if (messages.size() <= properties.getKeepEarliest() + properties.getKeepRecent()) return false;

        return true;
    }

    private CompressionResult executeCompression(List<Message> historyMessages) {
        List<Message> compressed = new ArrayList<>(historyMessages);
        long preTokens = TokenEstimator.estimateMessages(compressed);

        if (properties.isSnipEnabled()) {
            SnipCompactor.SnipResult snipResult = snipCompactor.compact(compressed);
            compressed = snipResult.getMessages();
            log.info("SnipCompact: 释放 {} tokens", snipResult.getTokensFreed());
        }

        if (properties.isMicroCompactEnabled()) {
            MicroCompactor.MicroCompactResult microResult = microCompactor.compact(compressed);
            compressed = microResult.getMessages();
            log.info("MicroCompact: 清理 {} 条消息", microResult.getClearedCount());
        }

        // ========== 临时调试日志：ContextCompressionAdvisor 最终压缩结果 ==========
        long afterSnipMicroTokens = TokenEstimator.estimateMessages(compressed);
        log.info("========== ContextCompressionAdvisor 压缩后(AutoCompact前) ==========");
        for (int i = 0; i < compressed.size(); i++) {
            Message msg = compressed.get(i);
            String msgType = msg.getClass().getSimpleName();
            boolean hasToolCallsFlag = msg instanceof AssistantMessage &&
                    ((AssistantMessage) msg).getToolCalls() != null &&
                    !((AssistantMessage) msg).getToolCalls().isEmpty();
            String textPreview = msg.getText() == null ? "null" :
                    (msg.getText().length() <= 100 ? msg.getText().replace("\n", "\\n") :
                            msg.getText().substring(0, 100).replace("\n", "\\n") + "...");
            log.info("  [{}] {}: text长度={}, hasToolCalls={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    hasToolCallsFlag,
                    textPreview);
        }
        log.info("  总计: {} 条消息, {} tokens", compressed.size(), afterSnipMicroTokens);
        log.info("==============================================================");
        // ========== 临时调试日志结束 ==========

        if (!properties.isAutoCompactEnabled()) {
            return CompressionResult.notCompacted(compressed, preTokens);
        }

        if (compressed.size() <= properties.getKeepEarliest() + properties.getKeepRecent()) {
            return CompressionResult.notCompacted(compressed, preTokens);
        }

        int head = properties.getKeepEarliest();
        int tail = properties.getKeepRecent();
        List<Message> toSummarize = new ArrayList<>(
                compressed.subList(head, compressed.size() - tail));

        String summary = summarize(toSummarize);
        if (summary == null || summary.isBlank()) {
            log.warn("摘要生成失败，跳过压缩");
            return CompressionResult.failure("摘要生成失败");
        }

        String summaryContent = buildSummaryMessage(summary);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AdvisorContextConstants.OMNI_INJECTED, true);
        metadata.put("type", "compression_summary");
        UserMessage summaryMessage = UserMessage.builder()
                .text(summaryContent)
                .metadata(metadata)
                .build();

        List<Message> result = new ArrayList<>();
        result.addAll(compressed.subList(0, head));
        result.add(summaryMessage);
        result.addAll(compressed.subList(compressed.size() - tail, compressed.size()));

        long postTokens = TokenEstimator.estimateMessages(result);

        return CompressionResult.success(result, preTokens, postTokens, summaryMessage, summary);
    }

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

    private String buildSummaryMessage(String summary) {
        return String.format("""
                [上下文压缩摘要]

                %s

                继续对话，不要询问用户。
                """, summary);
    }

    private List<Message> rebuildMessages(List<Message> allMessages, int historyStartIndex, List<Message> compressedHistory) {
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < historyStartIndex; i++) {
            result.add(allMessages.get(i));
        }
        result.addAll(compressedHistory);
        return result;
    }

    private ChatClientRequest modifyRequestMessages(ChatClientRequest request, List<Message> newMessages) {
        return request.mutate()
                .prompt(request.prompt().mutate().messages(newMessages).build())
                .build();
    }

    private void asyncSaveCompressionResult(String conversationId, CompressionResult result) {
        new Thread(() -> {
            try {
                Long summaryMsgId = memoryRepository.saveUserMessage(
                        conversationId,
                        "system",
                        result.getSummaryContent()
                );
                memoryRepository.compress(
                        conversationId,
                        properties.getKeepEarliest(),
                        properties.getKeepRecent(),
                        summaryMsgId.toString()
                );
                log.info("压缩结果已保存到数据库，摘要 ID: {}", summaryMsgId);
                onSuccess();
            } catch (Exception e) {
                log.error("保存压缩结果失败", e);
                onFailure();
            }
        }).start();
    }

    // ==================== 电路断路器 ====================

    private void onSuccess() {
        consecutiveFailures.set(0);
    }

    private void onFailure() {
        if (!properties.isCircuitBreakerEnabled()) return;

        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.getMaxConsecutiveFailures()) {
            log.warn("电路断路器触发！连续失败 {} 次，暂停自动压缩", failures);
            circuitBreakerOpen = true;
        }
    }
}
