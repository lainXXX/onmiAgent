package top.javarem.omni.service.compression.layer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.compression.LayerResult;
import top.javarem.omni.model.compression.TokenEstimator;
import top.javarem.omni.service.compression.context.CompactionContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 1: SnipCompact - 快速本地压缩
 *
 * <p>功能：
 * <ul>
 *   <li>去除连续重复的 User/Assistant 消息</li>
 *   <li>删除空内容消息</li>
 *   <li>截断超长工具输出（非 JSON）</li>
 * </ul>
 *
 * <p>此层每轮必然执行，无 API 调用。
 */
@Slf4j
@Component
public class SnipCompactLayer implements CompressionLayer {

    private static final int ORDER = 1;
    private static final String NAME = "SnipCompact";

    /**
     * 工具输出最大保留长度
     */
    private static final int MAX_TOOL_OUTPUT_LENGTH = 100;

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
        return ctx.getProperties() == null || ctx.getProperties().isSnipEnabled();
    }

    @Override
    public LayerResult compress(CompactionContext ctx, List<Message> messages) {
        long preTokens = TokenEstimator.estimateMessages(messages);
        long startTime = System.currentTimeMillis();

        // ========== 临时调试日志：压缩前消息列表 ==========
        log.info("========== SnipCompact 压缩前 ==========");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String msgType = msg.getClass().getSimpleName();
            String textPreview = getTextPreview(msg.getText(), 100);
            boolean hasToolCalls = hasToolCalls(msg);
            log.info("  [{}] {}: text长度={}, hasToolCalls={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    hasToolCalls,
                    textPreview);
        }
        log.info("  总计: {} 条消息, {} tokens", messages.size(), preTokens);
        log.info("========================================");

        List<Message> compacted = new ArrayList<>();
        Message prevMessage = null;
        int emptyCount = 0;
        int duplicateCount = 0;
        int truncatedCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            // 1. 删除空消息
            if (isEmptyMessage(msg)) {
                log.debug("  [{}] SKIP (empty): {}", i, msg.getClass().getSimpleName());
                emptyCount++;
                continue;
            }

            // 2. 去除连续重复
            if (isDuplicate(prevMessage, msg)) {
                log.info("  [{}] REMOVE (duplicate): {} text=\"{}\"",
                        i, msg.getClass().getSimpleName(), getTextPreview(msg.getText(), 50));
                duplicateCount++;
                continue;
            }

            // 3. 截断超长工具输出
            Message processedMsg = truncateIfNeeded(msg);
            if (processedMsg != msg) {
                truncatedCount++;
                log.info("  [{}] TRUNCATE: {} 原始长度={}, 截断后={}",
                        i, msg.getClass().getSimpleName(),
                        msg.getText() != null ? msg.getText().length() : 0,
                        processedMsg.getText() != null ? processedMsg.getText().length() : 0);
            }
            compacted.add(processedMsg);
            prevMessage = processedMsg;
        }

        long freedTokens = preTokens - TokenEstimator.estimateMessages(compacted);
        Duration executionTime = Duration.ofMillis(System.currentTimeMillis() - startTime);

        // ========== 临时调试日志：压缩后消息列表 ==========
        log.info("========== SnipCompact 压缩后 ==========");
        for (int i = 0; i < compacted.size(); i++) {
            Message msg = compacted.get(i);
            String msgType = msg.getClass().getSimpleName();
            String textPreview = getTextPreview(msg.getText(), 100);
            boolean hasToolCalls = hasToolCalls(msg);
            log.info("  [{}] {}: text长度={}, hasToolCalls={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    hasToolCalls,
                    textPreview);
        }
        log.info("  总计: {} 条消息, {} tokens (释放: {}, 空消息: {}, 重复: {}, 截断: {})",
                compacted.size(), TokenEstimator.estimateMessages(compacted),
                freedTokens, emptyCount, duplicateCount, truncatedCount);
        log.info("========================================");

        return LayerResult.builder()
                .layerOrder(ORDER)
                .layerName(NAME)
                .messages(compacted)
                .tokensFreed(freedTokens)
                .messagesCleared(emptyCount + duplicateCount)
                .didWork(emptyCount + duplicateCount > 0)
                .executionTime(executionTime)
                .build();
    }

    private String getTextPreview(String text, int maxLen) {
        if (text == null) return "null";
        if (text.length() <= maxLen) return text.replace("\n", "\\n");
        return text.substring(0, maxLen).replace("\n", "\\n") + "...";
    }

    private boolean hasToolCalls(Message msg) {
        if (msg instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) msg;
            return am.getToolCalls() != null && !am.getToolCalls().isEmpty();
        }
        return false;
    }

    /**
     * 判断消息是否为空
     */
    private boolean isEmptyMessage(Message msg) {
        String text = msg.getText();
        return text == null || text.isBlank();
    }

    /**
     * 判断两条消息是否连续重复
     */
    private boolean isDuplicate(Message prev, Message current) {
        if (prev == null || current == null) {
            return false;
        }

        // 只检查同类型消息连续重复
        if (prev.getClass() != current.getClass()) {
            return false;
        }

        String prevText = prev.getText();
        String currentText = current.getText();

        if (prevText == null || currentText == null) {
            return false;
        }

        return prevText.equals(currentText);
    }

    /**
     * 截断超长工具输出，但不破坏结构
     */
    private Message truncateIfNeeded(Message msg) {
        String text = msg.getText();
        if (text == null || text.length() <= MAX_TOOL_OUTPUT_LENGTH) {
            return msg;
        }

        // JSON 不能简单截断，否则破坏结构
        // 方案：保留 JSON 完整结构，让 AutoCompact 通过 LLM 摘要处理
        if (isJson(text)) {
            return msg;
        }

        // 非 JSON 的工具输出（如命令执行结果），可以安全截断
        String truncated = text.substring(0, MAX_TOOL_OUTPUT_LENGTH);
        String suffix = String.format(
                "\n\n[工具输出已截断，原长度 %d 字符]",
                text.length()
        );

        return createTruncatedMessage(msg, truncated + suffix);
    }

    /**
     * 判断文本是否为 JSON
     */
    private boolean isJson(String text) {
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    /**
     * 判断文本是否像工具输出
     */
    private boolean looksLikeToolOutput(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String trimmed = text.trim();
        // JSON 对象或数组
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return true;
        }
        // 命令输出（通常是多行）
        return trimmed.contains("\n") && text.length() > 200;
    }

    /**
     * 创建截断后的消息
     */
    private Message createTruncatedMessage(Message original, String newContent) {
        if (original instanceof UserMessage) {
            return new UserMessage(newContent);
        } else if (original instanceof AssistantMessage) {
            return new AssistantMessage(newContent);
        } else {
            return new UserMessage(newContent);
        }
    }
}
