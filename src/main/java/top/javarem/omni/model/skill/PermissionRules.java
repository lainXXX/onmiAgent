package top.javarem.omni.model.skill;

import java.util.List;

/**
 * 权限规则配置
 */
public record PermissionRules(
    List<String> deny,
    List<String> allow,
    DefaultBehavior defaultBehavior
) {
    public enum DefaultBehavior {
        ALLOW, DENY, ASK
    }

    public static PermissionRules defaultRules() {
        return new PermissionRules(
            List.of(),
            List.of(),
            DefaultBehavior.ASK
        );
    }
}
