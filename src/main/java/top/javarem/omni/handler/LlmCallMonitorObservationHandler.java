package top.javarem.omni.handler;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmCallMonitorObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        // Handler is disabled - tool interaction messages are now handled via Spring AI context
    }
}