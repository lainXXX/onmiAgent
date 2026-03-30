package top.javarem.omni.handler;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.AgentFinishStatus;
import top.javarem.omni.utils.ThreadLocalUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component // 交给 Spring 容器管理
public class LlmCallMonitorObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        // 告诉 Spring，我只拦截 ChatModel 的调用上下文
        return context instanceof ChatModelObservationContext;
    }



    @Override
    public void onStop(ChatModelObservationContext context) {

//        // onStop 方法触发的时机，正是大模型 HTTP 请求结束，
//        // 也就是你源码中 return chatResponse; 刚刚执行完的那一刻！
//        ChatResponse chatResponse = context.getResponse();
//        // 获取大模型的结束原因
//        String finishReason = chatResponse.getResults().get(0).getMetadata().getFinishReason();
//        // 如果大模型没有结束，则返回
//        if (!AgentFinishStatus.STOP.equals(AgentFinishStatus.from(finishReason))) return;
//
//        List<Message> messages = context.getRequest().getInstructions();
//        // 获取大模型和工具调用的对话
//        List<Message> toolInteractionMessages = messages.stream()
//                .dropWhile(msg -> !(msg instanceof AssistantMessage am && !am.getToolCalls().isEmpty()))
//                // 明确指定返回 ArrayList，它是可变的
//                .collect(Collectors.toCollection(ArrayList::new));
//        log.info("新增工具调用对话");
//        ThreadLocalUtil.set("TOOL_INTERACTION_MESSAGES", toolInteractionMessages);
    }
}