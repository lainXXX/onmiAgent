package top.javarem.skillDemo.model.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 元数据模型
 * 用于存储技能的名称、描述和文件路径信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMetadata {

    /**
     * 技能名称
     */
    private String name;

    /**
     * 技能描述（用于向量化检索）
     * 包含功能描述和触发词
     */
    private String description;

    /**
     * 技能文件路径
     */
    private String path;
}
