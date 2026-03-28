package top.javarem.omni.config;

import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import top.javarem.omni.handler.LlmCallMonitorObservationHandler;

@Configuration
public class ObservationConfig {

    private final ObservationRegistry observationRegistry;
    private final LlmCallMonitorObservationHandler llmCallMonitorObservationHandler;

    public ObservationConfig(ObservationRegistry observationRegistry, 
                             LlmCallMonitorObservationHandler llmCallMonitorObservationHandler) {
        this.observationRegistry = observationRegistry;
        this.llmCallMonitorObservationHandler = llmCallMonitorObservationHandler;
    }

    @PostConstruct
    public void registerHandler() {
        // 这一步是关键！把你的 Handler 挂进全局注册表
        this.observationRegistry.observationConfig().observationHandler(llmCallMonitorObservationHandler);
    }
}