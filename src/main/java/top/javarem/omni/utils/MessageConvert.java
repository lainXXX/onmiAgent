package top.javarem.omni.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Author: rem
 * @Date: 2026/03/29/16:03
 * @Description: 消息格式转换工具
 */
public class MessageConvert {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String convertMessage(Message message) {
        if (message == null) return "";
        try {
            // 1. 处理助手消息 (关键逻辑)
            if (message instanceof AssistantMessage a) {
                if (a.getToolCalls() != null && !a.getToolCalls().isEmpty()) {
                    // 使用 LinkedHashMap 保证 JSON 键值对顺序 (think 在前，tool_calls 在后)
                    Map<String, Object> combined = new LinkedHashMap<>();
                    combined.put("thinking", a.getText());
                    combined.put("tool_calls", a.getToolCalls());

                    return objectMapper.writeValueAsString(combined);
                }
                return a.getText() != null ? a.getText() : "";
            }

            // 2. 处理工具返回结果
            if (message instanceof ToolResponseMessage t) {
                return t.getResponses().toString();
            }

            // 3. 处理 User / System 消息
            return message.getText() != null ? message.getText() : "";

        } catch (JsonProcessingException e) {
            // 序列化失败时，降级返回原始文本
            return message.getText() != null ? message.getText() : "";
        }
    }
}