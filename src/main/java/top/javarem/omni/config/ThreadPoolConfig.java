package top.javarem.omni.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.*;

@Slf4j
@EnableAsync
@Configuration
@EnableConfigurationProperties(ThreadPoolConfigProperties.class)
public class ThreadPoolConfig {

    /**
     * 子 Agent 专用线程池
     */
    @Bean("agentExecutor")
    public ExecutorService agentExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(
                4,                                      // 核心线程数
                8,                                      // 最大线程数
                60L, TimeUnit.SECONDS,                 // 空闲回收时间
                new java.util.concurrent.LinkedBlockingQueue<>(50),  // 队列大小
                new java.util.concurrent.ThreadFactory() {
                    private int count = 0;
                    @Override
                    public java.lang.Thread newThread(Runnable r) {
                        return new java.lang.Thread(r, "agent-" + (++count));
                    }
                },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    @ConditionalOnMissingBean(ThreadPoolExecutor.class)
    public ThreadPoolExecutor threadPoolExecutor(ThreadPoolConfigProperties properties) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        // 实例化策略
        RejectedExecutionHandler handler;
        switch (properties.getPolicy()){
            case "AbortPolicy":
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
            case "DiscardPolicy":
                handler = new ThreadPoolExecutor.DiscardPolicy();
                break;
            case "DiscardOldestPolicy":
                handler = new ThreadPoolExecutor.DiscardOldestPolicy();
                break;
            case "CallerRunsPolicy":
                handler = new ThreadPoolExecutor.CallerRunsPolicy();
                break;
            default:
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
        }
        // 创建线程池
        return new ThreadPoolExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getBlockQueueSize()),
                Executors.defaultThreadFactory(),
                handler);
    }

}
