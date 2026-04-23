package top.javarem.omni.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.advisor.retry")
public class RetryProperties {

    private boolean enabled = true;

    private int maxAttempts = 3;

    private long initialDelayMs = 1000;

    private double multiplier = 2.0;

    private long maxDelayMs = 10000;

    private List<String> retryableExceptions = List.of(
            "529",
            "429",
            "timeout",
            "Timeout",
            "connection",
            "Connection refused"
    );
}
