# Bash 包重构设计文档

> **文档版本:** v1.0
> **创建日期:** 2026-03-28
> **状态:** 待评审

---

## 1. 重构目标

### 1.1 当前问题

| 问题 | 文件 |
|------|------|
| 冗余 DTO | `DangerousCheckResult` + `ApprovalCheckResult` |
| 重复格式化 | `ResponseBuilder` + `OutputProcessor` |
| 过度拆分 | 12 个文件，功能分散 |

### 1.2 重构原则

- **高内聚**：一个功能一个类，内部逻辑紧凑
- **低耦合**：外部只依赖入口，内部自我完备
- **可移除性**：安全检查作为独立组件，后续可复用

### 1.3 重要说明

**黑名单检测和路径校验将后续作为通用能力，抽离到独立模块。**
当前 bash 包**不包含**安全检查逻辑，安全检查将在后续设计中作为 `SecurityModule` 供所有工具（bash/write/read/edit）复用。

---

## 2. 目标结构

```
tool/bash/
├── BashToolConfig.java       # 入口：@Tool 定义、参数校验
├── BashExecutor.java         # 核心：ProcessBuilder、超时、输出捕获
├── ResponseFormatter.java     # 格式化：ANSI过滤、截断、成功/错误信息
└── BashConstants.java         # 常量：超时配置、输出限制
```

**从 12 个文件 → 4 个文件（简化版）**

---

## 3. 组件设计

### 3.1 BashConstants.java

**职责**：静态常量集中管理

```java
package top.javarem.omni.tool.bash;

/**
 * Bash 工具常量定义
 */
public final class BashConstants {

    private BashConstants() {}

    // ==================== 超时配置 ====================
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int MAX_TIMEOUT_SECONDS = 300;
    public static final int KILL_WAIT_SECONDS = 3;

    // ==================== 输出限制 ====================
    public static final int MAX_OUTPUT_CHARS = 6000;
    public static final int KEEP_START = 1000;
    public static final int KEEP_END = 5000;

    // ==================== 系统信息 ====================
    public static final String WORKSPACE = System.getProperty("user.dir");
}
```

---

### 3.2 ResponseFormatter.java

**职责**：输出格式化一体化

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bash 响应格式化器
 *
 * <p>一体化输出处理：ANSI过滤、截断、错误/成功信息构建。</p>
 */
@Component
@Slf4j
public class ResponseFormatter {

    /**
     * 格式化成功响应
     */
    public String formatSuccess(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "✅ 命令执行成功\n\n(无输出)";
        }

        String cleaned = stripAnsi(rawOutput);
        String truncated = truncateIfNeeded(cleaned);

        return "✅ 命令执行成功\n\n" + truncated;
    }

    /**
     * 格式化超时响应
     */
    public String formatTimeout(String rawOutput, int timeoutSeconds) {
        String cleaned = rawOutput != null ? stripAnsi(rawOutput) : "(无输出)";
        String truncated = truncateIfNeeded(cleaned);

        return String.format("""
            ⏰ 命令执行超时

            超时时间: %d 秒
            已输出内容（可能被截断）:

            %s
            """, timeoutSeconds, truncated);
    }

    /**
     * 格式化执行失败响应
     */
    public String formatError(String rawOutput, int exitCode) {
        String cleaned = rawOutput != null ? stripAnsi(rawOutput) : "(无输出)";
        String truncated = truncateIfNeeded(cleaned);

        return String.format("""
            ❌ 命令执行失败

            退出码: %d

            输出:
            %s
            """, exitCode, truncated);
    }

    /**
     * 去除 ANSI 转义序列
     */
    private String stripAnsi(String input) {
        if (input == null) return "";
        return input
            .replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "")
            .replaceAll("\u001B\\][^\u0007]+\u0007", "")
            .replaceAll("\u001B", "");
    }

    /**
     * 智能截断：保留头部和尾部
     */
    private String truncateIfNeeded(String output) {
        if (output.length() <= BashConstants.MAX_OUTPUT_CHARS) {
            return output;
        }

        int keepStart = BashConstants.KEEP_START;
        int keepEnd = BashConstants.KEEP_END;

        String head = output.substring(0, Math.min(keepStart, output.length()));
        String tail = output.substring(Math.max(output.length() - keepEnd, 0));

        return head + "\n\n... [中间内容已截断] ...\n\n" + tail;
    }
}
```

---

### 3.3 BashExecutor.java

**职责**：进程执行核心

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.*;

/**
 * Bash 命令执行器
 */
@Component
@Slf4j
public class BashExecutor {

    private final ResponseFormatter formatter;
    private final ProcessTreeKiller processKiller;

    public BashExecutor(ResponseFormatter formatter) {
        this.formatter = formatter;
        this.processKiller = new ProcessTreeKiller();
    }

    /**
     * 执行命令
     */
    public String execute(String command, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        configureProcessBuilder(builder, command);
        builder.directory(new File(BashConstants.WORKSPACE));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        log.debug("[BashExecutor] 进程已启动: PID={}", process.pid());

        StringBuilder output = new StringBuilder();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String encoding = System.getProperty("sun.jnu.encoding", "GBK");
        Charset charset = Charset.forName(encoding);

        Future<?> readerFuture = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (Exception e) {
                log.warn("[BashExecutor] 读取输出失败", e);
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        String rawOutput = output.toString();

        if (!finished) {
            processKiller.kill(process);
            readerFuture.cancel(true);
            log.warn("[BashExecutor] 命令超时: {} 秒, command={}", timeoutSeconds, command);
            return formatter.formatTimeout(rawOutput, timeoutSeconds);
        }

        try {
            readerFuture.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // 忽略
        }

        int exitCode = process.exitValue();

        if (exitCode == 0) {
            return formatter.formatSuccess(rawOutput);
        } else {
            return formatter.formatError(rawOutput, exitCode);
        }
    }

    private void configureProcessBuilder(ProcessBuilder builder, String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            builder.command("cmd", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
    }
}
```

