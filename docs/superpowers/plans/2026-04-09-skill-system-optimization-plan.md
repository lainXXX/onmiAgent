# Skill 系统优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现完整的多源 Skill 发现、Frontmatter 解析、权限控制、Fork 执行模式和遥测埋点

**Architecture:** 基于现有 Spring Boot 3.5.10 + Spring AI 1.1.3 架构，新建独立的 model/service 层，复用现有 SkillLoader 的向量存储机制

**Tech Stack:** Java 21, Spring Boot, Spring AI, SnakeYAML, JdbcTemplate

---

## 文件结构

```
src/main/java/top/javarem/omni/
├── model/skill/
│   ├── SkillFrontmatter.java          # 新建 - P0
│   ├── DiscoveredSkill.java           # 新建 - P0
│   ├── PermissionDecision.java        # 新建 - P0
│   ├── PermissionRules.java           # 新建 - P0
│   ├── SkillError.java                # 新建 - P1
│   ├── SkillTelemetry.java            # 新建 - P2
│   └── SkillSource.java                # 新建 - P0
├── service/skill/
│   ├── SkillDiscovery.java            # 新建 - P0
│   ├── PermissionService.java         # 新建 - P0
│   ├── TelemetryService.java          # 新建 - P2
│   └── SkillExecutor.java             # 新建 - P1
└── tool/
    └── SkillToolConfig.java           # 重构 - P0

### SkillLoader 集成说明

现有 `SkillLoader` 负责：
- 启动时扫描 skills 目录
- 向量存储（vectorStore）写入
- Skills Guide 生成

新增 `SkillDiscovery` 负责：
- 运行时按需发现单个 Skill
- Frontmatter 完整解析

两者职责互补，`SkillLoader` 的向量存储能力不在本次优化范围内。
```

---

## Task 1: 创建 SkillSource 枚举

**Files:**
- Create: `src/main/java/top/javarem/omni/model/skill/SkillSource.java`

- [ ] **Step 1: 创建文件**

```java
package top.javarem.omni.model.skill;

/**
 * Skill 来源枚举
 * 按优先级排序（数字越小优先级越高）
 */
public enum SkillSource {
    BUNDLED("bundled", 0),   // classpath:/skills/
    MANAGED("managed", 1),   // ~/.claude/.claude/skills
    USER("user", 2),         // ~/.claude/skills
    PROJECT("project", 3);   // .claude/skills

    private final String name;
    private final int priority;

    SkillSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/model/skill/SkillSource.java
git commit -m "feat(skill): add SkillSource enum for multi-source discovery"
```

---

## Task 2: 创建 SkillFrontmatter 记录

**Files:**
- Create: `src/main/java/top/javarem/omni/model/skill/SkillFrontmatter.java`

- [ ] **Step 1: 创建文件**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/model/skill/SkillFrontmatter.java
git commit -m "feat(skill): add SkillFrontmatter record for full frontmatter parsing"
```

---

## Task 3: 创建 DiscoveredSkill 记录

**Files:**
- Create: `src/main/java/top/javarem/omni/model/skill/DiscoveredSkill.java`

- [ ] **Step 1: 创建文件**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/model/skill/DiscoveredSkill.java
git commit -m "feat(skill): add DiscoveredSkill record"
```

---

## Task 4: 创建 PermissionDecision 和 PermissionRules

**Files:**
- Create: `src/main/java/top/javarem/omni/model/skill/PermissionDecision.java`
- Create: `src/main/java/top/javarem/omni/model/skill/PermissionRules.java`

- [ ] **Step 1: 创建 PermissionDecision.java**

```java
package top.javarem.omni.model.skill;

import java.util.List;

/**
 * 权限决策结果
 */
public record PermissionDecision(
    Behavior behavior,
    String message,
    String reason,
    List<Suggestion> suggestions
) {
    public enum Behavior {
        ALLOW, DENY, ASK
    }

    public record Suggestion(
        String action,
        String target,
        String pattern,
        String permission
    ) {}

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(Behavior.ALLOW, null, reason, null);
    }

    public static PermissionDecision deny(String message, String reason) {
        return new PermissionDecision(Behavior.DENY, message, reason, null);
    }

    public static PermissionDecision ask(String message, String reason, List<Suggestion> suggestions) {
        return new PermissionDecision(Behavior.ASK, message, reason, suggestions);
    }
}
```

