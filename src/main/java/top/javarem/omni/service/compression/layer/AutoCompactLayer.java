package top.javarem.omni.service.compression.layer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.compression.LayerResult;
import top.javarem.omni.model.compression.TokenEstimator;
import top.javarem.omni.service.compression.context.CompactionContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 4: AutoCompact - LLM 驱动的语义压缩
 *
 * <p>功能：
 * <ul>
 *   <li>当 token 数量超过 contextWindow - 13,000 时触发</li>
 *   <li>调用 LLM 生成摘要，保留关键信息</li>
 *   <li>创建边界标记，便于识别压缩点</li>
 * </ul>
 *
 * <p>此层按需执行，触发阈值较高。
 */
@Slf4j
@Component
public class AutoCompactLayer implements CompressionLayer {

    private static final int ORDER = 4;
    private static final String NAME = "AutoCompact";

    /**
     * AutoCompact 触发阈值 buffer
     */
    private static final int DEFAULT_BUFFER_TOKENS = 13_000;

    /**
     * 摘要最大 token 数
     */
    private static final int DEFAULT_MAX_SUMMARY_TOKENS = 4000;

    private final ChatModel chatModel;

    private final String summarySystemPrompt = """
            You are a Context Compression Engine. Your task is to create a detailed summary of the conversation.

            # Requirements:
            1. MERGE: If an existing summary exists, integrate new developments into it.
            2. RETAIN: Keep exact file paths, commands, code structure decisions.
            3. PRUNE: Remove outdated next steps that have been completed. Exclude conversational filler.
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
            - Output ONLY the raw summary content.
            """;

    public AutoCompactLayer(@Qualifier("minimaxChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(CompactionContext ctx) {
        if (ctx.getProperties() == null) {
            return true;
        }
        // 检查电路断路器
        if (ctx.isCircuitBreakerOpen()) {
            log.warn("AutoCompact: 电路断路器打开，跳过");
            return false;
        }
        return ctx.getProperties().isAutoCompactEnabled();
    }

    @Override
    public boolean shouldSkip(CompactionContext ctx, List<Message> messages) {
        // 检查是否超过阈值
        long threshold = getThreshold(ctx);
        long tokenCount = TokenEstimator.estimateMessages(messages);
        return tokenCount <= threshold;
    }

    @Override
    public LayerResult compress(CompactionContext ctx, List<Message> messages) {
        long preTokens = TokenEstimator.estimateMessages(messages);
        long startTime = System.currentTimeMillis();

        // 构建要摘要的消息（排除系统消息和最近的消息）
        int keepEarliest = getKeepEarliest(ctx);
        int keepRecent = getKeepRecent(ctx);

        List<Message> toSummarize;
        if (messages.size() <= keepEarliest + keepRecent) {
            // 消息太少，不需要摘要
            return LayerResult.noOp(ORDER, NAME);
        }

        toSummarize = new ArrayList<>(messages.subList(keepEarliest, messages.size() - keepRecent));

        // 调用 LLM 生成摘要
        String summary = generateSummary(toSummarize);

        if (summary == null || summary.isBlank()) {
            log.warn("AutoCompact: 摘要生成失败");
            ctx.onCircuitBreakerFailure();
            return LayerResult.builder()
                    .layerOrder(ORDER)
                    .layerName(NAME)
                    .messages(messages)
                    .tokensFreed(0)
                    .messagesCleared(0)
                    .didWork(false)
                    .executionTime(Duration.ofMillis(System.currentTimeMillis() - startTime))
                    .build();
        }

        // 成功，重置电路断路器
        ctx.onCircuitBreakerSuccess();

        // 构建压缩后的消息
        List<Message> compacted = buildCompactedMessages(
                messages,
                keepEarliest,
                summary
        );

        long postTokens = TokenEstimator.estimateMessages(compacted);
        long freedTokens = preTokens - postTokens;
        Duration executionTime = Duration.ofMillis(System.currentTimeMillis() - startTime);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("summaryLength", summary.length());
        metadata.put("keepEarliest", keepEarliest);
        metadata.put("keepRecent", keepRecent);
        metadata.put("compactedMessageCount", toSummarize.size());

        log.info("AutoCompact: 释放 {} tokens, {} 条消息被摘要",
                freedTokens, toSummarize.size());

        return LayerResult.builder()
                .layerOrder(ORDER)
                .layerName(NAME)
                .messages(compacted)
                .tokensFreed(freedTokens)
                .messagesCleared(toSummarize.size())
                .didWork(true)
                .executionTime(executionTime)
                .metadata(metadata)
                .build();
    }

    /**
     * 获取阈值
     */
    private long getThreshold(CompactionContext ctx) {
        if (ctx.getProperties() != null && ctx.getProperties().getContextWindow() != null) {
            int buffer = ctx.getProperties().getBufferTokens() != null
                    ? ctx.getProperties().getBufferTokens()
                    : DEFAULT_BUFFER_TOKENS;
            return ctx.getProperties().getContextWindow() - buffer;
        }
        return 200_000 - DEFAULT_BUFFER_TOKENS;
    }

    /**
     * 获取保留头部数
     */
    private int getKeepEarliest(CompactionContext ctx) {
        if (ctx.getProperties() != null && ctx.getProperties().getKeepEarliest() != null) {
            return ctx.getProperties().getKeepEarliest();
        }
        return 1;
    }

    /**
     * 获取保留尾部数
     */
    private int getKeepRecent(CompactionContext ctx) {
        if (ctx.getProperties() != null && ctx.getProperties().getKeepRecent() != null) {
            return ctx.getProperties().getKeepRecent();
        }
        return 2;
    }

    /**
     * 生成摘要
     */
    private String generateSummary(List<Message> messages) {
        try {
            // 构建 prompt
            StringBuilder sb = new StringBuilder();
            sb.append(summarySystemPrompt);
            sb.append("\n\n# Conversation to Summarize:\n\n");

            for (Message msg : messages) {
                String role = msg instanceof UserMessage ? "User"
                        : msg instanceof AssistantMessage ? "Assistant"
                        : msg instanceof SystemMessage ? "System"
                        : "Unknown";
                sb.append(String.format("[%s]: %s\n\n", role, msg.getText()));
            }

            // 调用 LLM
            // 注意：这是简化版本，实际应该使用 ChatClient
            // TODO: 使用 ChatClient.builder().build().prompt()...

            return "Summary generation not yet implemented - requires ChatClient integration";

        } catch (Exception e) {
            log.error("生成摘要失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建压缩后的消息列表
     */
    private List<Message> buildCompactedMessages(List<Message> original,
                                                 int keepEarliest,
                                                 String summary) {
        List<Message> result = new ArrayList<>();

        // 保留头部消息
        if (keepEarliest > 0 && original.size() > keepEarliest) {
            result.addAll(original.subList(0, keepEarliest));
        }

        // 添加压缩边界标记
        String boundaryText = String.format("""
                [上下文压缩摘要]

                %s

                继续对话，不要询问用户。
                """, summary);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "compression_summary");
        metadata.put("compacted", true);

        UserMessage summaryMessage = UserMessage.builder()
                .text(boundaryText)
                .metadata(metadata)
                .build();
        result.add(summaryMessage);

        // 保留尾部消息
        int keepRecent = 2;
        if (original.size() > keepEarliest + keepRecent) {
            result.addAll(original.subList(original.size() - keepRecent, original.size()));
        }

        return result;
    }
}
