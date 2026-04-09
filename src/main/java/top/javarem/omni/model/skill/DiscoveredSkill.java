package top.javarem.omni.model.skill;

import java.nio.file.Path;

/**
 * 已发现的 Skill 封装
 */
public record DiscoveredSkill(
    SkillSource source,
    Path filePath,
    SkillFrontmatter frontmatter,
    String rawContent,
    String bodyContent
) {
    /**
     * 获取 skill 名称
     */
    public String getName() {
        return frontmatter != null ? frontmatter.name() : null;
    }
}
