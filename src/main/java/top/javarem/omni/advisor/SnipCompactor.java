package top.javarem.omni.advisor;

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
@Component
@Slf4j
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

        List<Message> compacted = new ArrayList<>();
        long freedTokens = 0;
        Message prevMessage = null;

        for (Message msg : messages) {
            // 1. 删除空消息
            if (isEmptyMessage(msg)) {
                freedTokens += TokenEstimator.estimateMessage(msg);
                continue;
            }

            // 2. 去除连续重复
            if (isDuplicate(prevMessage, msg)) {
                freedTokens += TokenEstimator.estimateMessage(msg);
                continue;
            }

            // 3. 截断超长工具输出
            Message processedMsg = truncateIfNeeded(msg);
            compacted.add(processedMsg);

            prevMessage = processedMsg;
        }

        return new SnipResult(compacted, freedTokens);
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

        String truncated = text.substring(0, MAX_TOOL_OUTPUT_LENGTH);
        String suffix = String.format(
                "\n\n[工具输出已截断，原长度 %d 字符]",
                text.length()
        );

        return createTruncatedMessage(msg, truncated + suffix);
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
