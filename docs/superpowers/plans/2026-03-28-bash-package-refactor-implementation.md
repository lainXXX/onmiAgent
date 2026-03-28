# Bash 包重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 bash 包从 12 个文件简化为 4 个核心文件，安全检查后续剥离

**Architecture:** 执行器、格式化器、常量分离，职责清晰

**Tech Stack:** Java 21, Spring AI, JUnit 5

---

## 文件结构

```
src/main/java/top/javarem/omni/tool/bash/
├── BashConstants.java       # 常量：超时配置、输出限制
├── ResponseFormatter.java   # 格式化：ANSI过滤 + 截断 + 信息构建
├── BashExecutor.java       # 核心：ProcessBuilder + 超时
├── ProcessTreeKiller.java # 进程终止
└── BashToolConfig.java    # 入口：@Tool + 编排
```

**从 12 个文件 → 5 个文件**

---

## Task 1: 创建 BashConstants

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/BashConstants.java`

- [ ] **Step 1: 创建 BashConstants**

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

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashConstants.java
git commit -m "refactor(bash): create BashConstants"
```

---

## Task 2: 创建 ResponseFormatter

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/ResponseFormatter.java`

- [ ] **Step 1: 创建 ResponseFormatter**

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

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/ResponseFormatter.java
git commit -m "refactor(bash): create ResponseFormatter"
```

---

## Task 3: 创建 ProcessTreeKiller

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/ProcessTreeKiller.java`

- [ ] **Step 1: 创建 ProcessTreeKiller**

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

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/ProcessTreeKiller.java
git commit -m "refactor(bash): create ProcessTreeKiller"
```

---

## Task 4: 创建 BashExecutor

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/BashExecutor.java`

- [ ] **Step 1: 创建 BashExecutor**

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

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashExecutor.java
git commit -m "refactor(bash): create BashExecutor"
```

---

## Task 5: 改造 BashToolConfig

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java`

- [ ] **Step 1: 改造 BashToolConfig**

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

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java
git commit -m "refactor(bash): refactor BashToolConfig"
```

---

## Task 6: 编写单元测试

**Files:**
- Create: `src/test/java/top/javarem/omni/tool/bash/ResponseFormatterTest.java`

- [ ] **Step 1: ResponseFormatterTest**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResponseFormatter 测试
 */
@DisplayName("ResponseFormatter 测试")
class ResponseFormatterTest {

    private final ResponseFormatter formatter = new ResponseFormatter();

    @Test
    @DisplayName("成功结果格式化")
    void testFormatSuccess() {
        String result = formatter.formatSuccess("hello world");
        assertTrue(result.contains("✅"));
        assertTrue(result.contains("hello world"));
    }

    @Test
    @DisplayName("空输出格式化")
    void testFormatEmptyOutput() {
        String result = formatter.formatSuccess("");
        assertTrue(result.contains("✅"));
        assertTrue(result.contains("(无输出)"));
    }

    @Test
    @DisplayName("错误结果格式化")
    void testFormatError() {
        String result = formatter.formatError("error message", 1);
        assertTrue(result.contains("❌"));
        assertTrue(result.contains("退出码: 1"));
    }

    @Test
    @DisplayName("ANSI 转义序列过滤")
    void testAnsiStripping() {
        String raw = "\u001B[32mhello\u001B[0m";
        String result = formatter.formatSuccess(raw);
        assertFalse(result.contains("\u001B"));
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -DskipTests -q`

- [ ] **Step 3: 运行测试**

Run: `./mvnw test -Dtest="**/tool/bash/*Test" -q`

- [ ] **Step 4: 提交**

```bash
git add src/test/java/top/javarem/omni/tool/bash/
git commit -m "test(bash): add unit tests"
```

---

## Task 7: 删除废弃文件

**Files:**
- Delete: `src/main/java/top/javarem/omni/tool/bash/DangerousPatternValidator.java`
- Delete: `src/main/java/top/javarem/omni/tool/bash/SuicideCommandDetector.java`
- Delete: `src/main/java/top/javarem/omni/tool/bash/DangerousCheckResult.java`
- Delete: `src/main/java/top/javarem/omni/tool/bash/ApprovalCheckResult.java`
- Delete: `src/main/java/top/javarem/omni/tool/bash/CommandApprover.java`
- Delete: `src/main/java/top/javarem/omni/tool/bash/AnsiStripper.java`
- Delete: `src/main/java/top/javarem/omni/tool/bash/OutputProcessor.java`
- Delete: `src/main/java/top/javarem/omni/tool/bash/ResponseBuilder.java`

- [ ] **Step 1: 确认编译测试通过后再删除**

- [ ] **Step 2: 删除废弃文件**

```bash
git rm src/main/java/top/javarem/omni/tool/bash/DangerousPatternValidator.java
git rm src/main/java/top/javarem/omni/tool/bash/SuicideCommandDetector.java
git rm src/main/java/top/javarem/omni/tool/bash/DangerousCheckResult.java
git rm src/main/java/top/javarem/omni/tool/bash/ApprovalCheckResult.java
git rm src/main/java/top/javarem/omni/tool/bash/CommandApprover.java
git rm src/main/java/top/javarem/omni/tool/bash/AnsiStripper.java
git rm src/main/java/top/javarem/omni/tool/bash/OutputProcessor.java
git rm src/main/java/top/javarem/omni/tool/bash/ResponseBuilder.java
```

- [ ] **Step 3: 提交**

```bash
git commit -m "refactor(bash): remove obsolete files"
```

---

## 总结

| Task | 内容 | 状态 |
|------|------|------|
| 1 | BashConstants | ⬜ |
| 2 | ResponseFormatter | ⬜ |
| 3 | ProcessTreeKiller | ⬜ |
| 4 | BashExecutor | ⬜ |
| 5 | BashToolConfig | ⬜ |
| 6 | 单元测试 | ⬜ |
| 7 | 删除废弃文件 | ⬜ |

**预计工期：约 1 小时**
