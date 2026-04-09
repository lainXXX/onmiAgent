package top.javarem.omni.model.skill;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill Frontmatter 元数据
 * 完整解析 SKILL.md 中的 YAML 配置
 */
public record SkillFrontmatter(
    // 基础字段
    String name,
    String description,

    // P0: 安全相关
    List<String> allowedTools,
    boolean disableModelInvocation,

    // P0: 执行控制
    String model,
    String effort,
    String context,

    // P2: 参数
    String argumentHint,
    List<String> argumentNames,

    // P3: 动态激活
    List<String> paths,
    List<String> conditions,

    // 元数据
    boolean userInvocable,
    boolean enabled,
    String version,
    Map<String, String> hooks
) {
    // 安全属性白名单
    private static final Set<String> SAFE_PROPERTIES = Set.of(
        "name", "description", "allowedTools", "model",
        "effort", "context", "paths", "userInvocable",
        "argumentHint", "argumentNames", "version", "enabled",
        "disableModelInvocation", "hooks", "conditions"
    );

    /**
     * 检查是否仅包含安全属性
     * 安全属性：指不涉及危险操作的只读属性
     * 当前实现：仅当 allowedTools 非空且 disableModelInvocation 为 true 时视为不安全
     */
    public boolean hasOnlySafeProperties() {
        // 有 allowedTools 但禁用了模型调用 = 不安全
        if (disableModelInvocation && allowedTools != null && !allowedTools.isEmpty()) {
            return false;
        }
        // 有自定义 hooks = 需要进一步检查
        if (hooks != null && !hooks.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * 创建默认实例
     */
    public static SkillFrontmatter defaultInstance(String name) {
        return new SkillFrontmatter(
            name, "", null, false,
            null, null, null,
            null, null,
            null, null,
            true, true, null, null
        );
    }
}
