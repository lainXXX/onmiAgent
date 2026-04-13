package top.javarem.omni.advisor;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 上下文压缩结果
 */
@Data
public class CompressionResult {

    /**
     * 压缩后的消息列表
     */
    private List<Message> messages;

    /**
     * 压缩前 token 数
     */
    private long preCompressionTokens;

    /**
     * 压缩后 token 数
     */
    private long postCompressionTokens;

    /**
     * 释放的 token 数
     */
    private long freedTokens;

    /**
     * 是否执行了压缩
     */
    private boolean compacted;

    /**
     * 摘要消息（压缩后插入历史）
     */
    private Message summaryMessage;

    /**
     * 摘要内容
     */
    private String summaryContent;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 失败原因
     */
    private String failureReason;

    public static CompressionResult notCompacted(List<Message> messages, long tokenCount) {
        CompressionResult result = new CompressionResult();
        result.setMessages(messages);
        result.setPreCompressionTokens(tokenCount);
        result.setPostCompressionTokens(tokenCount);
        result.setFreedTokens(0);
        result.setCompacted(false);
        result.setSuccess(true);
        return result;
    }

    public static CompressionResult success(List<Message> messages, long preTokens, long postTokens,
                                            Message summaryMessage, String summaryContent) {
        CompressionResult result = new CompressionResult();
        result.setMessages(messages);
        result.setPreCompressionTokens(preTokens);
        result.setPostCompressionTokens(postTokens);
        result.setFreedTokens(preTokens - postTokens);
        result.setCompacted(true);
        result.setSummaryMessage(summaryMessage);
        result.setSummaryContent(summaryContent);
        result.setSuccess(true);
        return result;
    }

    public static CompressionResult failure(String reason) {
        CompressionResult result = new CompressionResult();
        result.setSuccess(false);
        result.setFailureReason(reason);
        return result;
    }
}
