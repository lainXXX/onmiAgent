package top.javarem.omni.advisor.hook;

import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * 工具链结束 Hook
 *
 * <p>在 {@link org.springframework.ai.chat.client.advisor.ToolCallAdvisor#doFinalizeLoop}
 * 中执行，此时所有工具调用循环已结束。
 */
public interface StopHook {

    /**
     * 在工具链结束后执行
     *
     * @param response 最终响应
     */
    void afterLoop(ChatClientResponse response);
}