- [ ] **Step 2: 创建 PermissionRules.java**

```java
package top.javarem.omni.model.skill;

import java.util.List;

/**
 * 权限规则配置
 */
public record PermissionRules(
    List<String> deny,
    List<String> allow,
    DefaultBehavior defaultBehavior
) {
    public enum DefaultBehavior {
        ALLOW, DENY, ASK
    }

    public static PermissionRules defaultRules() {
        return new PermissionRules(
            List.of(),
            List.of(),
            DefaultBehavior.ASK
        );
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/top/javarem/omni/model/skill/PermissionDecision.java
git add src/main/java/top/javarem/omni/model/skill/PermissionRules.java
git commit -m "feat(skill): add PermissionDecision and PermissionRules"
```

---

## Task 5: 创建 SkillError 错误码

**Files:**
- Create: `src/main/java/top/javarem/omni/model/skill/SkillError.java`

- [ ] **Step 1: 创建文件**

```java
package top.javarem.omni.model.skill;

import java.util.Map;

/**
 * Skill 错误结构
 */
public record SkillError(
    int code,
    String message,
    String suggestion,
    String skillName,
    Map<String, Object> context
) {
    // 错误码常量
    public static final int INVALID_FORMAT = 1;
    public static final int NOT_FOUND = 2;
    public static final int DISABLED = 3;
    public static final int DISABLE_MODEL_INVOCATION = 4;
    public static final int NOT_PROMPT_TYPE = 5;
    public static final int REMOTE_NOT_DISCOVERED = 6;
    public static final int PERMISSION_DENIED = 7;
    public static final int INTERNAL_ERROR = -1;

    public static SkillError invalidFormat(String skillName) {
        return new SkillError(INVALID_FORMAT,
            "Skill 格式错误: " + skillName,
            "Skill 名称应为字母、数字和连字符的组合",
            skillName, null);
    }

    public static SkillError notFound(String skillName) {
        return new SkillError(NOT_FOUND,
            "Skill 不存在: " + skillName,
            "请检查 Skill 名称是否正确，或确认该 Skill 已创建",
            skillName, null);
    }

    public static SkillError disabled(String skillName) {
        return new SkillError(DISABLED,
            "Skill 已禁用: " + skillName,
            "该 Skill 当前已被禁用",
            skillName, null);
    }

    public static SkillError permissionDenied(String skillName) {
        return new SkillError(PERMISSION_DENIED,
            "无权执行 Skill: " + skillName,
            "请联系管理员授予执行权限",
            skillName, null);
    }

    public static SkillError internalError(String skillName, Exception e) {
        return new SkillError(INTERNAL_ERROR,
            "执行失败: " + e.getMessage(),
            "请稍后重试，或联系管理员",
            skillName, Map.of("errorType", e.getClass().getSimpleName()));
    }

    /**
     * 构建用户友好的错误提示
     */
    public String toUserMessage() {
        return "❌ " + message + (suggestion != null ? "\n\n💡 建议: " + suggestion : "");
    }

    /**
     * 构建日志字符串
     */
    public String toLogString() {
        return String.format("[SkillError] code=%d, skill=%s, message=%s",
            code, skillName, message);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/model/skill/SkillError.java
git commit -m "feat(skill): add SkillError with structured error codes"
```

---

## Task 6: 创建 SkillTelemetry 遥测模型

**Files:**
- Create: `src/main/java/top/javarem/omni/model/skill/SkillTelemetry.java`

- [ ] **Step 1: 创建文件**

```java
package top.javarem.omni.model.skill;

/**
 * Skill 调用遥测数据
 */
public record SkillTelemetry(
    Long id,
    String skillName,
    String source,
    String executionMode,
    Long invokedAt,
    Long durationMs,
    boolean success,
    Integer errorCode,
    String errorMessage,
    String argsHash
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String skillName;
        private String source;
        private String executionMode;
        private Long invokedAt;
        private Long durationMs;
        private boolean success;
        private Integer errorCode;
        private String errorMessage;
        private String argsHash;

        public Builder skillName(String skillName) {
            this.skillName = skillName;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder executionMode(String executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder invokedAt(Long invokedAt) {
            this.invokedAt = invokedAt;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorCode(Integer errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder argsHash(String argsHash) {
            this.argsHash = argsHash;
            return this;
        }

        public SkillTelemetry build() {
            return new SkillTelemetry(
                null, skillName, source, executionMode,
                invokedAt, durationMs, success, errorCode, errorMessage, argsHash
            );
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/model/skill/SkillTelemetry.java
git commit -m "feat(skill): add SkillTelemetry model"
```

