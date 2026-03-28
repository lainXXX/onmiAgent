# Bash 命令适配增强设计规格书

> **文档版本:** v3.0
> **创建日期:** 2026-03-28
> **更新日期:** 2026-03-28
> **作者:** AI Review
> **状态:** 待评审

---

## 1. 核心设计原则

### 1.1 工具职责定位

| 组件 | 职责 | 不做 |
|------|------|------|
| **BashToolConfig** | 检测错误、返回清晰错误信息、建议纠正方向 | 不自动翻译命令 |
| **LLM (Agent)** | 理解错误原因、生成正确命令 | - |

**核心理念：工具告诉"有病"，LLM 自己开药**

### 1.2 错误处理流程

```
用户/AI 输入命令
        ↓
BashToolConfig 执行命令
        ↓
┌─ 成功 ──────────────────────────────┐
│  返回正常结果                        │
└─────────────────────────────────────┘
        ↓
┌─ 失败 ──────────────────────────────┐
│  错误检测 → 分类 → 返回错误报告     │
│                                    │
│  错误报告包含:                      │
│  1. 错误类型                        │
│  2. 失败原因                        │
│  3. 纠正建议                        │
│                                    │
│  LLM 收到 → 理解 → 生成正确命令      │
└─────────────────────────────────────┘
```

---

## 2. 错误分类与处理

### 2.1 错误类型

| 错误类型 | 检测规则 | 错误信息模板 |
|----------|----------|--------------|
| **命令不存在** | "不是内部或外部命令" | "'{cmd}' 不是 {os} 的命令。Windows 用 dir，Unix 用 ls" |
| **路径格式错误** | "系统找不到指定的路径" | 路径格式与当前 OS 不匹配 |
| **命令语法错误** | "语法不正确" | 命令语法与当前 OS 不兼容 |
| **权限不足** | "拒绝访问" | 权限不足，需要管理员 |
| **目录已存在** | "已经存在" | mkdir 参数问题 |

### 2.2 错误报告格式

```json
{
  "success": false,
  "error_type": "COMMAND_NOT_FOUND",
  "command": "ls -la /home/user",
  "target_os": "WINDOWS",
  "reason": "'ls' 不是 Windows CMD 命令",
  "suggestion": {
    "type": "COMMAND_REPLACEMENT",
    "original": "ls",
    "replacement": "dir",
    "full_command": "dir /a C:\\Users\\user",
    "explanation": "Windows CMD 使用 'dir' 替代 'ls'"
  },
  "hint": "请使用与当前操作系统匹配的 Windows CMD 命令"
}
```

---

## 3. 组件设计

### 3.1 OsDetector - 操作系统检测

**文件:** `src/main/java/top/javarem/omni/tool/bash/OsDetector.java`

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

### 3.2 CommandStyleDetector - 命令风格检测

**文件:** `src/main/java/top/javarem/omni/tool/bash/CommandStyleDetector.java`

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

### 3.3 BashErrorClassifier - 错误分类器

