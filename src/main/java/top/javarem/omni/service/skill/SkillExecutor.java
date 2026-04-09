package top.javarem.omni.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.javarem.omni.model.skill.DiscoveredSkill;
import top.javarem.omni.model.skill.SkillFrontmatter;

import java.util.concurrent.CompletableFuture;

/**
 * Skill 执行引擎
 * 支持 Inline 和 Fork 两种模式
 */
@Slf4j
@Service
public class SkillExecutor {

    public enum ExecutionMode {
        INLINE, FORK
    }

    @Value("${skill.execution.fork-threshold:high}")
    private String forkThreshold;

    @Value("${skill.execution.inline-timeout:60000}")
    private long inlineTimeout;

    @Value("${skill.execution.fork-timeout:300000}")
    private long forkTimeout;

    /**
     * 执行 Skill
     */
    public String execute(DiscoveredSkill skill, String args) {
        ExecutionMode mode = determineMode(skill.frontmatter());

        switch (mode) {
            case FORK -> {
                log.info("[SkillExecutor] Fork 模式: {}", skill.getName());
                return executeForked(skill, args).join();
            }
            default -> {
                log.info("[SkillExecutor] Inline 模式: {}", skill.getName());
                return executeInline(skill, args);
            }
        }
    }

    /**
     * Inline 执行
     */
    public String executeInline(DiscoveredSkill skill, String args) {
        String content = skill.bodyContent();
        return replaceArguments(content, args, skill.frontmatter());
    }

    /**
     * Fork 执行（异步）
     */
    public CompletableFuture<String> executeForked(DiscoveredSkill skill, String args) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: 后续集成 AgentToolConfig 实现 Fork 模式
            // 当前返回 Inline 结果作为占位
            log.warn("[SkillExecutor] Fork 模式暂未实现，使用 Inline 模式");
            return executeInline(skill, args);
        });
    }

    /**
     * 确定执行模式
     */
    public ExecutionMode determineMode(SkillFrontmatter fm) {
        if (fm == null) {
            return ExecutionMode.INLINE;
        }

        // 显式指定 context
        if (fm.context() != null) {
            return "fork".equalsIgnoreCase(fm.context())
                ? ExecutionMode.FORK
                : ExecutionMode.INLINE;
        }

        // 根据 effort 自动判断
        if (fm.effort() != null) {
            return switch (fm.effort().toLowerCase()) {
                case "high" -> ExecutionMode.FORK;
                case "low", "medium" -> ExecutionMode.INLINE;
                default -> ExecutionMode.INLINE;
            };
        }

        return ExecutionMode.INLINE;
    }

    /**
     * 替换参数占位符
     */
    private String replaceArguments(String content, String args, SkillFrontmatter fm) {
        if (args == null || args.isBlank()) {
            return content;
        }

        // 1. 简单占位符
        content = content.replace("{args}", args);
        content = content.replace("{arg}", args);
        content = content.replace("$ARGUMENTS", args);

        // 2. ARGUMENTS: 格式
        content = content.replace("ARGUMENTS: {arg}", "ARGUMENTS: " + args);
        content = content.replace("ARGUMENTS: {args}", "ARGUMENTS: " + args);
        content = content.replace("ARGUMENTS:", "ARGUMENTS: " + args);

        // 3. 命名参数
        if (fm != null && fm.argumentNames() != null && !fm.argumentNames().isEmpty()) {
            String[] argParts = args.split("\\s+");
            for (int i = 0; i < fm.argumentNames().size() && i < argParts.length; i++) {
                String paramName = fm.argumentNames().get(i)
                    .replace("<", "").replace(">", "");
                content = content.replace("{" + paramName + "}", argParts[i]);
            }
        }

        return content;
    }
}