---

## Task 7: 创建 SkillDiscovery 服务

**Files:**
- Create: `src/main/java/top/javarem/omni/service/skill/SkillDiscovery.java`

- [ ] **Step 1: 创建文件**

```java
package top.javarem.omni.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.yaml.snakeyaml.Yaml;
import top.javarem.omni.model.skill.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
        String root = skillRootPath.replace("${user.home}", System.getProperty("user.home"));
        // 确保 root 末尾有 /
        if (!root.endsWith("/")) {
            root += "/";
        }

        return switch (source) {
            // BUNDLED 使用 discoverBundled() 方法，不走此路径
            case USER -> Paths.get(root, "skills", skillName, "SKILL.md");
            case PROJECT -> Paths.get(".claude/skills", skillName, "SKILL.md");
            case MANAGED -> Paths.get(root, ".claude/skills", skillName, "SKILL.md");
            default -> null;
        };
    }

    private String buildGlobPattern(SkillSource source) {
        return switch (source) {
            case BUNDLED -> bundledPath + "**/SKILL.md";
            case USER -> skillRootPath + "/skills/**/SKILL.md";
            case PROJECT -> ".claude/skills/**/SKILL.md";
            case MANAGED -> skillRootPath + "/.claude/skills/**/SKILL.md";
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/service/skill/SkillDiscovery.java
git commit -m "feat(skill): add SkillDiscovery service with multi-source support"
```

---

## Task 8: 创建 PermissionService

**Files:**
- Create: `src/main/java/top/javarem/omni/service/skill/PermissionService.java`

- [ ] **Step 1: 创建文件**

```java
package top.javarem.omni.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.javarem.omni.model.skill.PermissionDecision;
import top.javarem.omni.model.skill.PermissionRules;
import top.javarem.omni.model.skill.SkillFrontmatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/service/skill/PermissionService.java
git commit -m "feat(skill): add PermissionService with hybrid rules"
```

---

## Task 9: 创建 TelemetryService

**Files:**
- Create: `src/main/java/top/javarem/omni/service/skill/TelemetryService.java`

- [ ] **Step 1: 创建文件**

```java
package top.javarem.omni.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.javarem.omni.model.skill.SkillError;
import top.javarem.omni.model.skill.SkillTelemetry;

import java.util.Map;

/**
 * Skill 遥测服务
 * 混合模式: 日志 + 可选数据库
 */
@Slf4j
@Service
public class TelemetryService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${skill.telemetry.enabled:true}")
    private boolean enabled;

    @Value("${skill.telemetry.persist:false}")
    private boolean persist;

    @Value("${skill.telemetry.log-detail:full}")
    private String logDetail;

    public TelemetryService(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录 Skill 调用
     */
    public void record(String skillName, long startTime, boolean success, Integer errorCode) {
        long duration = System.currentTimeMillis() - startTime;

        if ("full".equals(logDetail)) {
            log.info("[SkillTelemetry] skill={}, duration={}ms, success={}, errorCode={}",
                skillName, duration, success, errorCode);
        } else if ("simple".equals(logDetail)) {
            log.debug("[SkillTelemetry] skill={}, {}ms, {}",
                skillName, duration, success ? "OK" : "FAIL");
        }

        if (persist && enabled) {
            persistTelemetry(skillName, duration, success, errorCode, null);
        }
    }

    /**
     * 记录错误
     */
    public void recordError(String skillName, long startTime, SkillError error) {
        long duration = System.currentTimeMillis() - startTime;

        if ("full".equals(logDetail)) {
            log.error("[SkillTelemetry] skill={}, duration={}ms, error={}",
                skillName, duration, error.toLogString());
        } else {
            log.error("[SkillTelemetry] skill={}, {}ms, FAIL",
                skillName, duration);
        }

        if (persist && enabled) {
            persistTelemetry(skillName, duration, false,
                error != null ? error.code() : SkillError.INTERNAL_ERROR,
                error != null ? error.message() : null);
        }
    }

    private void persistTelemetry(String skillName, long durationMs,
                                  boolean success, Integer errorCode, String errorMessage) {
        try {
            String sql = """
                INSERT INTO skill_telemetry
                (skill_name, source, execution_mode, invoked_at, duration_ms, success, error_code, error_message)
                VALUES (?, ?, ?, NOW(), ?, ?, ?, ?)
                """;

            jdbcTemplate.update(sql,
                skillName, "UNKNOWN", "INLINE",
                durationMs, success, errorCode, errorMessage);

        } catch (Exception e) {
            log.warn("[TelemetryService] 遥测数据写入失败", e);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/service/skill/TelemetryService.java
git commit -m "feat(skill): add TelemetryService with hybrid logging"
```

