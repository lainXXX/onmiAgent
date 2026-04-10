package top.javarem.omni.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import top.javarem.omni.model.skill.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill 多源发现服务
 */
@Slf4j
@Service
public class SkillDiscovery {

    private final ResourcePatternResolver resourcePatternResolver;
    private final Yaml yaml;

    @Value("${skill.discovery.root:file:${user.home}/.omni}")
    private String skillRootPath;

    @Value("${skill.discovery.path:skills/**/SKILL.md}")
    private String skillPathTemplate;

    @Value("${skill.discovery.sources.bundled.enabled:true}")
    private boolean bundledEnabled;

    @Value("${skill.discovery.sources.bundled.path:classpath:/skills/}")
    private String bundledPath;

    @Value("${skill.discovery.sources.user.enabled:true}")
    private boolean userEnabled;

    @Value("${skill.discovery.sources.project.enabled:false}")
    private boolean projectEnabled;

    @Value("${skill.discovery.sources.managed.enabled:true}")
    private boolean managedEnabled;

    // 缓存已发现的 Skill
    private final Map<String, DiscoveredSkill> skillCache = new ConcurrentHashMap<>();

    public SkillDiscovery(ResourceLoader resourceLoader) {
        this.resourcePatternResolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        this.yaml = new Yaml();
    }

