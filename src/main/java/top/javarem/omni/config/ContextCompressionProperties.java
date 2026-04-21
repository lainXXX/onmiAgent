package top.javarem.omni.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文压缩属性配置
 *
 * <p>支持 YAML 配置：
 * <pre>
 * spring.ai.context.compression:
 *   enabled: true
 *   context-window: 200000
 *   keep-recent: 2
 *   keep-earliest: 1
 *   snip-enabled: true
 *   micro-compact-enabled: true
 *   gap-threshold-minutes: 60
 *   micro-compact-keep-recent: 5
 *   auto-compact-enabled: true
 *   max-summary-tokens: 2000
 *   circuit-breaker-enabled: true
 *   max-consecutive-failures: 3
 *   collapse-enabled: false
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "spring.ai.context.compression", ignoreInvalidFields = true)
@Data
public class ContextCompressionProperties {

    // ==================== 基础配置 ====================

    /**
     * 是否启用上下文压缩
     */
    private boolean enabled = true;

    /**
     * 上下文窗口大小
     */
    private Integer contextWindow = 200000;

    /**
     * 保留尾部消息轮数
     */
    private Integer keepRecent = 2;

    /**
     * 保留头部消息轮数
     */
    private Integer keepEarliest = 1;

    // ==================== SnipCompact 配置 ====================

    /**
     * 启用 SnipCompact
     */
    private boolean snipEnabled = true;

    // ==================== MicroCompact 配置 ====================

    /**
     * 启用 MicroCompact
     */
    private boolean microCompactEnabled = true;

    /**
     * 时间衰减阈值（分钟）
     */
    private Integer gapThresholdMinutes = 60;

    /**
     * MicroCompact 保留最近工具结果数
     */
    private Integer microCompactKeepRecent = 5;

    // ==================== AutoCompact 配置 ====================

    /**
     * 启用 AutoCompact（LLM 摘要）
     */
    private boolean autoCompactEnabled = true;

    /**
     * 摘要最大 token 数
     */
    private Integer maxSummaryTokens = 2000;

    /**
     * AutoCompact Buffer Token 数
     */
    private Integer bufferTokens = 13000;

    // ==================== Context Collapse 配置 ====================

    /**
     * 启用 Context Collapse
     */
    private boolean collapseEnabled = false;

    /**
     * Commit 阈值 (0.9 = 90%)
     */
    private Double collapseCommitThreshold = 0.90;

    /**
     * Block 阈值 (0.95 = 95%)
     */
    private Double collapseBlockThreshold = 0.95;

    // ==================== 电路断路器配置 ====================

    /**
     * 启用电路断路器
     */
    private boolean circuitBreakerEnabled = true;

    /**
     * 断路器触发阈值（连续失败次数）
     */
    private Integer maxConsecutiveFailures = 3;

    // ==================== 兼容旧代码 ====================

    /**
     * 压缩阈值系数 (contextWindow * threshold = 压缩触发token数)
     */
    private Double threshold = 0.8;
}
