package top.javarem.omni.advisor.hook.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.stereotype.Component;
import top.javarem.omni.advisor.hook.PostToolUseHook;
import top.javarem.omni.advisor.hook.PreToolUseHook;
import top.javarem.omni.advisor.hook.SessionLifecycleHook;
import top.javarem.omni.advisor.hook.StopHook;
/**
 * 日志记录 Hook 实现
 *
 * <p>用于记录调用日志，方便调试。
 */
@Slf4j
@Component
public class LoggingHooks implements PreToolUseHook, PostToolUseHook, StopHook, SessionLifecycleHook {

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        log.debug("[PreToolUse] 请求处理中...");
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response) {
        log.debug("[PostToolUse] 响应处理完成");
        return response;
    }

    @Override
    public void afterLoop(ChatClientResponse response) {
        log.info("[StopHook] 工具调用循环结束");
    }

    @Override
    public void onSessionStart(ChatClientRequest request) {
        log.info("[SessionLifecycle] 会话开始");
    }

    @Override
    public void onSessionEnd(ChatClientResponse response) {
        log.info("[SessionLifecycle] 会话结束");
    }
}
