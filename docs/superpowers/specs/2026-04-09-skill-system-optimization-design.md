# Skill 系统优化设计方案

**日期**: 2026-04-09
**状态**: 已批准
**版本**: v1.0

---

## 一、背景与目标

当前 Skill 系统实现仅支持基础的本地文件读取功能，与 Claude Code 的 Skill 系统存在显著差距。本方案旨在通过系统性优化，实现：

- 多源 Skill 发现机制
- 完整 Frontmatter 解析
- 权限控制体系
- Fork 执行模式
- 结构化错误码
- 遥测埋点

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      SkillToolConfig                        │
│                    (Facade / 执行入口)                       │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐│
│  │ SkillLoader │  │Permission   │  │  TelemetryService   ││
│  │ (多源发现)   │  │Service      │  │  (混合埋点)         ││
│  └─────────────┘  └─────────────┘  └─────────────────────┘│
│  ┌─────────────────────────────────────────────────────┐  │
│  │              SkillExecutor (执行引擎)                  │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐            │  │
│  │  │ Inline  │  │  Fork   │  │ Rollback│            │  │
│  │  │Executor │  │Executor │  │ Manager │            │  │
│  │  └─────────┘  └─────────┘  └─────────┘            │  │
│  └─────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、核心组件

### 3.1 SkillFrontmatter（元数据模型）

```java
public record SkillFrontmatter(
    // 基础字段
    String name,
    String description,

    // P0: 安全相关
    List<String> allowedTools,       // 允许的工具列表
    boolean disableModelInvocation,  // 禁止模型调用

    // P0: 执行控制
    String model,                    // 指定模型 (opus/sonnet/haiku)
    String effort,                   // low/medium/high
    String context,                  // inline/fork

    // P2: 参数
    String argumentHint,              // "<code> <message>"
    List<String> argumentNames,      // ["code", "message"]

    // P3: 动态激活
    List<String> paths,              // 条件激活路径
    List<String> conditions,         // 条件表达式

    // 元数据
    boolean userInvocable,           // 用户可调用
    boolean enabled,                  // 全局开关
    String version,
    Map<String, String> hooks
) {}
```

### 3.2 SkillSource（多源枚举）

```java
public enum SkillSource {
    BUNDLED("bundled", 0),   // classpath:/skills/
    MANAGED("managed", 1),   // ~/.claude/.claude/skills
    USER("user", 2),         // ~/.claude/skills
    PROJECT("project", 3);   // .claude/skills

    private final String name;
    private final int priority;
}
```

### 3.3 SkillDiscovery（多源发现）

```java
public class SkillDiscovery {
    DiscoveredSkill discover(String skillName);  // 按优先级返回第一个匹配的
    List<DiscoveredSkill> discoverAll();

    // 优先级: BUNDLED(0) > MANAGED(1) > USER(2) > PROJECT(3)
}
```

### 3.4 PermissionService（权限控制）

```java
public record PermissionDecision(
    Behavior behavior,    // ALLOW / DENY / ASK
    String message,
    String reason,
    List<Suggestion> suggestions
) {
    public enum Behavior { ALLOW, DENY, ASK }
}

// 检查流程
// 1. 配置文件 deny 规则 → DENY
// 2. 配置文件 allow 规则 → ALLOW
// 3. Frontmatter 安全属性检查 → ALLOW / ASK
// 4. 默认 ASK
```

### 3.5 TelemetryService（遥测埋点）

```java
public record SkillTelemetry(
    Long id,
    String skillName,
    String source,
    String executionMode,
    Long invokedAt,
    Long durationMs,
    boolean success,
    String errorCode,
    String errorMessage,
    String argsHash
) {}

// 混合模式
// - 始终写日志 (log.info/warn)
// - 可选写数据库 (skill_telemetry 表)
```

### 3.6 SkillExecutor（执行引擎）

```java
public class SkillExecutor {
    String executeInline(Skill skill, String args);
    CompletableFuture<String> executeForked(Skill skill, String args);

    // 根据 effort 自动选择
    ExecutionMode determineMode(SkillFrontmatter fm) {
        if (fm.context() != null) {
            return fm.context().equals("fork") ? FORK : INLINE;
        }
        return fm.effort() == "high" ? FORK : INLINE;
    }
}
```

### 3.7 SkillError（错误处理）

```java
public record SkillError(
    int code,
    String message,
    String suggestion,
    String skillName,
    Map<String, Object> context
) {
    public static final int INVALID_FORMAT = 1;
    public static final int NOT_FOUND = 2;
    public static final int DISABLED = 3;
    public static final int DISABLE_MODEL_INVOCATION = 4;
    public static final int NOT_PROMPT_TYPE = 5;
    public static final int REMOTE_NOT_DISCOVERED = 6;
    public static final int PERMISSION_DENIED = 7;
    public static final int INTERNAL_ERROR = -1;
}
```

---

## 四、执行流程

