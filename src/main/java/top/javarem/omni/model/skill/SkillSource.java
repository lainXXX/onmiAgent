package top.javarem.omni.model.skill;

/**
 * Skill 来源枚举
 * 按优先级排序（数字越小优先级越高）
 */
public enum SkillSource {
    /**
     * 内置技能 - 从 classpath 加载
     * 路径: classpath:/skills/{skillName}/SKILL.md
     */
    BUNDLED("bundled", 0, "classpath:/skills/"),

    /**
     * 托管技能 - 用户级别共享技能
     * 路径: {userHome}/.omni/.claude/skills/{skillName}/SKILL.md
     */
    MANAGED("managed", 1, ".claude/skills"),

    /**
         * 用户技能 - 用户个人技能
     * 路径: {userHome}/.omni/skills/{skillName}/SKILL.md
     */
    USER("user", 2, "skills"),

    /**
     * 项目技能 - 跟随项目的技能
     * 路径: {projectRoot}/.claude/skills/{skillName}/SKILL.md
     */
    PROJECT("project", 3, ".claude/skills");

    private final String name;
    private final int priority;
    private final String pathPrefix;  // 路径前缀（BUNDLED 用 classpath，其他用相对路径）

    SkillSource(String name, int priority, String pathPrefix) {
        this.name = name;
        this.priority = priority;
        this.pathPrefix = pathPrefix;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * 获取路径前缀
     * BUNDLED 返回 classpath:/skills/
     * 其他返回相对路径（如 skills、.claude/skills）
     */
    public String getPathPrefix() {
        return pathPrefix;
    }

    /**
     * 判断是否为内置来源（BUNDLED）
     */
    public boolean isBundled() {
        return this == BUNDLED;
    }

    /**
     * 获取单个文件的相对路径（不含 root 前缀）
     */
    public String getRelativeFilePath(String skillName) {
        return pathPrefix + "/" + skillName + "/SKILL.md";
    }

    /**
     * 判断是否为需要 userHome 的来源
     */
    public boolean needsUserHome() {
        return this == MANAGED || this == USER;
    }

    /**
     * 判断是否为项目级别的来源
     */
    public boolean isProjectLevel() {
        return this == PROJECT;
    }
}