---

## Task 10: 创建 SkillExecutor

**Files:**
- Create: `src/main/java/top/javarem/omni/service/skill/SkillExecutor.java`

- [ ] **Step 1: 创建文件**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/service/skill/SkillExecutor.java
git commit -m "feat(skill): add SkillExecutor with inline/fork modes"
```

---

## Task 11: 重构 SkillToolConfig

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/SkillToolConfig.java`

- [ ] **Step 1: 读取当前实现**

```java
// 当前实现较为简单，需要重构集成新组件
```

- [ ] **Step 2: 重构 SkillToolConfig**

```java
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
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/SkillToolConfig.java
git commit -m "refactor(skill): refactor SkillToolConfig with new components"
```

---

## Task 12: 更新配置文件

**Files:**
- Modify: `src/main/resources/application-dev.yml`

- [ ] **Step 1: 添加 Skill 配置**

在 `application-dev.yml` 末尾添加:

```yaml
# Skill 系统配置
skill:
  discovery:
    root: file:${user.home}/.omni/
    path: skills/**/SKILL.md
    sources:
      bundled:
        enabled: true
        path: classpath:/skills/
      managed:
        enabled: true
      user:
        enabled: true
      project:
        enabled: false

  permissions:
    rules:
      deny:
        - "dangerous-operation"
      allow:
        - "pdf"
        - "xlsx"
        - "docx"
    default-behavior: ask
    auto-allow-safe-properties: true

  execution:
    fork-threshold: high
    inline-timeout: 60000
    fork-timeout: 300000

  telemetry:
    enabled: true
    log-detail: full
    persist: false

  error:
    user-detail: simple
    log-detail: full
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application-dev.yml
git commit -m "feat(skill): add skill system configuration"
```

---

## Task 13: 创建数据库表（可选）

**Files:**
- Create: `docs/superpowers/sql/skill_telemetry.sql`

- [ ] **Step 1: 创建 SQL 文件**

```sql
-- Skill 遥测表 (可选，由 skill.telemetry.persist 控制)
CREATE TABLE IF NOT EXISTS skill_telemetry (
    id              BIGSERIAL PRIMARY KEY,
    skill_name      VARCHAR(128) NOT NULL,
    source          VARCHAR(32) NOT NULL,
    execution_mode  VARCHAR(16) NOT NULL,
    invoked_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    duration_ms     BIGINT,
    success         BOOLEAN NOT NULL,
    error_code      INT,
    error_message   TEXT,
    args_hash       VARCHAR(64),

    INDEX idx_skill_name (skill_name),
    INDEX idx_invoked_at (invoked_at),
    INDEX idx_success (success)
) COMMENT 'Skill 调用遥测表';
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/sql/skill_telemetry.sql
git commit -m "docs(skill): add skill_telemetry table schema"
```

---

## 执行方式

**Plan complete and saved to `docs/superpowers/plans/2026-04-09-skill-system-optimization-plan.md`.**

**两个执行选项：**

**1. Subagent-Driven (recommended)** - 每个 Task 由独立子 Agent 执行，Task 间有审核间隙，快速迭代

**2. Inline Execution** - 当前 Session 内顺序执行，带检查点的批量执行

请选择执行方式？
