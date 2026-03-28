# Bash 命令适配实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 BashToolConfig 增加错误检测和分类能力，当命令因操作系统不兼容而失败时，返回清晰的错误报告和建议

**Architecture:** 通过新增错误分类组件（OsDetector、CommandStyleDetector、BashErrorClassifier、ErrorReportBuilder）实现跨平台错误检测，让 LLM 根据错误信息自我修正命令

**Tech Stack:** Java 21, Spring AI, JUnit 5

---

## 文件结构

```
src/main/java/top/javarem/omni/tool/bash/
├── BashToolConfig.java              # 改造：整合错误检测
├── BashExecutor.java                # 改造：返回 ExecutionResult
├── BashConstants.java               # 已有：不变
├── ResponseBuilder.java             # 改造：增加错误报告方法
├── OsDetector.java                  # 新增：操作系统检测
├── CommandStyleDetector.java        # 新增：命令风格检测
├── BashErrorClassifier.java         # 新增：错误分类器
├── ErrorReportBuilder.java          # 新增：错误报告构建器
└── WorkingDirectoryManager.java     # 新增：工作目录管理

src/test/java/top/javarem/omni/tool/bash/
├── OsDetectorTest.java              # 新增
├── CommandStyleDetectorTest.java    # 新增
├── BashErrorClassifierTest.java     # 新增
└── ErrorReportBuilderTest.java      # 新增
```

---

## Task 0: System Prompt 更新 - 环境感知指导

**Files:**
- Modify: `src/main/resources/agent_system_prompt.md`

- [ ] **Step 1: 在 `# 使用你的工具` 章节末尾添加 Bash 工具使用规范**

在 `agent_system_prompt.md` 的 `# 使用你的工具` 章节末尾添加：

```markdown
## Bash 工具使用规范

### 环境感知
- 执行命令前，确认当前 shell 类型（WSL/Git Bash/原生 Linux）
- 如果用户提供了 Windows 路径，自动转换为 Unix 路径格式
- 路径格式：`C:\Users\xxx` → `/c/Users/xxx` 或 `/mnt/c/Users/xxx`

### 命令转换规则
| Windows CMD | Bash | Windows CMD | Bash |
|-------------|------|-------------|------|
| `dir` | `ls -la` | `del` | `rm` |
| `type` | `cat` | `copy` | `cp` |
| `move` | `mv` | `mkdir` | `mkdir -p` |
| `where` | `which` | `set VAR=val` | `export VAR=val` |

### 常见错误处理
- **错误**：`command not found: dir`
  **原因**：在 Bash 环境使用了 Windows 命令
  **修正**：将 `dir` 替换为 `ls`

- **错误**：`No such file or directory: C:\Users`
  **原因**：在 Bash 环境使用了 Windows 路径
  **修正**：将 `C:\Users` 转换为 `/c/Users` 或 `/mnt/c/Users`

### 执行流程
1. 解析用户输入的命令或路径
2. 如有 Windows 格式，转换为 Unix 格式
3. 如不确定，先用 `pwd` / `ls` 探测环境
4. 执行命令
5. 如失败，观察错误输出，判断是否路径或命令问题
6. 如需修正，重新生成正确命令重试
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/agent_system_prompt.md
git commit -m "feat: add bash tool environment awareness to system prompt"
```

---

