package top.javarem.omni.utils;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 请求上下文持有器
 *
 * <p>用于在 SSE streaming 期间传递上下文信息：
 * <ul>
 *   <li>SseEmitter：SSE 发射器，供 ChatController 发送 WAITING 事件</li>
 *   <li>conversationId：会话 ID，供 AskUserQuestionTool 获取当前会话</li>
 *   <li>dangerousCommandPending：危险命令待审批信息（ticketId + command）</li>
 * </ul>
 */
public class RequestContextHolder {

    /**
     * 使用 InheritableThreadLocal 而非 ThreadLocal，
     * 因为 Spring AI 的流式响应可能在不同的线程池线程中执行（如 boundedElastic）。
     * InheritableThreadLocal 允许子线程继承父线程的值。
     */
    private static final InheritableThreadLocal<SseEmitter> emitterHolder = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<String> conversationIdHolder = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<Map<String, String>> dangerousCommandPendingHolder = new InheritableThreadLocal<>();
    private static final ThreadLocal<String> workspaceHolder = new ThreadLocal<>();

    public static void setEmitter(SseEmitter emitter) {
        emitterHolder.set(emitter);
    }

    public static SseEmitter getEmitter() {
        return emitterHolder.get();
    }

    public static void setConversationId(String conversationId) {
        conversationIdHolder.set(conversationId);
    }

    public static String getConversationId() {
        return conversationIdHolder.get();
    }

    public static void setDangerousCommandPending(String ticketId, String command) {
        dangerousCommandPendingHolder.set(Map.of("ticketId", ticketId, "command", command));
    }

    public static Map<String, String> getDangerousCommandPending() {
        return dangerousCommandPendingHolder.get();
    }

    public static void setWorkspace(String workspace) {
        workspaceHolder.set(workspace);
    }

    public static String getWorkspace() {
        return workspaceHolder.get();
    }

    public static void clear() {
        emitterHolder.remove();
        conversationIdHolder.remove();
        dangerousCommandPendingHolder.remove();
        workspaceHolder.remove();
    }
}
