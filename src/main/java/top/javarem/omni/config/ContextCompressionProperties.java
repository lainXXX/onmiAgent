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

    /**
     * 是否启用上下文压缩
     */
    private boolean enabled;

    /**
     * 上下文窗口大小
     */
    private Integer contextWindow;

    /**
     * 压缩阈值
     */
    private BigDecimal threshold;

    /**
     * 保留最近的会话数
     */
    private Integer keepRecent;

    /**
     * 保留最早的会话数
     */
    private Integer keepEarliest;

}