```
getSkillContent(skillName, args)
    │
    ├─1─ ValidationResult.validate(skillName)
    │       └─ 返回格式错误或名称校验失败
    │
    ├─2─ SkillDiscovery.discover(skillName)
    │       ├─ 遍历 SkillSource 优先级
    │       ├─ 解析 Frontmatter
    │       └─ 返回 DiscoveredSkill 或 null
    │
    ├─3─ PermissionService.check(skillName, fm)
    │       ├─ 配置规则检查 → ALLOW/DENY
    │       ├─ 安全属性检查 → ALLOW/ASK
    │       └─ 返回 PermissionDecision
    │
    ├─4─ SkillExecutor.execute(skill, args, fm)
    │       ├─ determineMode(fm) → INLINE / FORK
    │       ├─ INLINE: executeInline()
    │       └─ FORK: executeForked() → AgentToolConfig
    │
    ├─5─ TelemetryService.record(telemetry)
    │       ├─ log.info(...)
    │       └─ 可选: jdbcTemplate.insert(...)
    │
    └─6─ 返回结果 / 错误
```

---

## 五、参数替换逻辑

```java
String replaceArguments(String content, String args, SkillFrontmatter fm) {
    if (args == null || args.isBlank()) return content;

    // 1. 简单占位符
    content = content.replace("{args}", args);
    content = content.replace("{arg}", args);
    content = content.replace("$ARGUMENTS", args);

    // 2. ARGUMENTS: 格式
    content = content.replace("ARGUMENTS: {arg}", "ARGUMENTS: " + args);
    content = content.replace("ARGUMENTS: {args}", "ARGUMENTS: " + args);
    content = content.replace("ARGUMENTS:", "ARGUMENTS: " + args);

    // 3. 命名参数 (argumentHint: "<code> <message>")
    if (fm.argumentNames() != null && !fm.argumentNames().isEmpty()) {
        String[] argParts = args.split("\\s+");
        for (int i = 0; i < fm.argumentNames().size() && i < argParts.length; i++) {
            String paramName = fm.argumentNames().get(i).replace("<", "").replace(">", "");
            content = content.replace("{" + paramName + "}", argParts[i]);
        }
    }

    return content;
}
```

---

## 六、数据库设计

```sql
-- Skill 遥测表 (可选，由 telemetry.enabled 控制)
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

---

## 七、配置项

```yaml
skill:
  discovery:
    root: file:${user.home}/.claude
    enabled: true
    path: skills/**/SKILL.md
    sources:
      bundled:
        enabled: true
        path: classpath:/skills/
      managed:
        enabled: true
        path: ${skill.discovery.root}/.claude/skills
      user:
        enabled: true
        path: ${skill.discovery.root}/skills
      project:
        enabled: true
        path: .claude/skills

  permissions:
    rules:
      deny:
        - "dangerous-operation"
        - "admin:*"
      allow:
        - "pdf"
        - "xlsx"
        - "docx"
    default-behavior: ask
    auto-allow-safe-properties: true

  execution:
    fork-threshold: high
    fork-timeout: 300000
    inline-timeout: 60000
    max-fork-concurrency: 3

  telemetry:
    enabled: true
    log-detail: full
    persist: false
    sample-rate: 1.0

  error:
    user-detail: simple
    log-detail: full
```

---

## 八、文件改动清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `SkillFrontmatter.java` | 新建 | P0 元数据模型 |
| `SkillDiscovery.java` | 新建 | P0 多源发现 |
| `PermissionService.java` | 新建 | P0 权限控制 |
| `TelemetryService.java` | 新建 | P2 遥测埋点 |
| `SkillExecutor.java` | 新建 | P1 执行引擎 |
| `SkillErrors.java` | 新建 | P1 错误码 |
| `SkillToolConfig.java` | 重构 | P0 集成上述组件 |
| `SkillLoader.java` | 增强 | 适配新发现机制 |
| `application.yml` | 新增配置 | P0 权限规则、遥测开关 |

---

## 九、错误码对照

| 错误码 | 含义 | 用户提示 |
|--------|------|----------|
| 1 | INVALID_FORMAT | Skill 格式错误 |
| 2 | NOT_FOUND | Skill 不存在 |
| 3 | DISABLED | Skill 已禁用 |
| 4 | DISABLE_MODEL_INVOCATION | 此 Skill 禁止模型调用 |
| 7 | PERMISSION_DENIED | 无权执行 |
| -1 | INTERNAL_ERROR | 执行失败 |

---

## 十、优先级

| 优先级 | 优化项 | 说明 |
|--------|--------|------|
| P0 | 多源 Skill 发现 | 支持 bundled/project/user 多层叠加 |
| P0 | 完整 Frontmatter 解析 | 解锁 allowedTools/effort/model 等能力 |
| P0 | 权限控制 | 企业级安全必备 |
| P1 | 错误码规范化 | 便于调试和问题定位 |
| P1 | Fork 执行模式 | 支持复杂 Skill 独立执行 |
| P2 | 命名参数替换 | 更灵活的参数传递 |
| P2 | 遥测埋点 | 运营分析 |
| P3 | 动态条件激活 | 根据路径自动启用 Skill |
