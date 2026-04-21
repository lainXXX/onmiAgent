package top.javarem.omni.advisor.hook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook 注册与组合中心
 *
 * <p>负责收集和管理所有 Hook，按顺序执行链式调用。
 */
@Slf4j
@Component
public class HookRegistry {

    private final List<PreToolUseHook> preHooks = new ArrayList<>();
    private final List<PostToolUseHook> postHooks = new ArrayList<>();
    private final List<CompressionHook> compressionHooks = new ArrayList<>();
    private final List<StopHook> stopHooks = new ArrayList<>();
    private final List<SessionLifecycleHook> sessionHooks = new ArrayList<>();

    public HookRegistry(
            List<PreToolUseHook> preHooks,
            List<PostToolUseHook> postHooks,
            List<CompressionHook> compressionHooks,
            List<StopHook> stopHooks,
            List<SessionLifecycleHook> sessionHooks) {

        if (preHooks != null) this.preHooks.addAll(preHooks);
        if (postHooks != null) this.postHooks.addAll(postHooks);
        if (compressionHooks != null) this.compressionHooks.addAll(compressionHooks);
        if (stopHooks != null) this.stopHooks.addAll(stopHooks);
        if (sessionHooks != null) this.sessionHooks.addAll(sessionHooks);

        log.info("HookRegistry 初始化完成:");
        log.info("  PreToolUseHooks: {}", this.preHooks.size());
        log.info("  PostToolUseHooks: {}", this.postHooks.size());
        log.info("  CompressionHooks: {}", this.compressionHooks.size());
        log.info("  StopHooks: {}", this.stopHooks.size());
        log.info("  SessionLifecycleHooks: {}", this.sessionHooks.size());
    }

    // ==================== Session Lifecycle ====================

    /**
     * 会话开始
     */
    public void onSessionStart(ChatClientRequest request) {
        for (SessionLifecycleHook hook : sessionHooks) {
            try {
                hook.onSessionStart(request);
            } catch (Exception e) {
                log.error("SessionLifecycleHook.onSessionStart 执行异常: {}", hook.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * 会话结束
     */
    public void onSessionEnd(ChatClientResponse response) {
        for (SessionLifecycleHook hook : sessionHooks) {
            try {
                hook.onSessionEnd(response);
            } catch (Exception e) {
                log.error("SessionLifecycleHook.onSessionEnd 执行异常: {}", hook.getClass().getSimpleName(), e);
            }
        }
    }

    // ==================== PreToolUse Chain ====================

    /**
     * 执行 PreToolUse Hook 链
     *
     * @return null 表示被某个 Hook 取消
     */
    public ChatClientRequest doPreToolUse(ChatClientRequest request) {
        for (PreToolUseHook hook : preHooks) {
            try {
                ChatClientRequest result = hook.before(request);
                if (result == null) {
                    log.info("PreToolUseHook {} 取消了请求", hook.getClass().getSimpleName());
                    return null;
                }
                request = result;
            } catch (Exception e) {
                log.error("PreToolUseHook 执行异常: {}", hook.getClass().getSimpleName(), e);
            }
        }
        return request;
    }

    // ==================== PostToolUse Chain ====================

    /**
     * 执行 PostToolUse Hook 链
     */
    public ChatClientResponse doPostToolUse(ChatClientResponse response) {
        for (PostToolUseHook hook : postHooks) {
            try {
                response = hook.after(response);
            } catch (Exception e) {
                log.error("PostToolUseHook 执行异常: {}", hook.getClass().getSimpleName(), e);
            }
        }
        return response;
    }

    // ==================== Compression ====================

    /**
     * 执行压缩 Hook
     *
     * <p>按顺序执行所有 CompressionHook，
     * 第一个返回非空结果的生效。
     */
    public List<Message> doCompression(ChatClientRequest request,
                                       ChatClientResponse response,
                                       ToolExecutionResult result) {
        for (CompressionHook hook : compressionHooks) {
            try {
                List<Message> messages = hook.execute(request, response, result);
                if (messages != null && !messages.isEmpty()) {
                    log.debug("CompressionHook {} 执行，返回 {} 条消息",
                            hook.getClass().getSimpleName(), messages.size());
                    return messages;
                }
            } catch (Exception e) {
                log.error("CompressionHook 执行异常: {}", hook.getClass().getSimpleName(), e);
            }
        }
        // 没有 CompressionHook 返回空列表
        return List.of();
    }

    // ==================== StopHook ====================

    /**
     * 执行 StopHook
     */
    public void doStopHook(ChatClientResponse response) {
        for (StopHook hook : stopHooks) {
            try {
                hook.afterLoop(response);
            } catch (Exception e) {
                log.error("StopHook 执行异常: {}", hook.getClass().getSimpleName(), e);
            }
        }
    }
}
