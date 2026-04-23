package top.javarem.omni.advisor.hook;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * 会话生命周期 Hook
 *
 * <p>用于监听会话的开始和结束。
 */
public interface SessionLifecycleHook {

    /**
     * 会话开始
     *
     * <p>在 {@link org.springframework.ai.chat.client.advisor.ToolCallAdvisor#doInitializeLoop}
     * 中执行，工具调用循环开始前调用。
     *
     * @param request 初始请求
     */
    void onSessionStart(ChatClientRequest request);

    /**
     * 会话结束
     *
     * <p>在 {@link org.springframework.ai.chat.client.advisor.ToolCallAdvisor#doFinalizeLoop}
     * 中执行，所有处理完成后调用。
     *
     * @param response 最终响应
     */
    void onSessionEnd(ChatClientResponse response);
}
