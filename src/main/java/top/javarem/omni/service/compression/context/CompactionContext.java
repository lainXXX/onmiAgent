package top.javarem.omni.service.compression.context;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.client.ChatClientRequest;
import top.javarem.omni.config.ContextCompressionProperties;
import top.javarem.omni.model.compression.CollapseState;
import top.javarem.omni.model.compression.LayerResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩执行上下文
 *
 * <p>贯穿整个压缩流水线的上下文对象，
 * 存储执行状态和中间结果。
 */
@Data
@Builder
public class CompactionContext {

    /**
     * ChatClient 请求
     */
    private ChatClientRequest request;

    /**
     * 压缩配置
     */
    private ContextCompressionProperties properties;

    // ==================== Token 统计 ====================

    /**
     * 压缩前 token 数
     */
    private long preTokens;

    /**
     * 压缩后 token 数
     */
    private long postTokens;

    // ==================== 层结果 ====================

    /**
     * 各层执行结果
     */
    @Builder.Default
    private List<LayerResult> layerResults = new ArrayList<>();

    // ==================== Context Collapse 状态 ====================

    /**
     * 当前折叠状态
     */
    @Builder.Default
    private CollapseState collapseState = CollapseState.NORMAL;

    /**
     * Token 使用率 (usedTokens / contextWindow)
     */
    private double tokenUsage;

    // ==================== 电路断路器 ====================

    /**
     * 连续失败次数
     */
    @Builder.Default
    private int consecutiveFailures = 0;

    // ==================== Hooks 上下文 ====================

    /**
     * 压缩前的消息（用于 PreCompactHook）
     */
    private List<?> preCompactMessages;

    /**
     * 压缩后的消息（用于 PostCompactHook）
     */
    private List<?> postCompactMessages;

    // ==================== 便捷方法 ====================

    /**
     * 添加层结果
     */
    public void addLayerResult(LayerResult result) {
        this.layerResults.add(result);
    }

    /**
     * 获取上下文窗口大小
     */
    public long getContextWindow() {
        return properties != null ? properties.getContextWindow() : 200_000;
    }

    /**
     * 获取 AutoCompact 阈值
     */
    public long getAutoCompactThreshold() {
        int buffer = (properties != null && properties.getBufferTokens() != null)
                ? properties.getBufferTokens()
                : 13_000;
        return getContextWindow() - buffer;
    }

    /**
     * 是否达到 AutoCompact 阈值
     */
    public boolean isAutoCompactThresholdExceeded() {
        return postTokens > getAutoCompactThreshold();
    }

    /**
     * 是否达到 Context Collapse Commit 阈值
     */
    public boolean isCommitThresholdExceeded() {
        double threshold = (properties != null && properties.getCollapseCommitThreshold() != null)
                ? properties.getCollapseCommitThreshold()
                : 0.90;
        return tokenUsage >= threshold;
    }

    /**
     * 是否达到 Context Collapse Block 阈值
     */
    public boolean isBlockThresholdExceeded() {
        double threshold = (properties != null && properties.getCollapseBlockThreshold() != null)
                ? properties.getCollapseBlockThreshold()
                : 0.95;
        return tokenUsage >= threshold;
    }

    /**
     * 电路断路器成功
     */
    public void onCircuitBreakerSuccess() {
        this.consecutiveFailures = 0;
    }

    /**
     * 电路断路器失败
     */
    public void onCircuitBreakerFailure() {
        this.consecutiveFailures++;
    }

    /**
     * 电路断路器是否打开
     */
    public boolean isCircuitBreakerOpen() {
        if (properties == null) {
            return false;
        }
        if (!properties.isCircuitBreakerEnabled()) {
            return false;
        }
        int maxFailures = properties.getMaxConsecutiveFailures() != null
                ? properties.getMaxConsecutiveFailures()
                : 3;
        return consecutiveFailures >= maxFailures;
    }
}
