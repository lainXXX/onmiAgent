package top.javarem.omni.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * @Author: rem
 * @Date: 2026/03/17/13:40
 * @Description: 上下文压缩属性配置
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
     * 压缩阈值 (0.8 = 80%)
     */
    private BigDecimal threshold = new BigDecimal("0.8");

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

    // ==================== 电路断路器配置 ====================

    /**
     * 启用电路断路器
     */
    private boolean circuitBreakerEnabled = true;

    /**
     * 断路器触发阈值（连续失败次数）
     */
    private Integer maxConsecutiveFailures = 3;

}