### 3.3.1 ProcessTreeKiller.java

```java
package top.javarem.omni.tool.bash;

/**
 * 进程树终结器
 */
class ProcessTreeKiller {

    public void kill(Process process) {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("windows")) {
            killWindows(process);
        } else {
            killUnix(process);
        }
    }

    private void killWindows(Process process) {
        try {
            new ProcessBuilder("taskkill", "/F", "/T", "/PID",
                String.valueOf(process.pid())).start();
            process.destroyForcibly();
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

    private void killUnix(Process process) {
        process.descendants().forEach(p -> {
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        });
        process.destroyForcibly();
    }
}
```

---

### 3.4 BashToolConfig.java

**职责**：入口编排

```java
package top.javarem.omni.tool.bash;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.AgentTool;

/**
 * Bash 命令执行工具
 */
@Component
@Slf4j
public class BashToolConfig implements AgentTool {

    @Resource
    private BashExecutor executor;

    @Tool(name = "bash", description = """
        执行 Shell 命令（编译构建、运行测试、查看进程、环境探测）。
        约束：
        - 交互式命令（vim/less）会被截断
        - 超时时间最大 300 秒
    """)
    public String bash(
            @ToolParam(description = "完整 Shell 命令") String command,
            @ToolParam(description = "超时秒数（默认60，最大300）", required = false) Integer timeout) {

        log.info("[BashToolConfig] 执行命令: {}", command);

        // 1. 参数校验
        if (command == null || command.trim().isEmpty()) {
            return "❌ 命令不能为空";
        }

        // 2. 超时处理
        int timeoutSeconds = normalizeTimeout(timeout);

        // 3. 执行命令
        try {
            return executor.execute(command, timeoutSeconds);
        } catch (Exception e) {
            log.error("[BashToolConfig] 执行异常: {}", e.getMessage(), e);
            return "❌ 命令执行异常: " + e.getMessage();
        }
    }

    private int normalizeTimeout(Integer timeout) {
        if (timeout == null || timeout < 1) {
            return BashConstants.DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(timeout, BashConstants.MAX_TIMEOUT_SECONDS);
    }
}
```

---

## 4. 文件变更对照

| 原文件 | 目标文件 | 变化 |
|--------|----------|------|
| `BashConstants.java` | `BashConstants.java` | 精简，只保留超时/输出常量 |
| `BashExecutor.java` | `BashExecutor.java` | 简化，依赖 ResponseFormatter |
| `ProcessTreeKiller.java` | `ProcessTreeKiller.java` | 保留 |
| `ResponseFormatter.java` | `ResponseFormatter.java` | 新增，合并 AnsiStripper + OutputProcessor + ResponseBuilder |
| `BashToolConfig.java` | `BashToolConfig.java` | 简化，移除安全检查 |
| `DangerousPatternValidator.java` | **后续剥离** | 后续作为通用安全模块 |
| `SuicideCommandDetector.java` | **后续剥离** | 后续作为通用安全模块 |
| `DangerousCheckResult.java` | **后续剥离** | 后续作为通用安全模块 |
| `ApprovalCheckResult.java` | **后续剥离** | 后续作为通用安全模块 |
| `CommandApprover.java` | **后续剥离** | 后续作为通用安全模块 |
| `AnsiStripper.java` | 删除 | 已合并到 ResponseFormatter |
| `OutputProcessor.java` | 删除 | 已合并到 ResponseFormatter |
| `ResponseBuilder.java` | 删除 | 已合并到 ResponseFormatter |

---

## 5. 依赖关系

```
BashToolConfig
    └── BashExecutor
        ├── ResponseFormatter
        │   └── BashConstants
        └── ProcessTreeKiller
            └── BashConstants
```

---

## 6. 安全模块后续设计（待抽离）

```
tool/security/
├── SecurityModule.java        # 通用安全检查器
│   ├── DangerousPatternValidator  # 黑名单检测
│   ├── PathValidator           # 路径校验
│   └── ApprovalService        # 审批流程
```

---

## 7. 实施顺序

| Phase | 内容 | 文件 |
|-------|------|------|
| 1 | 创建 `BashConstants` | `BashConstants.java` |
| 2 | 创建 `ResponseFormatter` | `ResponseFormatter.java` |
| 3 | 创建/改造 `BashExecutor` | `BashExecutor.java` |
| 4 | 改造 `BashToolConfig` | `BashToolConfig.java` |
| 5 | 编写单元测试 | `*Test.java` |
| 6 | 删除废弃文件 | 确认编译通过后删除 |
