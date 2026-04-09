package top.javarem.omni.model.skill;

import java.util.Map;

/**
 * Skill 错误结构
 */
public record SkillError(
    int code,
    String message,
    String suggestion,
    String skillName,
    Map<String, Object> context
) {
    // 错误码常量
    public static final int INVALID_FORMAT = 1;
    public static final int NOT_FOUND = 2;
    public static final int DISABLED = 3;
    public static final int DISABLE_MODEL_INVOCATION = 4;
    public static final int NOT_PROMPT_TYPE = 5;
    public static final int REMOTE_NOT_DISCOVERED = 6;
    public static final int PERMISSION_DENIED = 7;
    public static final int INTERNAL_ERROR = -1;

    public static SkillError invalidFormat(String skillName) {
        return new SkillError(INVALID_FORMAT,
            "Skill 格式错误: " + skillName,
            "Skill 名称应为字母、数字和连字符的组合",
            skillName, null);
    }

    public static SkillError notFound(String skillName) {
        return new SkillError(NOT_FOUND,
            "Skill 不存在: " + skillName,
            "请检查 Skill 名称是否正确，或确认该 Skill 已创建",
            skillName, null);
    }

    public static SkillError disabled(String skillName) {
        return new SkillError(DISABLED,
            "Skill 已禁用: " + skillName,
            "该 Skill 当前已被禁用",
            skillName, null);
    }

    public static SkillError permissionDenied(String skillName) {
        return new SkillError(PERMISSION_DENIED,
            "无权执行 Skill: " + skillName,
            "请联系管理员授予执行权限",
            skillName, null);
    }

    public static SkillError internalError(String skillName, Exception e) {
        return new SkillError(INTERNAL_ERROR,
            "执行失败: " + e.getMessage(),
            "请稍后重试，或联系管理员",
            skillName, Map.of("errorType", e.getClass().getSimpleName()));
    }

    /**
     * 构建用户友好的错误提示
     */
    public String toUserMessage() {
        return "❌ " + message + (suggestion != null ? "\n\n💡 建议: " + suggestion : "");
    }

    /**
     * 构建日志字符串
     */
    public String toLogString() {
        return String.format("[SkillError] code=%d, skill=%s, message=%s",
            code, skillName, message);
    }
}
