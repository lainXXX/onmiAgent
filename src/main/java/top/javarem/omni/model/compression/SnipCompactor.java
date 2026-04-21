package top.javarem.omni.model.compression;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SnipCompact - 无 API 调用的快速压缩
 *
 * <p>功能：
 * <ul>
 *   <li>去除连续重复的 User/Assistant 消息</li>
 *   <li>截断超长工具输出（追加截断提示，不破坏结构）</li>
 *   <li>删除空内容消息</li>
 * </ul>
 */
@Slf4j
@Component
public class SnipCompactor {

    /**
     * 工具输出最大保留长度
     */
    private static final int MAX_TOOL_OUTPUT_LENGTH = 100;

    /**
     * 执行 SnipCompact
     *
     * @param messages 原始消息列表
     * @return 压缩结果
     */
    public SnipResult compact(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new SnipResult(List.of(), 0);
        }

        // ========== 临时调试日志：SnipCompactor 压缩前 ==========
        log.info("========== SnipCompactor 原始消息 ==========");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String msgType = msg.getClass().getSimpleName();
            boolean hasToolCallsFlag = hasToolCalls(msg);
            String textPreview = getTextPreview(msg.getText(), 100);
            log.info("  [{}] {}: text长度={}, hasToolCalls={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    hasToolCallsFlag,
                    textPreview);
        }
        log.info("============================================");
        // ========== 临时调试日志结束 ==========

        List<Message> compacted = new ArrayList<>();
        long freedTokens = 0;
        Message prevMessage = null;
        int emptyCount = 0;
        int duplicateCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            // 1. 删除空消息
            if (isEmptyMessage(msg)) {
                freedTokens += TokenEstimator.estimateMessage(msg);
                emptyCount++;
                log.debug("SnipCompact[{}] removed empty message: {}", i, msg.getClass().getSimpleName());
                continue;
            }

            // 2. 去除连续重复
            if (isDuplicate(prevMessage, msg)) {
                freedTokens += TokenEstimator.estimateMessage(msg);
                duplicateCount++;
                boolean hasToolCallsFlag = hasToolCalls(msg);
                log.warn("SnipCompact[{}] REMOVE (duplicate): {} (text={}, hasToolCalls={}, textLength={})",
                        i, msg.getClass().getSimpleName(),
                        truncateForLog(msg.getText()),
                        hasToolCallsFlag,
                        msg.getText() != null ? msg.getText().length() : 0);
                continue;
            }

            // 3. 截断超长工具输出
            Message processedMsg = truncateIfNeeded(msg);
            compacted.add(processedMsg);

            prevMessage = processedMsg;
        }

        // ========== 临时调试日志：SnipCompactor 压缩后 ==========
        log.info("========== SnipCompactor 压缩后 ==========");
        for (int i = 0; i < compacted.size(); i++) {
            Message msg = compacted.get(i);
            String msgType = msg.getClass().getSimpleName();
            boolean hasToolCallsFlag = hasToolCalls(msg);
            String textPreview = getTextPreview(msg.getText(), 100);
            log.info("  [{}] {}: text长度={}, hasToolCalls={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    hasToolCallsFlag,
                    textPreview);
        }
        log.info("==========================================");
        // ========== 临时调试日志结束 ==========

        log.info("SnipCompact executes: {} tokens freed, {} empty removed, {} duplicates removed",
                freedTokens, emptyCount, duplicateCount);

        return new SnipResult(compacted, freedTokens);
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

    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
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

        // 如果当前消息有 toolCalls，不视为重复（toolCalls 不同 = 意图不同）
        if (current instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) current;
            if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                return false;
            }
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
     *
     * <p>追加截断提示，防止 LLM 产生幻觉
     */
    private Message truncateIfNeeded(Message msg) {
        String text = msg.getText();
        if (text == null || text.length() <= MAX_TOOL_OUTPUT_LENGTH) {
            return msg;
        }

        // 检查是否可能为工具输出（通常是 JSON 或较长文本）
        if (!looksLikeToolOutput(text)) {
            return msg;
        }

        // JSON 不能简单截断，否则破坏结构
        // 方案：保留 JSON 完整结构，让 AutoCompact 通过 LLM 摘要处理
        if (isJson(text)) {
            // JSON 保持完整，不在此截断
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

    @Data
    public static class SnipResult {
        private final List<Message> messages;
        private final long tokensFreed;
    }
}
