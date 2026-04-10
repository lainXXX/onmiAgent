package top.javarem.omni.model.skill;

/**
 * Skill 来源枚举
 * 按优先级排序（数字越小优先级越高）
 */
public enum SkillSource {
    BUNDLED("bundled", 0, "classpath:/skills/"),
    MANAGED("managed", 1, null),
    USER("user", 2, null),
    PROJECT("project", 3, null);

    private final String name;
    private final int priority;
    private final String bundledBase;  // BUNDLED 的 classpath 基础路径

    // 各自的相对路径模板（相对于 skillRootPath 或项目根目录）
    private static final String MANAGED_RELATIVE = ".claude/skills";
    private static final String USER_RELATIVE = "skills";
    private static final String PROJECT_RELATIVE = ".claude/skills";

    SkillSource(String name, int priority, String bundledBase) {
        this.name = name;
        this.priority = priority;
        this.bundledBase = bundledBase;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * 获取 BUNDLED 的 classpath 基础路径
     */
    public String getBundledBase() {
        return bundledBase;
    }

    /**
     * 获取相对路径（用于拼接 rootPath）
     */
    public String getRelativePath() {
        return switch (this) {
            case BUNDLED -> bundledBase;  // 不使用
            case MANAGED -> MANAGED_RELATIVE;
            case USER -> USER_RELATIVE;
            case PROJECT -> PROJECT_RELATIVE;
        };
    }

    /**
     * 获取单个文件的相对路径
     */
    public String getRelativeFilePath(String skillName) {
        return getRelativePath() + "/" + skillName + "/SKILL.md";
    }

    /**
     * 判断是否为内置来源
     */
    public boolean isBundled() {
        return this == BUNDLED;
    }
}
