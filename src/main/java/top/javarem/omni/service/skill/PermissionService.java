package top.javarem.omni.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.javarem.omni.model.skill.PermissionDecision;
import top.javarem.omni.model.skill.PermissionRules;
import top.javarem.omni.model.skill.SkillFrontmatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 权限控制服务
 * 混合模式: Frontmatter 安全属性 + 配置文件规则
 */
@Slf4j
@Service
public class PermissionService {

    @Value("${skill.permissions.rules.deny:#{T(java.util.Collections).emptyList()}}")
    private List<String> denyRules;

    @Value("${skill.permissions.rules.allow:#{T(java.util.Collections).emptyList()}}")
    private List<String> allowRules;

    @Value("${skill.permissions.default-behavior:ask}")
    private String defaultBehaviorStr;

    @Value("${skill.permissions.auto-allow-safe-properties:true}")
    private boolean autoAllowSafeProperties;

    /**
     * 检查权限
     */
    public PermissionDecision check(String skillName, SkillFrontmatter fm) {
        // 1. 检查 deny 规则
        if (matchesAnyRule(skillName, denyRules)) {
            log.debug("[PermissionService] DENY by deny-rule: {}", skillName);
            return PermissionDecision.deny(
                "Skill 被权限规则拒绝: " + skillName,
                "deny-rule"
            );
        }

        // 2. 检查 allow 规则
        if (matchesAnyRule(skillName, allowRules)) {
            log.debug("[PermissionService] ALLOW by allow-rule: {}", skillName);
            return PermissionDecision.allow("allow-rule");
        }

        // 3. 安全属性检查
        if (autoAllowSafeProperties && fm != null && fm.hasOnlySafeProperties()) {
            log.debug("[PermissionService] ALLOW by safe properties: {}", skillName);
            return PermissionDecision.allow("safe-properties");
        }

        // 4. 默认行为
        PermissionRules.DefaultBehavior defaultBehavior = parseDefaultBehavior();
        if (defaultBehavior == PermissionRules.DefaultBehavior.ALLOW) {
            return PermissionDecision.allow("default-allow");
        } else if (defaultBehavior == PermissionRules.DefaultBehavior.DENY) {
            return PermissionDecision.deny(
                "Skill 默认被拒绝: " + skillName,
                "default-deny"
            );
        }

        // 5. 默认 ASK
        return PermissionDecision.ask(
            "执行 Skill: " + skillName,
            "user-confirmation",
            buildSuggestions(skillName)
        );
    }

    private boolean matchesAnyRule(String skillName, List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        for (String rule : rules) {
            if (matchesRule(skillName, rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRule(String skillName, String rule) {
        if (rule == null || skillName == null) {
            return false;
        }

        // 通配符匹配
        if (rule.endsWith(":*")) {
            String prefix = rule.substring(0, rule.length() - 2);
            return skillName.startsWith(prefix);
        }

        // 精确匹配
        return skillName.equalsIgnoreCase(rule);
    }

    private PermissionRules.DefaultBehavior parseDefaultBehavior() {
        try {
            return PermissionRules.DefaultBehavior.valueOf(defaultBehaviorStr.toUpperCase());
        } catch (Exception e) {
            return PermissionRules.DefaultBehavior.ASK;
        }
    }

    private List<PermissionDecision.Suggestion> buildSuggestions(String skillName) {
        List<PermissionDecision.Suggestion> suggestions = new ArrayList<>();
        suggestions.add(new PermissionDecision.Suggestion(
            "addRules", "SkillTool", skillName, "allow"
        ));
        suggestions.add(new PermissionDecision.Suggestion(
            "addRules", "SkillTool", skillName + ":*", "allow"
        ));
        return suggestions;
    }
}
