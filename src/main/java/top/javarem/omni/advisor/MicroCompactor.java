package top.javarem.omni.advisor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * MicroCompact - 时间衰减触发的小规模压缩
 *
 * <p>功能：
 * <ul>
 *   <li>距离上次 Assistant 消息超过 gapThresholdMinutes 时触发</li>
 *   <li>清除旧工具结果，仅保留最近 keepRecent 条</li>
 * </ul>
 *
 * <p>注意：只压缩历史消息，系统注入消息不参与压缩
 */
@Component
@Slf4j
public class MicroCompactor {

    /**
     * 默认时间衰减阈值（分钟）
     */
    private static final int DEFAULT_GAP_THRESHOLD_MINUTES = 60;

    /**
     * 默认保留最近工具结果数
     */
    private static final int DEFAULT_KEEP_RECENT = 5;

    private final int gapThresholdMinutes;
    private final int keepRecent;

    public MicroCompactor() {
        this(DEFAULT_GAP_THRESHOLD_MINUTES, DEFAULT_KEEP_RECENT);
    }

    public MicroCompactor(int gapThresholdMinutes, int keepRecent) {
        this.gapThresholdMinutes = gapThresholdMinutes;
        this.keepRecent = keepRecent;
    }

    /**
     * 执行 MicroCompact
     *
     * @param messages 消息列表
     * @param lastAssistantTime 上次 Assistant 消息时间（判断是否触发时间衰减）
     * @return 压缩结果
     */
    public MicroCompactResult compact(List<Message> messages, Timestamp lastAssistantTime) {
        if (messages == null || messages.isEmpty()) {
            return new MicroCompactResult(List.of(), false, 0, 0);
        }

        // 检查是否需要时间衰减清理
        if (!shouldTrigger(lastAssistantTime)) {
            return new MicroCompactResult(messages, false, 0, 0);
        }

        // 清理旧的工具结果
        return compactToolResults(messages);
    }

    /**
     * 执行 MicroCompact（使用消息列表推断时间）
     *
     * @param messages 消息列表
     * @return 压缩结果
     */
    public MicroCompactResult compact(List<Message> messages) {
        return compact(messages, null);
    }

    /**
     * 判断是否应该触发时间衰减
     */
    private boolean shouldTrigger(Timestamp lastAssistantTime) {
        if (lastAssistantTime == null) {
            return false;
        }
        long gapMillis = System.currentTimeMillis() - lastAssistantTime.getTime();
        long gapMinutes = gapMillis / (1000 * 60);
        return gapMinutes >= gapThresholdMinutes;
    }

    /**
     * 清理旧工具结果
     */
    private MicroCompactResult compactToolResults(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        long freedTokens = 0;
        int clearedCount = 0;

        // 识别并清理工具结果消息
        List<Message> toolResults = new ArrayList<>();
        List<Message> otherMessages = new ArrayList<>();

        for (Message msg : messages) {
            if (isToolResultMessage(msg)) {
                toolResults.add(msg);
            } else {
                otherMessages.add(msg);
            }
        }

        // 保留最近 KEEP_RECENT 条工具结果
        if (toolResults.size() > keepRecent) {
            List<Message> keepToolResults = toolResults.subList(
                    toolResults.size() - keepRecent,
                    toolResults.size()
            );
            List<Message> clearToolResults = toolResults.subList(
                    0,
                    toolResults.size() - keepRecent
            );

            for (Message cleared : clearToolResults) {
                freedTokens += TokenEstimator.estimateMessage(cleared);
                clearedCount++;
            }

            result.addAll(otherMessages);
            result.addAll(keepToolResults);
        } else {
            result.addAll(messages);
        }

        boolean compacted = clearedCount > 0;
        return new MicroCompactResult(result, compacted, freedTokens, clearedCount);
    }

    /**
     * 判断是否为工具结果消息
     */
    private boolean isToolResultMessage(Message msg) {
        // 工具结果通常是较长的 Assistant 消息
        if (!(msg instanceof AssistantMessage)) {
            return false;
        }
        String text = msg.getText();
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 工具结果通常是多行的
        return text.contains("\n") && text.length() > 150;
    }

    @Data
    public static class MicroCompactResult {
        /**
         * 压缩后的消息列表
         */
        private final List<Message> messages;

        /**
         * 是否执行了压缩
         */
        private final boolean compacted;

        /**
         * 释放的 token 数
         */
        private final long freedTokens;

        /**
         * 清理的消息数
         */
        private final int clearedCount;
    }
}
