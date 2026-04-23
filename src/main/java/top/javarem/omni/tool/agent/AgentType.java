package top.javarem.omni.tool.agent;

import lombok.Getter;

import java.util.Set;

/**
 * 子 Agent 类型枚举
 */
@Getter
public enum AgentType {
    EXPLORE("explore", "深入探索代码库，分析结构和组件关系", Set.of("Read", "Glob", "Grep", "Bash"), false, 180000L),
    PLAN("plan", "制定详细的实施计划，分解任务步骤", Set.of("Read", "Glob", "Grep", "Write", "Edit", "Skill", "Bash"), false, 180000L),
    GENERAL("general", "通用问题处理，支持多种工具", Set.of("Read", "Write", "Edit", "Glob", "Grep", "Bash", "WebSearch", "WebFetch", "Skill"), false, 300000L),
    CODE_REVIEWER("code-reviewer", "代码审查，提供优化建议", Set.of("Read", "Glob", "Grep", "Skill", "Bash"), false, 300000L),
    VERIFICATION("verification", "验收测试 Agent，尝试破坏而非确认实现工作", Set.of("Read", "Glob", "Grep", "Bash"), true, 600000L);

    private final String value;
    private final String description;
    private final Set<String> allowedTools;
    private final boolean oneShot;
    private final long defaultTimeoutMs;

    AgentType(String value, String description, Set<String> allowedTools, boolean oneShot, long defaultTimeoutMs) {
        this.value = value;
        this.description = description;
        this.allowedTools = allowedTools;
        this.oneShot = oneShot;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public static AgentType fromValue(String value) {
        for (AgentType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return GENERAL;
    }

    public static Set<String> allowedValues() {
        return Set.of("explore", "plan", "general", "code-reviewer", "verification");
    }

    public boolean isToolAllowed(String toolName) {
        return allowedTools.contains(toolName);
    }
}
