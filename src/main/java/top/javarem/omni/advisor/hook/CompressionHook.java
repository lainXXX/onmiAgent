package top.javarem.omni.advisor.hook;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

/**
 * 压缩 Hook
 *
 * <p>在 {@link org.springframework.ai.chat.client.advisor.ToolCallAdvisor#doGetNextInstructionsForToolCall}
 * 中执行，此时工具已执行完毕，可以获取工具结果进行压缩。
 */
public interface CompressionHook {

    /**
     * 执行压缩
     *
     * <p>在工具执行后、下轮指令构建前执行。
     * 可以访问工具执行结果，进行上下文压缩。
     *
     * @param request 请求上下文
     * @param response LLM 响应
     * @param result 工具执行结果
     * @return 压缩后的消息列表
     */
    List<Message> execute(ChatClientRequest request,
                         ChatClientResponse response,
                         ToolExecutionResult result);
}
