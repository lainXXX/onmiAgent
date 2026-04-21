package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.config.RetryProperties;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

@Slf4j
@Component
public class RetryAdvisor implements BaseAdvisor {

    private final RetryProperties properties;

    public RetryAdvisor(RetryProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 800;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    // ==================== 非流式重试 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!properties.isEnabled()) {
            return chain.nextCall(request);
        }

        int attempt = 0;
        Throwable lastException = null;

        while (attempt < properties.getMaxAttempts()) {
            attempt++;
            try {
                return chain.nextCall(request);
            } catch (Throwable e) {
                lastException = e;
                if (!isRetryable(e)) {
                    log.error("[RetryAdvisor] 非流式请求遇到不可重试异常，直接抛出: {}", e.getMessage());
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }
                if (attempt >= properties.getMaxAttempts()) {
                    log.error("[RetryAdvisor] 非流式请求重试 {} 次耗尽，最终失败: {}", attempt, e.getMessage());
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }
                long delay = calculateDelay(attempt);
                log.warn("[RetryAdvisor] 非流式请求第 {} 次尝试失败，{}ms 后重试...", attempt, delay);
                sleep(delay);
            }
        }
        throw lastException != null
                ? (lastException instanceof RuntimeException ? (RuntimeException) lastException : new RuntimeException(lastException))
                : new RuntimeException("重试耗尽");
    }

    // ==================== 流式重试 ====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!properties.isEnabled()) {
            return chain.nextStream(request);
        }

        return Flux.defer(() -> chain.nextStream(request))
                .retryWhen(reactor.util.retry.Retry
                        .backoff(properties.getMaxAttempts(), Duration.ofMillis(properties.getInitialDelayMs()))
                        .maxBackoff(Duration.ofMillis(properties.getMaxDelayMs()))
                        .filter((Predicate<Throwable>) this::isRetryable)
                        .doBeforeRetry(signal -> log.warn("[RetryAdvisor] 流式第 {} 次尝试失败: {}，{}ms 后重试...",
                                signal.totalRetries() + 1,
                                signal.failure().getMessage(),
                                calculateDelay((int) signal.totalRetries() + 1)))
                        .onRetryExhaustedThrow((backoffSpec, retrySignal) -> {
                            log.error("[RetryAdvisor] 流式重试耗尽，最终失败: {}", retrySignal.failure().getMessage());
                            return retrySignal.failure();
                        }));
    }

    // ==================== 工具方法 ====================

    private boolean isRetryable(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        for (String pattern : properties.getRetryableExceptions()) {
            if (msg.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private long calculateDelay(int attempt) {
        long delay = (long) (properties.getInitialDelayMs() * Math.pow(properties.getMultiplier(), attempt - 1));
        delay += ThreadLocalRandom.current().nextLong(0, 500);
        return Math.min(delay, properties.getMaxDelayMs());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试等待被中断", e);
        }
    }
}
