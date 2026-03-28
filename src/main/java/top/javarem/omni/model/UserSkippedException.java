package top.javarem.omni.model;

/**
 * 用户跳过异常
 * 当用户在 AskUserQuestion 交互中点击"跳过"时抛出此异常
 */
public class UserSkippedException extends RuntimeException {

    private final String reason;

    public UserSkippedException(String reason) {
        super("User skipped the question: " + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
