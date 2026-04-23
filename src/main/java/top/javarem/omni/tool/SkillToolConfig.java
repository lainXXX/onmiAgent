package top.javarem.omni.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.skill.DiscoveredSkill;
import top.javarem.omni.model.skill.PermissionDecision;
import top.javarem.omni.model.skill.SkillError;
import top.javarem.omni.model.skill.SkillFrontmatter;
import top.javarem.omni.service.skill.PermissionService;
import top.javarem.omni.service.skill.SkillDiscovery;
import top.javarem.omni.service.skill.SkillExecutor;
import top.javarem.omni.service.skill.TelemetryService;

import java.nio.file.Path;

/**
 * Skill 技能执行工具
 */
@Component
@Slf4j
public class SkillToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "Skill";
    }

    @Autowired
    private SkillDiscovery skillDiscovery;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private SkillExecutor skillExecutor;

    @Value("${skill.discovery.root:classpath:/}")
    private String skillRootPath;

    @Tool(name = "Skill", description = """
            execute a skill within the main conversation

            When users ask you to perform tasks, check if any of the available skills match.
            Skills provide specialized capabilities and domain knowledge.

            Examples:
            - skill: "pdf", args: "document.pdf"
            - skill: "xlsx", args: "data.csv"
            """)
    public String getSkillContent(
            @ToolParam(description = "skill name") String skillName,
            @ToolParam(description = "optional arguments", required = false) String args) {

        long startTime = System.currentTimeMillis();
        log.info("[Skill] 开始执行: skillName={}", skillName);

        // 1. 验证输入
        if (skillName == null || skillName.trim().isEmpty()) {
            return buildUserError(SkillError.invalidFormat(skillName));
        }

        String normalizedSkillName = skillName.trim();

        // 2. 发现 Skill
        DiscoveredSkill discovered = skillDiscovery.discover(normalizedSkillName);
        if (discovered == null) {
            log.error("[Skill] 失败: Skill 不存在 skillName={}", normalizedSkillName);
            telemetryService.record(normalizedSkillName, startTime, false, SkillError.NOT_FOUND);
            return buildUserError(SkillError.notFound(normalizedSkillName));
        }

        // 3. 解析 Frontmatter
        SkillFrontmatter fm = discovered.frontmatter();
        if (fm == null || !fm.enabled()) {
            log.error("[Skill] 失败: Skill 已禁用 skillName={}", normalizedSkillName);
            telemetryService.record(normalizedSkillName, startTime, false, SkillError.DISABLED);
            return buildUserError(SkillError.disabled(normalizedSkillName));
        }

        // 4. 权限检查
        PermissionDecision decision = permissionService.check(normalizedSkillName, fm);
        if (decision.behavior() == PermissionDecision.Behavior.DENY) {
            log.warn("[Skill] 权限拒绝: skillName={}", normalizedSkillName);
            telemetryService.record(normalizedSkillName, startTime, false, SkillError.PERMISSION_DENIED);
            return buildUserError(SkillError.permissionDenied(normalizedSkillName));
        }

        if (decision.behavior() == PermissionDecision.Behavior.ASK) {
            return buildAskPermissionResponse(decision);
        }

        // 5. 执行
        try {
            String result = skillExecutor.execute(discovered, args);
            telemetryService.record(normalizedSkillName, startTime, true, null);
            log.info("[Skill] 完成: skillName={}, contentLength={}",
                normalizedSkillName, result.length());
            return result;

        } catch (Exception e) {
            log.error("[Skill] 执行异常: skillName={}, error={}",
                normalizedSkillName, e.getMessage(), e);
            telemetryService.recordError(normalizedSkillName, startTime,
                SkillError.internalError(normalizedSkillName, e));
            return buildUserError(SkillError.internalError(normalizedSkillName, e));
        }
    }

    private String buildUserError(SkillError error) {
        return error.toUserMessage();
    }

    private String buildAskPermissionResponse(PermissionDecision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤔 是否允许执行 Skill: ").append(decision.message()).append("？\n\n");
        if (decision.suggestions() != null && !decision.suggestions().isEmpty()) {
            sb.append("💡 提示: ").append(decision.suggestions().get(0).action())
                .append(" ").append(decision.suggestions().get(0).pattern())
                .append(" -> ").append(decision.suggestions().get(0).permission());
        }
        return sb.toString();
    }
}