    /**
     * 按名称发现 Skill
     */
    public DiscoveredSkill discover(String skillName) {
        if (skillName == null || skillName.trim().isEmpty()) {
            return null;
        }

        String normalizedName = skillName.trim().toLowerCase();

        // 检查缓存
        if (skillCache.containsKey(normalizedName)) {
            return skillCache.get(normalizedName);
        }

        // 按优先级遍历来源
        List<SkillSource> sources = Arrays.stream(SkillSource.values())
            .sorted(Comparator.comparingInt(SkillSource::getPriority))
            .toList();

        for (SkillSource source : sources) {
            if (!isSourceEnabled(source)) {
                continue;
            }

            // BUNDLED 使用 ResourcePatternResolver，其他使用 Files.exists()
            if (source == SkillSource.BUNDLED) {
                DiscoveredSkill discovered = discoverBundled(normalizedName);
                if (discovered != null) {
                    skillCache.put(normalizedName, discovered);
                    return discovered;
                }
            } else {
                Path path = buildPath(source, normalizedName);
                if (path != null && Files.exists(path)) {
                    DiscoveredSkill discovered = parseSkill(source, path);
                    if (discovered != null) {
                        skillCache.put(normalizedName, discovered);
                        return discovered;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 发现所有 Skill
     */
    public List<DiscoveredSkill> discoverAll() {
        List<DiscoveredSkill> result = new ArrayList<>();

        for (SkillSource source : SkillSource.values()) {
            if (!isSourceEnabled(source)) {
                continue;
            }

            String pattern = buildGlobPattern(source);
            try {
                org.springframework.core.io.Resource[] resources =
                    resourcePatternResolver.getResources(pattern);

                for (var resource : resources) {
                    Path path = resource.getFile().toPath();
                    DiscoveredSkill discovered = parseSkill(source, path);
                    if (discovered != null) {
                        result.add(discovered);
                    }
                }
            } catch (Exception e) {
                log.warn("[SkillDiscovery] 扫描失败: source={}, pattern={}", source, pattern, e);
            }
        }

        return result;
    }

    /**
     * 使用 ResourcePatternResolver 发现 BUNDLED 资源
     */
    private DiscoveredSkill discoverBundled(String skillName) {
        String pattern = "classpath:/skills/" + skillName + "/SKILL.md";
        try {
            org.springframework.core.io.Resource[] resources =
                resourcePatternResolver.getResources(pattern);
            if (resources.length > 0) {
                Path path = resources[0].getFile().toPath();
                return parseSkill(SkillSource.BUNDLED, path);
            }
        } catch (Exception e) {
            log.debug("[SkillDiscovery] BUNDLED 发现失败: pattern={}", pattern, e);
        }
        return null;
    }

    /**
     * 解析 Skill 文件
     */
    private DiscoveredSkill parseSkill(SkillSource source, Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            SkillFrontmatter fm = parseFrontmatter(content);

            if (fm == null || fm.name() == null) {
                log.warn("[SkillDiscovery] 跳过无效 Skill: {}", path);
                return null;
            }

            String body = extractBody(content);

            return new DiscoveredSkill(source, path, fm, content, body);

        } catch (Exception e) {
            log.error("[SkillDiscovery] 解析失败: {}", path, e);
            return null;
        }
    }

    /**
     * 解析 Frontmatter
     */
    private SkillFrontmatter parseFrontmatter(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return null;
        }

        int endIndex = trimmed.indexOf("---", 3);
        if (endIndex == -1) {
            return null;
        }

        String yamlContent = trimmed.substring(3, endIndex).trim();

        try {
            Map<String, Object> data = yaml.load(yamlContent);
            if (data == null || !data.containsKey("name")) {
                return null;
            }

            // 解析 argumentHint 提取 argumentNames
            List<String> argumentNames = null;
            Object argumentHint = data.get("argument-hint");
            if (argumentHint instanceof String hint) {
                argumentNames = extractArgumentNames(hint);
            }

            return new SkillFrontmatter(
                (String) data.get("name"),
                (String) data.get("description"),
                parseList(data.get("allowed-tools")),
                parseBoolean(data.get("disable-model-invocation")),
                (String) data.get("model"),
                (String) data.get("effort"),
                (String) data.get("context"),
                (String) argumentHint,
                argumentNames,
                parseList(data.get("paths")),
                parseList(data.get("conditions")),
                parseBoolean(data.get("user-invocable"), true),
                parseBoolean(data.get("enabled"), true),
                (String) data.get("version"),
                parseMap(data.get("hooks"))
            );

        } catch (Exception e) {
            log.error("[SkillDiscovery] Frontmatter 解析失败", e);
            return null;
        }
    }

    private List<String> extractArgumentNames(String hint) {
        if (hint == null || hint.isBlank()) {
            return List.of();
        }
        // 提取 <> 包裹的参数名
        return Arrays.stream(hint.split("\\s+"))
            .map(s -> s.replace("<", "").replace(">", "").replace(",", ""))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    private String extractBody(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return trimmed;
        }
        int endIndex = trimmed.indexOf("---", 3);
        if (endIndex == -1) {
            return trimmed;
        }
        return trimmed.substring(endIndex + 3).trim();
    }

    private boolean isSourceEnabled(SkillSource source) {
        return switch (source) {
            case BUNDLED -> bundledEnabled;
            case USER -> userEnabled;
            case PROJECT -> projectEnabled;
            case MANAGED -> managedEnabled;
        };
    }

    private Path buildPath(SkillSource source, String skillName) {
        // 解析 ${user.home} 并去除 file: 前缀
        String resolvedRoot = resolveRootPath();

        return switch (source) {
            // BUNDLED 使用 discoverBundled() 方法，不走此路径
            case USER -> Paths.get(resolvedRoot, "skills", skillName, "SKILL.md");
            case PROJECT -> Paths.get(".claude/skills", skillName, "SKILL.md");
            case MANAGED -> Paths.get(resolvedRoot, ".claude/skills", skillName, "SKILL.md");
            default -> null;
        };
    }

    /**
     * 解析 root 路径，处理 ${user.home} 和 file: 前缀
     */
    private String resolveRootPath() {
        String root = skillRootPath;

        // 处理 Spring 未解析的 ${user.home}
        if (root.contains("${user.home}")) {
            root = root.replace("${user.home}", System.getProperty("user.home"));
        }

        // 去除 file: 前缀（如果是 file: 开头的 URI）
        if (root.startsWith("file:")) {
            root = root.substring(5);
        }

        // 标准化路径分隔符
        root = root.replace("\\", "/");

        // 确保末尾有 /
        if (!root.endsWith("/")) {
            root += "/";
        }

        return root;
    }

    private String buildGlobPattern(SkillSource source) {
        String root = resolveRootPath();

        return switch (source) {
            case BUNDLED -> bundledPath + "**/SKILL.md";
            case USER -> root + "skills/**/SKILL.md";
            case PROJECT -> ".claude/skills/**/SKILL.md";
            case MANAGED -> root + ".claude/skills/**/SKILL.md";
        };
    }

    private List<String> parseList(Object value) {
        if (value == null) return null;
        if (value instanceof List) {
            return ((List<?>) value).stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        }
        return List.of(value.toString());
    }

    private boolean parseBoolean(Object value) {
        return parseBoolean(value, false);
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMap(Object value) {
        if (value == null) return null;
        if (value instanceof Map) {
            return ((Map<?, ?>) value).entrySet().stream()
                .collect(Collectors.toMap(
                    e -> e.getKey().toString(),
                    e -> e.getValue().toString()
                ));
        }
        return null;
    }
}
