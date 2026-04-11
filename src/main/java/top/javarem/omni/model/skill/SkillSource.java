package top.javarem.omni.model.skill;

/**
 * Skill 来源枚举
 * 按优先级排序（数字越小优先级越高）
 */
public enum SkillSource {
    // 内置技能 - 从 classpath 加载
    BUNDLED("bundled", "classpath:/skills/"),

    // 托管技能 - 用户级别共享技能 {userHome}/.omni/.claude/skills/
    MANAGED("managed", ".claude/skills"),

    // 用户技能 - 用户个人技能 {userHome}/.omni/skills/
    USER("user", "skills"),

    // 项目技能 - 跟随项目的技能 {projectRoot}/.claude/skills/
    PROJECT("project", ".claude/skills");

    private final String name;
    private final String relativePath;

    SkillSource(String name, String relativePath) {
        this.name = name;
        this.relativePath = relativePath;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return ordinal();
    }

    public String getRelativePath() {
        return relativePath;
    }

    public boolean isBundled() {
        return this == BUNDLED;
    }

    public boolean needsUserHome() {
        return this == MANAGED || this == USER;
    }

    public boolean isProjectLevel() {
        return this == PROJECT;
    }

    /**
     * 获取单个 Skill 文件的完整搜索路径
     * @param rootPath 用户根目录（已解析的绝对路径，如 C:/Users/xxx/.omni/）
     * @param projectRoot 项目根目录
     * @param skillName 技能名称
     * @return 完整路径
     */
    public String getFullSearchPath(String rootPath, String projectRoot, String skillName) {
        if (isBundled()) {
            return relativePath + "/" + skillName + "/SKILL.md";
        }
        if (isProjectLevel()) {
            return projectRoot + "/" + relativePath + "/" + skillName + "/SKILL.md";
        }
        return rootPath + relativePath + "/" + skillName + "/SKILL.md";
    }

    /**
     * 获取 glob 搜索模式（用于发现所有技能）
     * @param rootPath 用户根目录
     * @param projectRoot 项目根目录
     * @return glob 模式
     */
    public String getGlobPattern(String rootPath, String projectRoot) {
        if (isBundled()) {
            return relativePath + "/**/SKILL.md";
        }
        if (isProjectLevel()) {
            return projectRoot + "/" + relativePath + "/**/SKILL.md";
        }
        return rootPath + relativePath + "/**/SKILL.md";
    }
}
