package top.javarem.omni.model.skill;

/**
 * Skill 来源枚举
 * 按优先级排序（数字越小优先级越高）
 */
public enum SkillSource {
    BUNDLED("bundled", 0),   // classpath:/skills/
    MANAGED("managed", 1),   // ~/.claude/.claude/skills
    USER("user", 2),         // ~/.claude/skills
    PROJECT("project", 3);   // .claude/skills

    private final String name;
    private final int priority;

    SkillSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }
}