## Task 1: OsDetector - 操作系统检测

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/OsDetector.java`

- [ ] **Step 1: 创建 OsDetector 类**

```java
package top.javarem.omni.tool.bash;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 操作系统检测器
 *
 * <p>检测 AI 自身运行的操作系统，用于判断命令兼容性。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class OsDetector {

    @Getter
    private final OsType currentOs;

    public OsDetector() {
        this.currentOs = detectOs();
        log.info("[OsDetector] 检测到当前系统: {}", currentOs);
    }

    private OsType detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("windows")) {
            return OsType.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return OsType.MACOS;
        } else if (os.contains("linux")) {
            return OsType.LINUX;
        }

        log.warn("[OsDetector] 无法识别操作系统，默认为 LINUX");
        return OsType.LINUX;
    }

    public String getOsDisplayName() {
        return switch (currentOs) {
            case WINDOWS -> "Windows";
            case LINUX -> "Linux";
            case MACOS -> "macOS";
        };
    }

    public enum OsType {
        WINDOWS,
        LINUX,
        MACOS
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/OsDetector.java
git commit -m "feat(bash): add OsDetector for OS detection"
```

---

## Task 2: CommandStyleDetector - 命令风格检测

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/CommandStyleDetector.java`

- [ ] **Step 1: 创建 CommandStyleDetector 类**

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 命令风格检测器
 *
 * <p>检测用户输入命令的风格，用于判断是否与当前 OS 匹配。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class CommandStyleDetector {

    // Unix 特有命令
    private static final Pattern UNIX_COMMAND = Pattern.compile(
        "\\b(ls|ll|la|mkdir -p|which|cat|grep|egrep|fgrep|touch|chmod|export|whoami|pwd|uname|top|ps aux|kill -9|man|head|tail|cut|awk|sed|sort|uniq|wc|ln -s)\\b"
    );

    // Windows CMD 特有命令
    private static final Pattern WINDOWS_COMMAND = Pattern.compile(
        "\\b(dir|type |del |rmdir|mkdir|attrib|set \\w+|echo %|if exist|for /f)\\b"
    );

    // PowerShell 特有命令
    private static final Pattern POWERSHELL_COMMAND = Pattern.compile(
        "\\b(Get-ChildItem|Write-Host|Get-Process|\\$\\w+:|Where-Object|Select-Object|ForEach-Object|Start-Process)\\b"
    );

    // Unix 特有路径模式
    private static final Pattern UNIX_PATH = Pattern.compile(
        "^/[a-z]/|^/home/|^/usr/|^/etc/|^/var/|^/tmp/|~|/dev/|/proc/"
    );

    // Windows 特有路径模式
    private static final Pattern WINDOWS_PATH = Pattern.compile(
        "^[A-Za-z]:\\\\|\\\\\\\\[a-z]|\\\\[^/\\\\]+\\\\"
    );

    public enum CommandStyle {
        UNIX,
        WINDOWS,
        POWERSHELL,
        UNKNOWN
    }

    /**
     * 检测命令风格
     */
    public CommandStyle detect(String command) {
        if (command == null || command.isBlank()) {
            return CommandStyle.UNKNOWN;
        }

        // 检测 PowerShell
        if (POWERSHELL_COMMAND.matcher(command.toLowerCase()).find()) {
            log.debug("[CommandStyleDetector] 检测为 POWERSHELL: {}", command);
            return CommandStyle.POWERSHELL;
        }

        // 检测 Unix
        if (UNIX_COMMAND.matcher(command.toLowerCase()).find()
            || UNIX_PATH.matcher(command).find()) {
            log.debug("[CommandStyleDetector] 检测为 UNIX: {}", command);
            return CommandStyle.UNIX;
        }

        // 检测 Windows
        if (WINDOWS_COMMAND.matcher(command.toLowerCase()).find()
            || WINDOWS_PATH.matcher(command).find()) {
            log.debug("[CommandStyleDetector] 检测为 WINDOWS: {}", command);
            return CommandStyle.WINDOWS;
        }

        return CommandStyle.UNKNOWN;
    }

    /**
     * 检测是否有 Unix 命令风格
     */
    public boolean isUnixStyle(String command) {
        return detect(command) == CommandStyle.UNIX;
    }

    /**
     * 检测是否有 Windows 命令风格
     */
    public boolean isWindowsStyle(String command) {
        return detect(command) == CommandStyle.WINDOWS;
    }

    /**
     * 获取检测说明
     */
    public String getStyleDescription(String command) {
        CommandStyle style = detect(command);
        return switch (style) {
            case UNIX -> "Unix/Linux 命令风格";
            case WINDOWS -> "Windows CMD 命令风格";
            case POWERSHELL -> "PowerShell 命令风格";
            default -> "未知命令风格";
        };
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/CommandStyleDetector.java
git commit -m "feat(bash): add CommandStyleDetector for command style detection"
```

---

## Task 3: BashErrorClassifier - 错误分类器

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/BashErrorClassifier.java`

- [ ] **Step 1: 创建 BashErrorClassifier 类**

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bash 错误分类器
 *
 * <p>对命令执行结果进行分类，判断失败原因并提供纠正建议。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class BashErrorClassifier {

    private final OsDetector osDetector;
    private final CommandStyleDetector styleDetector;

    public BashErrorClassifier(OsDetector osDetector, CommandStyleDetector styleDetector) {
        this.osDetector = osDetector;
        this.styleDetector = styleDetector;
    }

    /**
     * 错误类型枚举
     */
    public enum ErrorType {
        COMMAND_NOT_FOUND,
        PATH_NOT_FOUND,
        SYNTAX_ERROR,
        PERMISSION_DENIED,
        ALREADY_EXISTS,
        UNKNOWN
    }

    /**
     * 错误分类结果
     */
    public record ErrorClassification(
        boolean isError,
        ErrorType type,
        String command,
        String reason,
        String suggestion
    ) {}

    /**
     * 分类错误
     */
    public ErrorClassification classify(String command, int exitCode, String output) {
        CommandStyleDetector.CommandStyle style = styleDetector.detect(command);
        OsDetector.OsType currentOs = osDetector.getCurrentOs();

        // 检查命令不存在错误
        if (isCommandNotFound(output)) {
            String reason = buildCommandNotFoundReason(command, style, currentOs);
            String suggestion = buildSuggestion(command, style, currentOs);

            return new ErrorClassification(
                true,
                ErrorType.COMMAND_NOT_FOUND,
                command,
                reason,
                suggestion
            );
        }

        // 检查路径不存在
        if (isPathNotFound(output)) {
            return new ErrorClassification(
                true,
                ErrorType.PATH_NOT_FOUND,
                command,
                buildPathNotFoundReason(command, style, currentOs),
                "请检查路径是否正确，注意当前系统使用 " + osDetector.getOsDisplayName() + " 路径格式"
            );
        }

        // 检查语法错误
        if (isSyntaxError(output)) {
            return new ErrorClassification(
                true,
                ErrorType.SYNTAX_ERROR,
                command,
                "命令语法与 " + osDetector.getOsDisplayName() + " 不兼容",
                "请使用符合当前系统的命令语法"
            );
        }

        // 检查权限不足
        if (isPermissionDenied(output)) {
            return new ErrorClassification(
                true,
                ErrorType.PERMISSION_DENIED,
                command,
                "权限不足，无法执行该命令",
                "可能需要管理员权限或更换操作目标"
            );
        }

        // 检查目录已存在
        if (isAlreadyExists(output)) {
            return new ErrorClassification(
                true,
                ErrorType.ALREADY_EXISTS,
                command,
                "目标已存在",
                "检查目标是否存在，或使用覆盖参数"
            );
        }

        // 非零退出码但未分类
        if (exitCode != 0) {
            return new ErrorClassification(
                true,
                ErrorType.UNKNOWN,
                command,
                "命令执行失败 (退出码: " + exitCode + ")",
                "请检查命令是否正确"
            );
        }

        return new ErrorClassification(false, null, command, null, null);
    }

    private boolean isCommandNotFound(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("不是内部或外部命令")
            || lower.contains("is not recognized")
            || lower.contains("not found")
            || lower.contains("command not found");
    }

    private boolean isPathNotFound(String output) {
        if (output == null) return false;
        return output.contains("系统找不到指定的路径")
            || output.contains("No such file or directory")
            || output.contains("cannot find");
    }

    private boolean isSyntaxError(String output) {
        if (output == null) return false;
        return output.contains("语法不正确")
            || output.contains("syntax error")
            || output.contains("unexpected token");
    }

    private boolean isPermissionDenied(String output) {
        if (output == null) return false;
        return output.contains("拒绝访问")
            || output.contains("Permission denied")
            || output.contains("access denied");
    }

    private boolean isAlreadyExists(String output) {
        if (output == null) return false;
        return output.contains("已经存在")
            || output.contains("already exists");
    }

    private String buildCommandNotFoundReason(String command,
            CommandStyleDetector.CommandStyle style,
            OsDetector.OsType currentOs) {
        String cmd = extractCommand(command);
        String targetOs = osDetector.getOsDisplayName();

        return switch (style) {
            case UNIX -> String.format(
                "检测到 Unix 命令 '%s' 在 %s 环境执行失败。%s 使用 'dir' 替代 'ls'，'mkdir -p' 在 Windows 中只需 'mkdir'",
                cmd, targetOs, targetOs
            );
            case WINDOWS -> String.format(
                "检测到 Windows 命令 '%s' 在 %s 环境执行失败。Unix/Linux 使用 'ls' 替代 'dir'",
                cmd, targetOs
            );
            case POWERSHELL -> String.format(
                "检测到 PowerShell 命令在 %s CMD 环境执行失败。请使用 CMD 或 Bash 命令",
                targetOs
            );
            default -> String.format("命令 '%s' 在 %s 环境无法识别", cmd, targetOs);
        };
    }

    private String buildSuggestion(String command,
            CommandStyleDetector.CommandStyle style,
            OsDetector.OsType currentOs) {
        String cmd = extractCommand(command);

        if (currentOs == OsDetector.OsType.WINDOWS && style == CommandStyleDetector.CommandStyle.UNIX) {
            String replacement = getUnixToWindowsReplacement(cmd);
            if (replacement != null) {
                String translatedCmd = command.replaceFirst("\\b" + cmd + "\\b", replacement);
                return String.format(
                    "💡 建议: 使用 Windows CMD 命令\n   原命令: %s\n   建议改为: %s\n   常见对照: ls→dir, cat→type, grep→findstr, mkdir -p→mkdir",
                    command, translatedCmd
                );
            }
        }

        if ((currentOs == OsDetector.OsType.LINUX || currentOs == OsDetector.OsType.MACOS)
            && (style == CommandStyleDetector.CommandStyle.WINDOWS || style == CommandStyleDetector.CommandStyle.POWERSHELL)) {
            String replacement = getWindowsToUnixReplacement(cmd);
            if (replacement != null) {
                String translatedCmd = command.replaceFirst("(?i)\\b" + cmd + "\\b", replacement);
                return String.format(
                    "💡 建议: 使用 Unix/Bash 命令\n   原命令: %s\n   建议改为: %s\n   常见对照: dir→ls, type→cat, copy→cp",
                    command, translatedCmd
                );
            }
        }

        return "💡 请确认命令与当前系统 " + osDetector.getOsDisplayName() + " 兼容";
    }

    private String buildPathNotFoundReason(String command,
            CommandStyleDetector.CommandStyle style,
            OsDetector.OsType currentOs) {
        String targetOs = osDetector.getOsDisplayName();

        if (command.contains("/") && currentOs == OsDetector.OsType.WINDOWS) {
            return String.format(
                "检测到 Unix 路径格式在 %s 环境无法识别。Unix 用 /c/Users，Windows 用 C:\\Users",
                targetOs
            );
        }

        if ((command.contains("\\") || command.contains("C:"))
            && (currentOs == OsDetector.OsType.LINUX || currentOs == OsDetector.OsType.MACOS)) {
            return String.format(
                "检测到 Windows 路径格式在 %s 环境无法识别。Windows 用 C:\\Users，Unix 用 /c/Users",
                targetOs
            );
        }

        return "路径不存在或格式不正确";
    }

    private String extractCommand(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        int spaceIdx = trimmed.indexOf(' ');
        return spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
    }

    private String getUnixToWindowsReplacement(String unixCmd) {
        return switch (unixCmd.toLowerCase()) {
            case "ls", "ll", "la" -> "dir";
            case "cat" -> "type";
            case "grep", "egrep", "fgrep" -> "findstr";
            case "cp" -> "copy";
            case "mv" -> "move";
            case "rm", "rm -r", "rm -rf" -> "del";
            case "mkdir -p" -> "mkdir";
            case "touch" -> "type NUL >";
            case "pwd" -> "cd";
            case "clear" -> "cls";
            case "which" -> "where";
            case "rmdir" -> "rmdir";
            case "man" -> "help";
            case "head" -> "more";
            case "find" -> "dir /s";
            default -> null;
        };
    }

    private String getWindowsToUnixReplacement(String windowsCmd) {
        return switch (windowsCmd.toLowerCase()) {
            case "dir" -> "ls";
            case "type" -> "cat";
            case "copy" -> "cp";
            case "move" -> "mv";
            case "del", "erase" -> "rm";
            case "mkdir" -> "mkdir -p";
            case "rmdir" -> "rmdir";
            case "where" -> "which";
            case "cls" -> "clear";
            case "more" -> "cat";
            case "findstr" -> "grep";
            case "fc" -> "diff";
            case "attrib" -> "chmod";
            default -> null;
        };
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashErrorClassifier.java
git commit -m "feat(bash): add BashErrorClassifier for error classification"
```

---

## Task 4: ErrorReportBuilder - 错误报告构建器

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/ErrorReportBuilder.java`

- [ ] **Step 1: 创建 ErrorReportBuilder 类**

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 错误报告构建器
 *
 * <p>将错误分类结果格式化为人类可读的错误报告。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class ErrorReportBuilder {

    private final OsDetector osDetector;

    public ErrorReportBuilder(OsDetector osDetector) {
        this.osDetector = osDetector;
    }

    /**
     * 构建错误报告
     */
    public String buildReport(BashErrorClassifier.ErrorClassification error) {
        if (!error.isError()) {
            return null;
        }

        StringBuilder report = new StringBuilder();

        report.append("❌ 命令执行失败\n");
        report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // 错误类型
        report.append("📋 错误类型: ").append(getErrorTypeName(error.type())).append("\n\n");

        // 原因
        report.append("📝 失败原因:\n");
        report.append("   ").append(error.reason()).append("\n\n");

        // 建议
        if (error.suggestion() != null && !error.suggestion().isBlank()) {
            report.append(error.suggestion()).append("\n");
        }

        // 当前环境信息
        report.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        report.append("💻 当前环境: ").append(osDetector.getOsDisplayName()).append("\n");
        report.append("📌 请根据上述信息修正命令后重试\n");

        log.info("[ErrorReportBuilder] 生成错误报告: type={}, cmd={}", error.type(), error.command());

        return report.toString();
    }

    private String getErrorTypeName(BashErrorClassifier.ErrorType type) {
        return switch (type) {
            case COMMAND_NOT_FOUND -> "命令不存在 (COMMAND_NOT_FOUND)";
            case PATH_NOT_FOUND -> "路径不存在 (PATH_NOT_FOUND)";
            case SYNTAX_ERROR -> "语法错误 (SYNTAX_ERROR)";
            case PERMISSION_DENIED -> "权限不足 (PERMISSION_DENIED)";
            case ALREADY_EXISTS -> "目标已存在 (ALREADY_EXISTS)";
            case UNKNOWN -> "未知错误 (UNKNOWN)";
        };
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/ErrorReportBuilder.java
git commit -m "feat(bash): add ErrorReportBuilder for error reporting"
```

---

## Task 5: BashExecutor - 增加 ExecutionResult

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/bash/BashExecutor.java`

- [ ] **Step 1: 修改 BashExecutor，增加 ExecutionResult 记录类型**

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.util.concurrent.*;

import top.javarem.omni.tool.bash.OsDetector;

/**
 * Bash 命令执行器
 */
@Slf4j
public class BashExecutor {

    private final ProcessTreeKiller processTreeKiller;

    public BashExecutor() {
        this.processTreeKiller = new ProcessTreeKiller();
    }

    /**
     * 执行结果记录
     */
    public record ExecutionResult(
        int exitCode,
        String output,
        String errorOutput,
        long executionTimeMs
    ) {
        public boolean isSuccess() {
            return exitCode == 0;
        }

        public String formattedOutput() {
            if (output == null || output.isBlank()) {
                return "(无输出)";
            }
            return output;
        }
    }

    /**
     * 执行命令，返回执行结果
     */
    public ExecutionResult execute(String command, int timeoutSeconds) throws Exception {
        long startTime = System.currentTimeMillis();

        ProcessBuilder processBuilder = new ProcessBuilder();

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            processBuilder.command("cmd", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }

        processBuilder.directory(new File(BashConstants.WORKSPACE));
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String encoding = System.getProperty("sun.jnu.encoding", "GBK");
        Charset charset = Charset.forName(encoding);

        Future<?> future = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                log.warn("读取命令输出失败", e);
            }
        });

        boolean finished = false;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode;
        if (finished) {
            try {
                future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // 忽略
            }
            exitCode = process.exitValue();
        } else {
            processTreeKiller.kill(process);
            future.cancel(true);
            exitCode = -1;
            errorOutput.append("命令执行超时");
        }

        executor.shutdownNow();

        long executionTimeMs = System.currentTimeMillis() - startTime;

        String processedOutput = OutputProcessor.process(output.toString(), exitCode, exitCode == -1);

        return new ExecutionResult(exitCode, processedOutput, errorOutput.toString(), executionTimeMs);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashExecutor.java
git commit -m "feat(bash): add ExecutionResult to BashExecutor"
```

---

## Task 6: ResponseBuilder - 增加错误报告方法

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/bash/ResponseBuilder.java`

- [ ] **Step 1: 修改 ResponseBuilder，增加 buildErrorReport 方法**

```java
package top.javarem.omni.tool.bash;

/**
 * 响应构建器
 */
public final class ResponseBuilder {

    private ResponseBuilder() {}

    public static String buildError(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }

    public static String buildDenied(String command, String reason) {
        return "⛔ 命令执行被拒绝\n\n" +
                "执行的命令: " + command + "\n\n" +
                "原因: " + reason + "\n\n" +
                "💡 为了系统安全，某些高危命令需要人工审批才能执行。";
    }

    public static String buildSuicideBlocked(String command) {
        return "⛔ 命令执行被拒绝\n\n" +
                "执行的命令: " + command + "\n\n" +
                "原因: 禁止终止当前进程自身 (PID: " + BashConstants.CURRENT_PID + ")\n\n" +
                "💡 为了系统安全，不允许终止当前 Agent 进程。";
    }

    /**
     * 构建跨平台错误报告
     */
    public static String buildCrossPlatformError(String command, String reason, String suggestion) {
        return "❌ 命令执行失败\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                "📝 失败原因:\n   " + reason + "\n\n" +
                suggestion + "\n\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "📌 请根据上述信息修正命令后重试\n";
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/ResponseBuilder.java
git commit -m "feat(bash): add buildCrossPlatformError to ResponseBuilder"
```

---

## Task 7: BashToolConfig - 整合错误检测

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java`

- [ ] **Step 1: 修改 BashToolConfig，整合错误检测组件**

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.AgentTool;
import top.javarem.omni.tool.PathApprovalService;
import top.javarem.omni.tool.file.GrepToolConfig;

/**
 * Bash 终端命令执行工具
 *
 * <p>执行系统命令，提供跨平台错误检测和清晰的错误报告。</p>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li><b>纠错为主</b> - 检测错误，返回清晰反馈</li>
 *   <li><b>不自动翻译</b> - 让 LLM 理解错误后自行修复</li>
 *   <li><b>详细建议</b> - 提供错误原因和纠正方向</li>
 * </ul>
 */
@Component
@Slf4j
public class BashToolConfig implements AgentTool {

    private final DangerousPatternValidator validator;
    private final SuicideCommandDetector suicideDetector;
    private final CommandApprover approver;
    private final BashExecutor executor;
    private final OsDetector osDetector;
    private final CommandStyleDetector styleDetector;
    private final BashErrorClassifier errorClassifier;
    private final ErrorReportBuilder errorReportBuilder;

    public BashToolConfig(PathApprovalService approvalService) {
        this.validator = new DangerousPatternValidator();
        this.suicideDetector = new SuicideCommandDetector();
        this.approver = new CommandApprover(approvalService);
        this.executor = new BashExecutor();
        this.osDetector = new OsDetector();
        this.styleDetector = new CommandStyleDetector();
        this.errorClassifier = new BashErrorClassifier(osDetector, styleDetector);
        this.errorReportBuilder = new ErrorReportBuilder(osDetector);
        log.info("BashToolConfig 初始化完成，当前系统: {}, PID: {}", osDetector.getOsDisplayName(), BashConstants.CURRENT_PID);
    }

    @Tool(name = "bash", description = """
        适用场景：编译构建(mvn/npm)、运行测试、查看进程/端口、Git版本控制、环境探测。
        禁止场景：1.交互式命令(如vim/python/node REPL)；2.后台持续运行服务(如直接运行npm start)；3.高危删除(rm/del)或系统关键修改。
        约束：必须使用 Windows 语法(如 dir/type)。执行复杂构建务必添加 --batch-mode 以防阻塞
    """)
    public String bash(
            @ToolParam(description = "完整命令。Windows用DOS语法，支持mvn/npm等构建工具") String command,
            @ToolParam(description = "超时秒数。默认60", required = false) Integer timeout) {
        log.info("执行命令: {}", command);

        // 1. 参数校验
        if (command == null || command.trim().isEmpty()) {
            return ResponseBuilder.buildError("命令不能为空", "请提供要执行的命令");
        }

        String normalizedCommand = command.trim();

        // 2. 防自杀检查
        if (suicideDetector.isSuicide(normalizedCommand)) {
            log.warn("检测到自杀命令，已拦截: {}", normalizedCommand);
            return ResponseBuilder.buildSuicideBlocked(normalizedCommand);
        }

        // 3. 危险命令检测
        DangerousCheckResult check = validator.validate(normalizedCommand);
        if (check.isDangerous()) {
            ApprovalCheckResult approval = approver.requestApproval(normalizedCommand, check.reason());
            if (!approval.approved()) {
                return ResponseBuilder.buildDenied(normalizedCommand, approval.reason());
            }
        }

        // 4. 规范化超时时间
        int timeoutSeconds = (timeout == null || timeout < 1)
                ? BashConstants.DEFAULT_TIMEOUT_SECONDS
                : Math.min(timeout, BashConstants.MAX_TIMEOUT_SECONDS);

        // 5. 执行命令
        try {
            BashExecutor.ExecutionResult result = executor.execute(normalizedCommand, timeoutSeconds);

            // 6. 分类错误
            BashErrorClassifier.ErrorClassification error =
                errorClassifier.classify(normalizedCommand, result.exitCode(), result.output());

            // 7. 如果出错，返回错误报告
            if (error.isError()) {
                String report = errorReportBuilder.buildReport(error);
                log.info("[BashToolConfig] 命令失败: {} → {}", normalizedCommand, error.type());
                return report;
            }

            // 8. 返回成功结果
            return result.formattedOutput();

        } catch (GrepToolConfig.AgentToolSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("命令执行失败: command={}", normalizedCommand, e);
            return ResponseBuilder.buildError("命令执行失败: " + e.getMessage(), "请检查命令是否正确");
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java
git commit -m "feat(bash): integrate error detection into BashToolConfig"
```

---

## Task 8: 单元测试 - OsDetectorTest

**Files:**
- Create: `src/test/java/top/javarem/omni/tool/bash/OsDetectorTest.java`

- [ ] **Step 1: 编写 OsDetector 测试**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OsDetector 测试
 */
@DisplayName("OsDetector 测试")
class OsDetectorTest {

    @Test
    @DisplayName("应该能检测到当前操作系统")
    void testDetectCurrentOs() {
        OsDetector detector = new OsDetector();

        assertNotNull(detector.getCurrentOs());
        assertNotNull(detector.getOsDisplayName());

        // 验证是已知类型之一
        assertTrue(
            detector.getCurrentOs() == OsDetector.OsType.WINDOWS
            || detector.getCurrentOs() == OsDetector.OsType.LINUX
            || detector.getCurrentOs() == OsDetector.OsType.MACOS
        );
    }

    @Test
    @DisplayName("getOsDisplayName 应返回正确的系统名称")
    void testGetOsDisplayName() {
        OsDetector detector = new OsDetector();

        String displayName = detector.getOsDisplayName();
        assertNotNull(displayName);
        assertFalse(displayName.isBlank());
    }
}
```

- [ ] **Step 2: 运行测试验证**

Run: `./mvnw test -Dtest=OsDetectorTest -q`
Expected: PASS

- [ ] **Step 3: 提交**

```bash
git add src/test/java/top/javarem/omni/tool/bash/OsDetectorTest.java
git commit -m "test(bash): add OsDetector tests"
```

---

## Task 9: 单元测试 - CommandStyleDetectorTest

**Files:**
- Create: `src/test/java/top/javarem/omni/tool/bash/CommandStyleDetectorTest.java`

- [ ] **Step 1: 编写 CommandStyleDetector 测试**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandStyleDetector 测试
 */
@DisplayName("CommandStyleDetector 测试")
class CommandStyleDetectorTest {

    private final CommandStyleDetector detector = new CommandStyleDetector();

    @ParameterizedTest
    @DisplayName("检测 Unix 命令")
    @ValueSource(strings = {"ls", "ls -la", "mkdir -p", "which python", "cat file.txt", "grep pattern"})
    void testDetectUnixCommands(String command) {
        assertEquals(CommandStyleDetector.CommandStyle.UNIX, detector.detect(command));
    }

    @ParameterizedTest
    @DisplayName("检测 Windows 命令")
    @ValueSource(strings = {"dir", "dir /a", "type file.txt", "copy a b", "mkdir test"})
    void testDetectWindowsCommands(String command) {
        assertEquals(CommandStyleDetector.CommandStyle.WINDOWS, detector.detect(command));
    }

    @ParameterizedTest
    @DisplayName("检测 PowerShell 命令")
    @ValueSource(strings = {"Get-ChildItem", "Write-Host hello", "Get-Process"})
    void testDetectPowerShellCommands(String command) {
        assertEquals(CommandStyleDetector.CommandStyle.POWERSHELL, detector.detect(command));
    }

    @Test
    @DisplayName("检测 Unix 路径")
    void testDetectUnixPaths() {
        assertEquals(CommandStyleDetector.CommandStyle.UNIX, detector.detect("/home/user/file.txt"));
        assertEquals(CommandStyleDetector.CommandStyle.UNIX, detector.detect("/usr/local/bin"));
        assertEquals(CommandStyleDetector.CommandStyle.UNIX, detector.detect("~/Documents"));
    }

    @Test
    @DisplayName("检测 Windows 路径")
    void testDetectWindowsPaths() {
        assertEquals(CommandStyleDetector.CommandStyle.WINDOWS, detector.detect("C:\\Users\\test"));
        assertEquals(CommandStyleDetector.CommandStyle.WINDOWS, detector.detect("D:\\Program Files"));
    }

    @Test
    @DisplayName("空命令应返回 UNKNOWN")
    void testNullOrEmptyCommand() {
        assertEquals(CommandStyleDetector.CommandStyle.UNKNOWN, detector.detect(""));
        assertEquals(CommandStyleDetector.CommandStyle.UNKNOWN, detector.detect(null));
    }

    @Test
    @DisplayName("isUnixStyle 应正确判断")
    void testIsUnixStyle() {
        assertTrue(detector.isUnixStyle("ls -la"));
        assertTrue(detector.isUnixStyle("/home/user"));
        assertFalse(detector.isUnixStyle("dir"));
    }

    @Test
    @DisplayName("isWindowsStyle 应正确判断")
    void testIsWindowsStyle() {
        assertTrue(detector.isWindowsStyle("dir"));
        assertTrue(detector.isWindowsStyle("C:\\Users"));
        assertFalse(detector.isWindowsStyle("ls"));
    }
}
```

- [ ] **Step 2: 运行测试验证**

Run: `./mvnw test -Dtest=CommandStyleDetectorTest -q`
Expected: PASS

- [ ] **Step 3: 提交**

```bash
git add src/test/java/top/javarem/omni/tool/bash/CommandStyleDetectorTest.java
git commit -m "test(bash): add CommandStyleDetector tests"
```

---

## Task 10: 单元测试 - BashErrorClassifierTest

**Files:**
- Create: `src/test/java/top/javarem/omni/tool/bash/BashErrorClassifierTest.java`

- [ ] **Step 1: 编写 BashErrorClassifier 测试**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BashErrorClassifier 测试
 */
@DisplayName("BashErrorClassifier 测试")
class BashErrorClassifierTest {

    private BashErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        OsDetector osDetector = new OsDetector();
        CommandStyleDetector styleDetector = new CommandStyleDetector();
        classifier = new BashErrorClassifier(osDetector, styleDetector);
    }

    @Test
    @DisplayName("成功执行应返回非错误")
    void testSuccessResult() {
        var result = classifier.classify("echo hello", 0, "hello");

        assertFalse(result.isError());
        assertNull(result.type());
    }

    @Test
    @DisplayName("检测命令不存在错误 - Unix 命令")
    void testUnixCommandNotFound() {
        // 模拟 Windows 环境检测 Unix 命令
        var result = classifier.classify("ls -la", 1, "'ls' 不是内部或外部命令");

        assertTrue(result.isError());
        assertEquals(BashErrorClassifier.ErrorType.COMMAND_NOT_FOUND, result.type());
        assertNotNull(result.reason());
        assertNotNull(result.suggestion());
    }

    @Test
    @DisplayName("检测命令不存在错误 - Windows 命令")
    void testWindowsCommandNotFound() {
        // 模拟 Linux 环境检测 Windows 命令
        var result = classifier.classify("dir C:\\Users", 127, "dir: command not found");

        assertTrue(result.isError());
        assertEquals(BashErrorClassifier.ErrorType.COMMAND_NOT_FOUND, result.type());
        assertTrue(result.reason().contains("Windows"));
    }

    @Test
    @DisplayName("检测路径不存在错误")
    void testPathNotFound() {
        var result = classifier.classify("/c/Users/test", 1, "系统找不到指定的路径");

        assertTrue(result.isError());
        assertEquals(BashErrorClassifier.ErrorType.PATH_NOT_FOUND, result.type());
    }

    @Test
    @DisplayName("检测语法错误")
    void testSyntaxError() {
        var result = classifier.classify("ls --invalid-flag", 1, "syntax error");

        assertTrue(result.isError());
        assertEquals(BashErrorClassifier.ErrorType.SYNTAX_ERROR, result.type());
    }

    @Test
    @DisplayName("检测权限不足错误")
    void testPermissionDenied() {
        var result = classifier.classify("rm /etc/passwd", 1, "Permission denied");

        assertTrue(result.isError());
        assertEquals(BashErrorClassifier.ErrorType.PERMISSION_DENIED, result.type());
    }

    @Test
    @DisplayName("检测目录已存在错误")
    void testAlreadyExists() {
        var result = classifier.classify("mkdir test", 1, "子目录或文件 test 已经存在");

        assertTrue(result.isError());
        assertEquals(BashErrorClassifier.ErrorType.ALREADY_EXISTS, result.type());
    }

    @Test
    @DisplayName("建议应包含命令对照")
    void testSuggestionContainsReplacement() {
        var result = classifier.classify("ls -la", 1, "'ls' 不是内部或外部命令");

        assertTrue(result.suggestion().contains("dir"));
    }
}
```

- [ ] **Step 2: 运行测试验证**

Run: `./mvnw test -Dtest=BashErrorClassifierTest -q`
Expected: PASS

- [ ] **Step 3: 提交**

```bash
git add src/test/java/top/javarem/omni/tool/bash/BashErrorClassifierTest.java
git commit -m "test(bash): add BashErrorClassifier tests"
```

---

## Task 11: 集成测试 - 端到端验证

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java` (如需调整)

- [ ] **Step 1: 编译验证**

Run: `./mvnw compile -DskipTests -q`
Expected: 编译成功，无错误

- [ ] **Step 2: 运行所有 bash 相关测试**

Run: `./mvnw test -Dtest="**/tool/bash/*Test" -q`
Expected: 所有测试通过

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "test(bash): add all bash tool tests"
```

---

## Task 12: 更新工具描述

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java`

- [ ] **Step 1: 更新 @Tool 描述，增加跨平台错误检测说明**

```java
@Tool(name = "bash", description = """
    执行系统命令（编译构建、运行测试、查看进程、环境探测）。

    特性：
    - 跨平台错误检测：自动识别命令风格与当前 OS 是否匹配
    - 清晰的错误报告：失败时返回详细原因和纠正建议
    - 常见错误示例：
      * Unix 命令 (ls, cat, grep) 在 Windows → 建议使用 Windows 命令 (dir, type, findstr)
      * Windows 命令 (dir, type) 在 Linux → 建议使用 Unix 命令 (ls, cat, grep)

    禁止场景：
    - 交互式命令(如vim/python/node REPL)
    - 后台持续运行服务(如直接运行npm start)
    - 高危删除(rm/del)或系统关键修改

    约束：
    - Windows 环境使用 DOS 语法 (dir/type)
    - 执行复杂构建添加 --batch-mode 以防阻塞
""")
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java
git commit -m "docs(bash): update tool description with cross-platform error detection info"
```

---

## 总结

| Task | 组件 | 状态 |
|------|------|------|
| 0 | System Prompt 更新 | ⬜ |
| 1 | OsDetector | ⬜ |
| 2 | CommandStyleDetector | ⬜ |
| 3 | BashErrorClassifier | ⬜ |
| 4 | ErrorReportBuilder | ⬜ |
| 5 | BashExecutor (改造) | ⬜ |
| 6 | ResponseBuilder (改造) | ⬜ |
| 7 | BashToolConfig (整合) | ⬜ |
| 8-10 | 单元测试 (3个) | ⬜ |
| 11 | 集成测试 | ⬜ |
| 12 | 更新工具描述 | ⬜ |

**预计总工期：约 2 小时**
