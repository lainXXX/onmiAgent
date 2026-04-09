package top.javarem.omni.model.skill;

import java.util.List;

/**
 * 权限决策结果
 */
public record PermissionDecision(
    Behavior behavior,
    String message,
    String reason,
    List<Suggestion> suggestions
) {
    public enum Behavior {
        ALLOW, DENY, ASK
    }

    public record Suggestion(
        String action,
        String target,
        String pattern,
        String permission
    ) {}

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(Behavior.ALLOW, null, reason, null);
    }

    public static PermissionDecision deny(String message, String reason) {
        return new PermissionDecision(Behavior.DENY, message, reason, null);
    }

    public static PermissionDecision ask(String message, String reason, List<Suggestion> suggestions) {
        return new PermissionDecision(Behavior.ASK, message, reason, suggestions);
    }
}
