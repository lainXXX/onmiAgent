package top.javarem.omni.model;

/**
 * AskUserQuestion 工具执行时抛出，中断 Flux，
 * 触发 ChatController 发送 ask-user-question 事件到 SSE。
 *
 * <p>与 DangerousCommandPendingException 不同：
 * 在抛出前会完成 pending Future（设置为 YIELD 状态），
 * 确保 pendingFutures 不会泄漏。</p>
 */
public class AskUserQuestionYieldException extends RuntimeException {

    private final String questionId;
    private final String conversationId;
    private final AskUserQuestionRequest request;

    public AskUserQuestionYieldException(String questionId, String conversationId, AskUserQuestionRequest request) {
        super("AskUserQuestion_YIELD: " + questionId);
        this.questionId = questionId;
        this.conversationId = conversationId;
        this.request = request;
    }

    public String questionId() {
        return questionId;
    }

    public String conversationId() {
        return conversationId;
    }

    public AskUserQuestionRequest request() {
        return request;
    }
}