**文件:** `src/main/java/top/javarem/omni/tool/bash/BashErrorClassifier.java`

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bash 错误分类器
 *
 * <p>对命令执行结果进行分类，判断是否失败及失败原因。</p>
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
        /** 命令不存在 */
        COMMAND_NOT_FOUND,
        /** 路径不存在 */
        PATH_NOT_FOUND,
        /** 命令语法错误 */
        SYNTAX_ERROR,
        /** 权限不足 */
        PERMISSION_DENIED,
        /** 目录已存在 */
        ALREADY_EXISTS,
        /** 未知错误 */
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
        // 检查命令风格与 OS 是否匹配
        CommandStyleDetector.CommandStyle style = styleDetector.detect(command);
        OsDetector.OsType currentOs = osDetector.getCurrentOs();

        // 检查是否是不兼容的命令错误
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

        if (isPathNotFound(output)) {
            return new ErrorClassification(
                true,
                ErrorType.PATH_NOT_FOUND,
                command,
                buildPathNotFoundReason(command, style, currentOs),
                "请检查路径是否正确，注意当前系统使用 " + osDetector.getOsDisplayName() + " 路径格式"
            );
        }

        if (isSyntaxError(output)) {
            return new ErrorClassification(
                true,
                ErrorType.SYNTAX_ERROR,
                command,
                "命令语法与 " + osDetector.getOsDisplayName() + " 不兼容",
                "请使用符合当前系统的命令语法"
            );
        }

        if (isPermissionDenied(output)) {
            return new ErrorClassification(
                true,
                ErrorType.PERMISSION_DENIED,
                command,
                "权限不足，无法执行该命令",
                "可能需要管理员权限或更换操作目标"
            );
        }

        if (isAlreadyExists(output)) {
            return new ErrorClassification(
                true,
                ErrorType.ALREADY_EXISTS,
                command,
                "目标已存在",
                "检查目标是否存在，或使用 -f 参数强制覆盖"
            );
        }

        // 非零退出码但未分类的错误
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
                String translatedCmd = command.replace(cmd, replacement);
                return String.format(
                    "💡 建议: 使用 Windows CMD 命令\n   原命令: %s\n   建议改为: %s\n   常见对照: ls→dir, cat→type, grep→findstr, mkdir -p→mkdir",
                    command, translatedCmd
                );
            }
        }

        if ((currentOs == OsDetector.OsType.LINUX || currentOs == OsDetector.OsType.MACOS)
            && style == CommandStyleDetector.CommandStyle.WINDOWS) {
            String replacement = getWindowsToUnixReplacement(cmd);
            if (replacement != null) {
                String translatedCmd = command.replace(cmd, replacement);
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

        // 检查是否是路径格式问题
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
            case "tail" -> null;  // 无直接等价
            case "diff" -> "fc";
            case "sort" -> null;
            case "wc" -> null;
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
            case "rmdir" -> "rmdir" ;
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

### 3.4 ErrorReportBuilder - 错误报告构建器

**文件:** `src/main/java/top/javarem/omni/tool/bash/ErrorReportBuilder.java`

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

    /**
     * 构建命令不匹配警告（执行前检测）
     */
    public String buildMismatchWarning(String command,
            CommandStyleDetector.CommandStyle style,
            OsDetector.OsType currentOs) {
        StringBuilder warning = new StringBuilder();

        warning.append("⚠️ 命令风格警告\n");
        warning.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        warning.append("检测到命令可能与当前环境不匹配:\n");
        warning.append("   命令: ").append(command).append("\n");
        warning.append("   当前系统: ").append(osDetector.getOsDisplayName()).append("\n\n");

        if (currentOs == OsDetector.OsType.WINDOWS && style == CommandStyleDetector.CommandStyle.UNIX) {
            warning.append("该命令看起来是 Unix/Linux 命令。\n");
            warning.append("在 Windows 环境中，").append(osDetector.getOsDisplayName()).append("可能无法直接执行。\n");
            warning.append("请确认是否要继续执行，或使用 Windows 等价命令。\n");
        } else if (currentOs != OsDetector.OsType.WINDOWS && style == CommandStyleDetector.CommandStyle.WINDOWS) {
            warning.append("该命令看起来是 Windows CMD 命令。\n");
            warning.append("在 Unix/Linux/macOS 环境中可能无法执行。\n");
            warning.append("请确认是否要继续执行，或使用 Bash 等价命令。\n");
        }

        warning.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        warning.append("💡 如果执行失败，系统会提供更详细的错误信息和纠正建议。\n");

        return warning.toString();
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

---

## 4. BashToolConfig 改造

**文件:** `src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java`

```java
package top.javarem.omni.tool.bash;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.bash.translator.*;

/**
 * Bash 命令执行工具
 *
 * <p>提供跨平台命令执行能力，专注于错误检测和反馈。</p>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li><b>纠错为主</b> - 检测错误，返回清晰反馈</li>
 *   <li><b>不自动翻译</b> - 让 LLM 理解错误后自行修复</li>
 *   <li><b>详细建议</b> - 提供错误原因和纠正方向</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class BashToolConfig {

    @Resource
    private OsDetector osDetector;

    @Resource
    private CommandStyleDetector styleDetector;

    @Resource
    private BashErrorClassifier errorClassifier;

    @Resource
    private ErrorReportBuilder errorReportBuilder;

    @Resource
    private BashExecutor bashExecutor;

    @Resource
    private WorkingDirectoryManager workingDirectoryManager;

    /**
     * 执行 Bash 命令
     *
     * <p>执行命令并返回结果。如果失败，返回详细的错误报告，
     * 包括错误原因和纠正建议，帮助 LLM 自我修正。</p>
     *
     * @param command        要执行的命令
     * @param conversationId 会话ID（用于工作目录追踪）
     * @param timeout        超时时间（秒）
     * @return 命令执行结果或错误报告
     */
    @Tool(name = "bash", description = "Execute bash command. Returns detailed error report if failed.")
    public String bash(
            @ToolParam(description = "The command to execute") String command,
            @ToolParam(description = "Conversation ID for working directory tracking") String conversationId,
            @ToolParam(description = "Timeout in seconds", defaultValue = "30") Integer timeout
    ) {
        // 1. 检测命令风格
        CommandStyleDetector.CommandStyle style = styleDetector.detect(command);
        OsDetector.OsType currentOs = osDetector.getCurrentOs();

        // 2. 执行前警告（可选，记录日志）
        if (styleDetector.isUnixStyle(command) && currentOs == OsDetector.OsType.WINDOWS) {
            log.warn("[BashToolConfig] 检测到 Unix 命令在 Windows 环境: {}", command);
        }

        // 3. 准备命令（注入工作目录）
        String finalCommand = workingDirectoryManager.prepareCommand(conversationId, command);

        // 4. 执行命令
        BashExecutor.ExecutionResult result;
        try {
            result = bashExecutor.execute(finalCommand, timeout, currentOs);
        } catch (Exception e) {
            log.error("[BashToolConfig] 命令执行异常: {} → {}", command, e.getMessage());
            return "❌ 命令执行异常: " + e.getMessage();
        }

        // 5. 分类错误
        BashErrorClassifier.ErrorClassification error =
            errorClassifier.classify(command, result.exitCode(), result.output());

        // 6. 如果出错，返回错误报告
        if (error.isError()) {
            String report = errorReportBuilder.buildReport(error);
            log.info("[BashToolConfig] 命令失败: {} → {}", command, error.type());
            return report;
        }

        // 7. 返回成功结果
        return result.formattedOutput();
    }
}
```

### 4.1 BashExecutor.ExecutionResult

```java
// BashExecutor 中的结果记录
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
```

---

## 5. 示例对话

### 5.1 Unix 命令在 Windows 环境

```
用户/AI: ls -la /home/user
    ↓
工具执行: ls -la /home/user
    ↓
结果: "'ls' 不是内部或外部命令"
    ↓
返回错误报告:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
❌ 命令执行失败
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📋 错误类型: 命令不存在 (COMMAND_NOT_FOUND)

📝 失败原因:
   检测到 Unix 命令 'ls' 在 Windows 环境执行失败。
   Windows 使用 'dir' 替代 'ls'，'mkdir -p' 在 Windows 中只需 'mkdir'

💡 建议: 使用 Windows CMD 命令
   原命令: ls -la /home/user
   建议改为: dir /a C:\Users\user
   常见对照: ls→dir, cat→type, grep→findstr, mkdir -p→mkdir

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💻 当前环境: Windows
📌 请根据上述信息修正命令后重试
    ↓
LLM 理解错误 → 生成: dir C:\Users\user
    ↓
工具执行: dir C:\Users\user
    ↓
成功 → 返回目录列表
```

### 5.2 Windows 命令在 Linux 环境

```
用户/AI: dir C:\Users
    ↓
工具执行: dir C:\Users
    ↓
结果: "dir: command not found"
    ↓
返回错误报告:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
❌ 命令执行失败
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📋 错误类型: 命令不存在 (COMMAND_NOT_FOUND)

📝 失败原因:
   检测到 Windows 命令 'dir' 在 Linux 环境执行失败。
   Unix/Linux 使用 'ls' 替代 'dir'

💡 建议: 使用 Unix/Bash 命令
   原命令: dir C:\Users
   建议改为: ls /c/Users
   常见对照: dir→ls, type→cat, copy→cp

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💻 当前环境: Linux
📌 请根据上述信息修正命令后重试
    ↓
LLM 理解错误 → 生成: ls /c/Users
    ↓
工具执行: ls /c/Users
    ↓
成功 → 返回目录列表
```

### 5.3 路径格式错误

```
用户/AI: cd /c/Users/Desktop
    ↓
工具执行: cd /c/Users/Desktop (在 Windows 上)
    ↓
结果: "系统找不到指定的路径"
    ↓
返回错误报告:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
❌ 命令执行失败
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📋 错误类型: 路径不存在 (PATH_NOT_FOUND)

📝 失败原因:
   检测到 Unix 路径格式在 Windows 环境无法识别。
   Unix 用 /c/Users，Windows 用 C:\Users

💡 请检查路径是否正确，注意当前系统使用 Windows 路径格式

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💻 当前环境: Windows
📌 请根据上述信息修正命令后重试
    ↓
LLM 理解 → 生成: cd C:\Users\Desktop
```

---

## 6. 辅助组件

### 6.1 WorkingDirectoryManager

```java
package top.javarem.omni.tool.bash;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作目录管理器
 *
 * <p>管理每个对话会话的工作目录，确保命令在正确的目录下执行。</p>
 */
@Component
@Slf4j
public class WorkingDirectoryManager {

    private final Map<String, Path> sessionWorkingDirs = new ConcurrentHashMap<>();
    private final Path defaultWorkspace;

    @Value("${bash.workspace:D:\\Develop\\ai\\omniAgent}")
    private String workspace;

    private static final Pattern CD_PATTERN = Pattern.compile(
        "^\\s*cd\\s+(.+?)\\s*(?:&&|$)", Pattern.CASE_INSENSITIVE
    );

    public WorkingDirectoryManager() {
        this.defaultWorkspace = Paths.get(System.getProperty("user.dir"));
    }

    public Path getWorkingDirectory(String conversationId) {
        return sessionWorkingDirs.getOrDefault(conversationId, defaultWorkspace);
    }

    public String prepareCommand(String conversationId, String command) {
        Path workDir = getWorkingDirectory(conversationId);

        // 检测 cd 命令
        Matcher matcher = CD_PATTERN.matcher(command);
        if (matcher.find()) {
            String target = matcher.group(1).replace("\"", "").replace("'", "");
            try {
                Path newDir = workDir.resolve(target).normalize();
                if (newDir.toFile().exists() && newDir.toFile().isDirectory()) {
                    sessionWorkingDirs.put(conversationId, newDir);
                }
            } catch (Exception e) {
                log.warn("[WorkingDirectoryManager] cd 解析失败: {}", target);
            }
        }

        // 如果是绝对路径，不处理
        if (command.trim().matches("^[A-Za-z]:\\\\.*") || command.trim().startsWith("/")) {
            return command;
        }

        // 注入工作目录
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            return String.format("cd /d \"%s\" && %s", workDir, command);
        } else {
            return String.format("cd \"%s\" && %s", workDir, command);
        }
    }

    public void clear(String conversationId) {
        sessionWorkingDirs.remove(conversationId);
    }
}
```

### 6.2 BashExecutor (改造)

```java
// 保持原有执行逻辑，增加 ExecutionResult 记录
public ExecutionResult execute(String command, int timeout, OsDetector.OsType osType) {
    ProcessBuilder builder = new ProcessBuilder();

    if (osType == OsDetector.OsType.WINDOWS) {
        builder.command("cmd", "/c", command);
    } else {
        builder.command("sh", "-c", command);
    }

    // ... 执行逻辑 ...

    return new ExecutionResult(exitCode, output, errorOutput, executionTimeMs);
}
```

---

## 7. 测试用例

### 7.1 错误分类测试

```java
@Nested
@DisplayName("BashErrorClassifier 错误分类测试")
class BashErrorClassifierTest {

    private BashErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        OsDetector osDetector = new OsDetector();
        CommandStyleDetector styleDetector = new CommandStyleDetector();
        classifier = new BashErrorClassifier(osDetector, styleDetector);
    }

    @Test
    @DisplayName("检测 Unix 命令在 Windows 环境错误")
    void testUnixCommandNotFound() {
        var result = classifier.classify("ls -la", 1, "'ls' 不是内部或外部命令");

        assertTrue(result.isError());
        assertEquals(ErrorType.COMMAND_NOT_FOUND, result.type());
        assertTrue(result.reason().contains("Unix 命令"));
        assertTrue(result.suggestion().contains("dir"));
    }

    @Test
    @DisplayName("检测 Windows 命令在 Linux 环境错误")
    void testWindowsCommandNotFound() {
        var result = classifier.classify("dir C:\\Users", 127, "dir: command not found");

        assertTrue(result.isError());
        assertEquals(ErrorType.COMMAND_NOT_FOUND, result.type());
        assertTrue(result.reason().contains("Windows 命令"));
        assertTrue(result.suggestion().contains("ls"));
    }

    @Test
    @DisplayName("检测路径格式错误")
    void testPathFormatError() {
        var result = classifier.classify("/c/Users/test", 1, "系统找不到指定的路径");

        assertTrue(result.isError());
        assertEquals(ErrorType.PATH_NOT_FOUND, result.type());
        assertTrue(result.reason().contains("Unix 路径格式"));
    }
}
```

### 7.2 命令风格检测测试

```java
@Nested
@DisplayName("CommandStyleDetector 测试")
class CommandStyleDetectorTest {

    private CommandStyleDetector detector = new CommandStyleDetector();

    @Test
    @DisplayName("检测 ls 为 UNIX")
    void testDetectLs() {
        assertEquals(CommandStyle.UNIX, detector.detect("ls -la"));
    }

    @Test
    @DisplayName("检测 dir 为 WINDOWS")
    void testDetectDir() {
        assertEquals(CommandStyle.WINDOWS, detector.detect("dir"));
    }

    @Test
    @DisplayName("检测 /home/ 为 UNIX 路径")
    void testDetectUnixPath() {
        assertEquals(CommandStyle.UNIX, detector.detect("/home/user/file.txt"));
    }

    @Test
    @DisplayName("检测 C:\\ 为 WINDOWS 路径")
    void testDetectWindowsPath() {
        assertEquals(CommandStyle.WINDOWS, detector.detect("C:\\Users\\file.txt"));
    }
}
```

---

## 8. 配置项

```yaml
bash:
  workspace: ${user.dir}
  error-report:
    enabled: true
    verbose: true  # 详细错误信息
```

---

## 9. 与旧版设计的区别

| 方面 | 旧版 (v1/v2) | 新版 (v3) |
|------|--------------|------------|
| **翻译主体** | 工具自动翻译 | LLM 自行修复 |
| **工具角色** | 翻译执行者 | 错误检测器 |
| **失败处理** | 自动转换命令 | 返回错误报告 |
| **复杂度** | 高 (多翻译器) | 低 (错误分类) |
| **可维护性** | 翻译规则难维护 | 错误规则易扩展 |
| **LLM 参与度** | 低 | 高 (自我纠错) |

---

## 10. 里程碑

| Phase | 内容 |
|-------|------|
| **Phase 1** | `OsDetector` + `CommandStyleDetector` |
| **Phase 2** | `BashErrorClassifier` 错误分类器 |
| **Phase 3** | `ErrorReportBuilder` 报告构建器 |
| **Phase 4** | `BashToolConfig` 集成 |
| **Phase 5** | `WorkingDirectoryManager` 完善 |
| **Phase 6** | 单元测试 + 集成测试 |

**总工期估算：约 1-2 人天**
