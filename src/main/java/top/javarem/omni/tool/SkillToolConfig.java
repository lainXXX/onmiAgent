package top.javarem.onmi.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Skill 技能文件读取工具
 * 根据技能名称读取 ~/.claude/skills/{skillName}/SKILL.md 文件内容
 */
@Component
@Slf4j
public class SkillToolConfig implements AgentTool {

    /**
     * 技能根目录
     */
    @Value("${skill.discovery.root:classpath:/}")
    private String skillRootPath;

    /**
     * 技能文件相对路径模板
     */
    @Value("${skill.discovery.path:skills/**/SKILL.md}")
    private String skillPathTemplate;

    /**
     * 读取指定技能文件的完整内容
     *
     * @param skillName 技能名称
     * @param args      技能参数（可选）
     * @return 技能文件内容
     */
    @Tool(name = "Skill", description = """
            Execute a skill within the main conversation

            When users ask you to perform tasks, check if any of the available skills match. Skills provide specialized capabilities and domain knowledge.

            When users reference a "slash command" or "/<something>" (e.g., "/commit", "/review-pr"), they are referring to a skill. Use this tool to invoke it.

            How to invoke:
            - Use this tool with the skill name and optional arguments
            - Examples:
            - skill: "pdf" - invoke the pdf skill
            - skill: "commit", args: "-m 'Fix bug'" - invoke with arguments
            - skill: "review-pr", args: "123" - invoke with arguments
            - skill: "ms-office-suite:pdf" - invoke using fully qualified name

            Important:
            - Available skills are listed in system-reminder messages in the conversation
            - When a skill matches the user's request, this is a BLOCKING REQUIREMENT: invoke the relevant Skill tool BEFORE generating any other response about the task
            - NEVER mention a skill without actually calling this tool
            - Do not invoke a skill that is already running
            - Do not use this tool for built-in CLI commands (like /help, /clear, etc.)
            - If you see a <command-name> tag in the current conversation turn, the skill has ALREADY been loaded - follow the instructions directly instead of calling this tool again
            """)
    public String getSkillContent(
            @ToolParam(description = "技能名称") String skillName,
            @ToolParam(description = "技能参数（可选）", required = false) String args) {
        log.info("[skill] 开始执行: skillName={}", skillName);

        // 1. 参数校验
        if (skillName == null || skillName.trim().isEmpty()) {
            log.error("[skill] 失败: 技能名称为空");
            return buildErrorResponse("技能名称不能为空", "请提供要读取的技能名称");
        }

        String normalizedSkillName = skillName.trim();

        // 2. 构建技能文件路径
        String skillFilePath = buildSkillFilePath(normalizedSkillName);
        log.debug("技能文件路径: {}", skillFilePath);

        // 3. 解析路径并检查文件是否存在
        Path targetPath;
        try {
            targetPath = Paths.get(skillFilePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return buildErrorResponse("路径解析失败: " + e.getMessage(),
                    "技能名称格式可能有误");
        }

        if (!Files.exists(targetPath)) {
            log.error("[skill] 失败: 技能不存在 skillName={}", normalizedSkillName);
            return buildNotFoundResponse(normalizedSkillName);
        }

        // 4. 读取文件内容
        try {
            String result = executeSkill(targetPath, normalizedSkillName, args);
            log.info("[skill] 完成: skillName={}, contentLength={}", normalizedSkillName, result.length());
            return result;
        } catch (Exception e) {
            log.error("[skill] 失败: skillName={}, error={}", normalizedSkillName, e.getMessage(), e);
            return buildErrorResponse("技能文件读取失败: " + e.getMessage(),
                    "请检查文件是否存在以及是否有读取权限");
        }
    }

    /**
     * 执行技能：读取文件内容并替换 ARGUMENTS 占位符
     */
    private String executeSkill(Path targetPath, String skillName, String args) throws IOException {
        String content = Files.readString(targetPath, StandardCharsets.UTF_8);

        // 剔除 YAML front matter
        content = extractYamlFrontMatter(content);

        // 替换 ARGUMENTS 占位符
        String processedContent = replaceArguments(content, args);

        // 构建返回结果
        StringBuilder sb = new StringBuilder();
        sb.append("Base directory for this skill: ").append(targetPath).append("\n\n").append(skillName).append("\n\n");
        sb.append(processedContent);

        return sb.toString();
    }

    /**
     * 剔除 YAML front matter，保留正文
     * 格式: --- 内容 ---
     */
    private String extractYamlFrontMatter(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return content;
        }

        int endIndex = trimmed.indexOf("---", 3);
        if (endIndex == -1) {
            return content;
        }

        // 返回 --- 之后的内容
        return trimmed.substring(endIndex + 3).trim();
    }

    /**
     * 替换 ARGUMENTS 占位符
     * 支持格式：
     * - ARGUMENTS: {arg} -> ARGUMENTS: 用户实际参数
     * - {args} -> 用户实际参数
     */
    private String replaceArguments(String content, String args) {
        if (args == null || args.isBlank()) {
            return content;
        }

        // 替换 ARGUMENTS: {arg} 或 ARGUMENTS: xxx
        content = content.replace("ARGUMENTS: {arg}", "ARGUMENTS: " + args);
        content = content.replace("ARGUMENTS: {args}", "ARGUMENTS: " + args);
        content = content.replace("ARGUMENTS:", "ARGUMENTS: " + args);

        // 替换 {args} 占位符
        content = content.replace("{args}", args);
        content = content.replace("{arg}", args);

        return content;
    }

    /**
     * 构建技能文件路径
     * 路径格式: {root}skills/{skillName}/SKILL.md
     */
    private String buildSkillFilePath(String skillName) {
        // 移除 root 中的 file: 前缀（如果有）
        String root = skillRootPath;
        if (root.startsWith("file:")) {
            root = root.substring(5);
        }

        // 替换 user.home
        root = root.replace("${user.home}", System.getProperty("user.home"));

        // 标准化路径分隔符
        root = root.replace("\\", "/");
        if (!root.endsWith("/")) {
            root += "/";
        }

        // 构建最终路径: {root}skills/{skillName}/SKILL.md
        return root + "skills/" + skillName + "/SKILL.md";
    }

    /**
     * 构建文件不存在响应
     */
    private String buildNotFoundResponse(String skillName) {
        StringBuilder sb = new StringBuilder();
        sb.append("❌ 技能不存在: ").append(skillName).append("\n\n");
        sb.append("💡 可能的原因:\n");
        sb.append("  - 技能名称拼写有误\n");
        sb.append("  - 该技能尚未创建\n\n");
        sb.append("💡 提示: 技能文件应放置在 ~/.claude/skills/{skillName}/SKILL.md");

        return sb.toString();
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }
}
