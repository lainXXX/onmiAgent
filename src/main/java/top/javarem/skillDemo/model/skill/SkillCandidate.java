package top.javarem.skillDemo.model.skill;

/**
     * 技能候选
     * @param skillName 技能名称
     * @param description 技能描述
     * @param filePath 技能文件路径
     * @param score 向量相似度分数
     */
public record SkillCandidate(
            String skillName,
            String description,
            String filePath,
            double score
    ) {}