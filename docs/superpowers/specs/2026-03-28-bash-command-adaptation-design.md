# Bash 命令适配增强设计规格书

> **文档版本:** v2.0
> **创建日期:** 2026-03-28
> **更新日期:** 2026-03-28
> **作者:** AI Review
> **状态:** 待评审

---

## 1. 背景与问题

### 1.1 问题描述

当前 `BashToolConfig` 在 Windows 环境下存在以下问题：

| 问题 | 表现 | 影响 |
|------|------|------|
| **命令风格不匹配** | 用户输入 Unix 命令在 Windows 上执行失败 | 80% 的工具调用失败源于此 |
| **失败模式无记忆** | 同一错误在同一对话中重复出现 | 大量无效 Token 消耗 |
| **工作目录污染** | `cd && command` 失败导致文件写到错误位置 | 产物位置不确定 |

### 1.2 核心思路

**翻译目标 OS = AI 自身运行的系统**

```
用户输入命令（如 ls -la /home/user）
        ↓
AI 检测自身运行系统：Windows
        ↓
自动翻译：Unix 命令 → Windows CMD 命令
        ↓
执行翻译后的命令
```

**双向自动转换：**

| 用户输入风格 | AI 运行系统 | 翻译结果 |
|-------------|-------------|----------|
| Unix (`ls -la /home/user`) | Windows | `dir C:\Users\user` |
| Windows (`dir C:\Users`) | Linux/Mac | `ls /home/user` |
| PowerShell (`Get-ChildItem`) | Linux/Mac | `ls -la` |

### 1.3 预期改进

| 指标 | 改进前 | 改进后 |
|------|--------|--------|
| 同一错误重复率 | ~40% | <5% |
| 命令往返次数 | 15-20 次 | 5-8 次 |
| Token 消耗 | ~3000 | ~1200 |
| 成功率 | ~70% | ~95% |

---

## 2. 解决方案架构

### 2.1 组件拓扑

```
src/main/java/top/javarem/omni/tool/bash/
├── BashToolConfig.java              # 主入口（改造）
├── BashExecutor.java                # 命令执行器
├── translator/                     # 新增包
│   ├── CommandTranslator.java       # 接口
│   ├── UnixToWindowsTranslator.java    # Unix→Windows
│   └── WindowsToUnixTranslator.java     # Windows→Unix/Mac
├── CommandStyleDetector.java        # 命令风格检测（重命名）
├── BashFailureMemory.java           # 失败模式记忆
├── WorkingDirectoryManager.java     # 工作目录隔离
└── ResponseBuilder.java             # 响应构建
```

### 2.2 调用链

```
BashToolConfig.bash(command)
    │
    ├─► BashFailureMemory.shouldAvoid()
    │       └─ 命中已知失败模式 → 返回警告
    │
    ├─► CommandStyleDetector.detect(command)
    │       └─ 检测输入命令风格：UNIX / WINDOWS / POWERSHELL / UNKNOWN
    │
    ├─► OsDetector.getCurrentOs()
    │       └─ 检测 AI 自身运行系统：WINDOWS / LINUX / MACOS
    │
    ├─► CommandTranslator.translate(command, inputStyle, targetOs)
    │       └─ 翻译到目标系统
    │
    ├─► WorkingDirectoryManager.prepareCommand()
    │       └─ 注入工作目录
    │
    ├─► BashExecutor.execute()
    │       └─ 执行命令
    │
    └─► BashFailureMemory.recordFailure()
            └─ 记录失败模式
```

---

## 3. 核心组件设计

### 3.1 OsDetector - 系统检测

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/OsDetector.java`

```java
package top.javarem.omni.tool.bash.translator;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 操作系统检测器
 *
 * <p>检测 AI 自身运行的操作系统，用于确定命令翻译的目标平台。</p>
 *
 * <h3>使用场景：</h3>
 * <ul>
 *   <li>用户输入 Unix 命令 → AI 运行在 Windows → 需要翻译为 Windows CMD</li>
 *   <li>用户输入 Windows 命令 → AI 运行在 Linux → 需要翻译为 Bash</li>
 * </ul>
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

    /**
     * 检测当前操作系统
     */
    private OsType detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("windows")) {
            return OsType.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return OsType.MACOS;
        } else if (os.contains("linux")) {
            return OsType.LINUX;
        } else {
            log.warn("[OsDetector] 无法识别操作系统: {}, 默认为 LINUX", os);
            return OsType.LINUX;
        }
    }

    /**
     * 操作系统类型
     */
    public enum OsType {
        WINDOWS,
        LINUX,
        MACOS
    }
}
```

### 3.2 CommandStyleDetector - 命令风格检测

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/CommandStyleDetector.java`

```java
package top.javarem.omni.tool.bash.translator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 命令风格检测器
 *
 * <p>检测用户输入的命令风格，用于确定是否需要翻译。</p>
 *
 * <h3>检测规则：</h3>
 * <ul>
 *   <li><b>UNIX</b> - ls, mkdir -p, which, cat, grep, /home/, ~</li>
 *   <li><b>WINDOWS</b> - dir, type, if exist, \, A:\</li>
 *   <li><b>POWERSHELL</b> - Get-ChildItem, Write-Host, $env:</li>
 *   <li><b>UNKNOWN</b> - 无法判断</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class CommandStyleDetector {

    // Unix 特有命令模式
    private static final Pattern UNIX_COMMAND = Pattern.compile(
        "\\b(ls|ll|la|mkdir -p|which|cat|grep|egrep|fgrep|touch|chmod|export|whoami|pwd|uname|top|ps aux|kill -9|man|head|tail|cut|awk|sed|sort|uniq|wc)\\b"
    );

    // Windows CMD 特有模式
    private static final Pattern WINDOWS_COMMAND = Pattern.compile(
        "\\b(dir|type |del |rmdir|mkdir|attrib|set \\w+|echo %\\w+%)\\b"
    );

    // PowerShell 特有模式
    private static final Pattern POWERSHELL_COMMAND = Pattern.compile(
        "\\b(Get-ChildItem|Write-Host|Get-Process|\\$\\w+:|Where-Object|Select-Object|ForEach-Object)\\b"
    );

    // Unix 特有路径模式
    private static final Pattern UNIX_PATH = Pattern.compile(
        "^/[a-z]/|^/home/|^/usr/|^/etc/|^/var/|^/tmp/|~"
    );

    // Windows 特有路径模式
    private static final Pattern WINDOWS_PATH = Pattern.compile(
        "^[A-Za-z]:\\\\|^\\\\\\\\|\\\\[^/\\\\]+\\\\"
    );

    /**
     * 命令风格枚举
     */
    public enum CommandStyle {
        UNIX,       // Unix/Linux/Mac 命令
        WINDOWS,    // Windows CMD 命令
        POWERSHELL, // PowerShell 命令
        UNKNOWN     // 无法判断
    }

    /**
     * 检测命令风格
     *
     * @param command 用户输入的命令
     * @return 命令风格
     */
    public CommandStyle detect(String command) {
        if (command == null || command.isBlank()) {
            return CommandStyle.UNKNOWN;
        }

        String lowerCmd = command.toLowerCase();

        // 检测 PowerShell
        if (POWERSHELL_COMMAND.matcher(lowerCmd).find()) {
            log.debug("[CommandStyleDetector] 检测为 POWERSHELL: {}", command);
            return CommandStyle.POWERSHELL;
        }

        // 检测 Unix 命令
        boolean hasUnixCommand = UNIX_COMMAND.matcher(lowerCmd).find();
        boolean hasUnixPath = UNIX_PATH.matcher(command).find();

        // 检测 Windows 命令
        boolean hasWindowsCommand = WINDOWS_COMMAND.matcher(lowerCmd).find();
        boolean hasWindowsPath = WINDOWS_PATH.matcher(command).find();

        // 综合判断
        if (hasUnixCommand || hasUnixPath) {
            log.debug("[CommandStyleDetector] 检测为 UNIX: {}", command);
            return CommandStyle.UNIX;
        }

        if (hasWindowsCommand || hasWindowsPath) {
            log.debug("[CommandStyleDetector] 检测为 WINDOWS: {}", command);
            return CommandStyle.WINDOWS;
        }

        log.debug("[CommandStyleDetector] 无法判断风格: {}", command);
        return CommandStyle.UNKNOWN;
    }

    /**
     * 判断是否需要翻译
     *
     * @param command 用户输入的命令
     * @param currentOs AI 自身运行的系统
     * @return true 如果需要翻译
     */
    public boolean needsTranslation(String command, OsDetector.OsType currentOs) {
        CommandStyle style = detect(command);

        switch (currentOs) {
            case WINDOWS:
                return style == CommandStyle.UNIX || style == CommandStyle.POWERSHELL;
            case LINUX:
            case MACOS:
                return style == CommandStyle.WINDOWS || style == CommandStyle.POWERSHELL;
            default:
                return false;
        }
    }

    /**
     * 获取检测说明
     */
    public String getDetectionReason(String command) {
        CommandStyle style = detect(command);
        switch (style) {
            case UNIX:
                return "检测到 Unix 命令风格 (ls, mkdir -p, /home/, ~)";
            case WINDOWS:
                return "检测到 Windows CMD 命令风格 (dir, type, \\)";
            case POWERSHELL:
                return "检测到 PowerShell 命令风格 (Get-ChildItem, $env:)";
            default:
                return "无法判断命令风格";
        }
    }
}
```

### 3.3 CommandTranslator 接口

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/CommandTranslator.java`

```java
package top.javarem.omni.tool.bash.translator;

/**
 * 命令翻译器接口
 *
 * <p>将用户输入的命令翻译为 AI 自身运行系统能理解的命令。</p>
 *
 * <h3>翻译方向：</h3>
 * <ul>
 *   <li>用户输入 Unix 命令 + AI 运行在 Windows → 翻译为 Windows CMD</li>
 *   <li>用户输入 Windows 命令 + AI 运行在 Linux/Mac → 翻译为 Bash</li>
 *   <li>用户输入 PowerShell + AI 运行在 Unix → 翻译为 Bash</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-28
 */
public interface CommandTranslator {

    /**
     * 翻译命令到目标操作系统
     *
     * @param command    原始命令
     * @param sourceStyle 源命令风格
     * @param targetOs   目标操作系统
     * @return 翻译后的命令
     */
    String translate(String command, CommandStyleDetector.CommandStyle sourceStyle, OsDetector.OsType targetOs);

    /**
     * 判断是否支持翻译
     */
    boolean canTranslate(CommandStyleDetector.CommandStyle sourceStyle, OsDetector.OsType targetOs);
}
```

### 3.4 UnixToWindowsTranslator - Unix→Windows

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/UnixToWindowsTranslator.java`

```java
package top.javarem.omni.tool.bash.translator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unix/Linux/Mac → Windows CMD 翻译器
 *
 * <h3>翻译规则：</h3>
 * <table>
 * <tr><th>Unix</th><th>Windows CMD</th></tr>
 * <tr><td>ls</td><td>dir</td></tr>
 * <tr><td>ls -la</td><td>dir /a</td></tr>
 * <tr><td>mkdir -p</td><td>mkdir</td></tr>
 * <tr><td>which</td><td>where</td></tr>
 * <tr><td>cat</td><td>type</td></tr>
 * <tr><td>grep</td><td>findstr</td></tr>
 * <tr><td>cp</td><td>copy</td></tr>
 * <tr><td>mv</td><td>move</td></tr>
 * <tr><td>rm -rf</td><td>rmdir /s /q</td></tr>
 * <tr><td>touch</td><td>type NUL ></td></tr>
 * <tr><td>pwd</td><td>cd</td></tr>
 * <tr><td>cd ~</td><td>cd %USERPROFILE%</td></tr>
 * <tr><td>/c/Users</td><td>C:\Users</td></tr>
 * <tr><td>/home/</td><td>C:\Users\</td></tr>
 * <tr><td>$VAR</td><td>%VAR%</td></tr>
 * <tr><td>&&</td><td>&</td></tr>
 * <tr><td>/dev/null</td><td>NUL</td></tr>
 * </table>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class UnixToWindowsTranslator implements CommandTranslator {

    private static final Map<String, String> COMMAND_MAP = Map.ofEntries(
        Map.entry("ls", "dir"),
        Map.entry("ll", "dir"),
        Map.entry("la", "dir /a"),
        Map.entry("la -a", "dir /a"),
        Map.entry("mkdir -p", "mkdir"),
        Map.entry("rm -rf", "rmdir /s /q"),
        Map.entry("rm -r", "rmdir /s /q"),
        Map.entry("rm", "del /q"),
        Map.entry("which", "where"),
        Map.entry("cat", "type"),
        Map.entry("grep", "findstr"),
        Map.entry("egrep", "findstr /r"),
        Map.entry("fgrep", "findstr"),
        Map.entry("cp", "copy"),
        Map.entry("mv", "move"),
        Map.entry("touch", "type NUL >"),
        Map.entry("pwd", "cd"),
        Map.entry("clear", "cls"),
        Map.entry("export", "set"),
        Map.entry("echo $", "echo %"),
        Map.entry("&&", "&"),
        Map.entry("||", "|"),
        Map.entry("|", "^|"),
        Map.entry("~", "%USERPROFILE%")
    );

    // /c/Users/aaa 或 /d/Program Files
    private static final Pattern UNIX_DRIVE_PATH = Pattern.compile("/([a-z])(/.*)?$");

    // $HOME, $PATH, $USER
    private static final Pattern BASH_VAR = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");

    @Override
    public String translate(String command, CommandStyleDetector.CommandStyle sourceStyle,
                           OsDetector.OsType targetOs) {
        if (targetOs != OsDetector.OsType.WINDOWS) {
            return command; // 只翻译到 Windows
        }

        String translated = command;

        // 1. 命令翻译
        translated = translateCommands(translated);

        // 2. 路径翻译 (/c/Users → C:\Users)
        translated = translatePaths(translated);

        // 3. 变量翻译 ($HOME → %USERPROFILE%)
        translated = translateVariables(translated);

        log.info("[UnixToWindowsTranslator] 翻译结果: {} → {}", command, translated);
        return translated;
    }

    @Override
    public boolean canTranslate(CommandStyleDetector.CommandStyle sourceStyle, OsDetector.OsType targetOs) {
        return sourceStyle == CommandStyleDetector.CommandStyle.UNIX
            && targetOs == OsDetector.OsType.WINDOWS;
    }

    private String translateCommands(String cmd) {
        String result = cmd;

        // 按长度降序排列，确保长命令优先匹配
        COMMAND_MAP.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .forEach(entry -> {
                String pattern = "\\b" + Pattern.quote(entry.getKey()) + "\\b";
                result = result.replaceAll(pattern, entry.getValue());
            });

        return result;
    }

    private String translatePaths(String cmd) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = UNIX_DRIVE_PATH.matcher(cmd);

        while (matcher.find()) {
            char drive = Character.toUpperCase(matcher.group(1).charAt(0));
            String path = matcher.group(2) != null ? matcher.group(2).replace("/", "\\") : "\\";
            matcher.appendReplacement(sb, drive + ":\\" + path.substring(1));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String translateVariables(String cmd) {
        // 特殊变量映射
        Map<String, String> varMap = Map.of(
            "HOME", "USERPROFILE",
            "USER", "USERNAME",
            "PWD", "CD",
            "TMPDIR", "TEMP"
        );

        StringBuffer sb = new StringBuffer();
        Matcher matcher = BASH_VAR.matcher(cmd);

        while (matcher.find()) {
            String varName = matcher.group(1);
            String windowsVar = varMap.getOrDefault(varName, varName);
            matcher.appendReplacement(sb, "%" + windowsVar + "%");
        }
        matcher.appendTail(sb);

        return sb.toString();
    }
}
```

### 3.5 WindowsToUnixTranslator - Windows→Unix

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/WindowsToUnixTranslator.java`

```java
package top.javarem.omni.tool.bash.translator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows CMD/PowerShell → Unix/Linux/Mac 翻译器
 *
 * <h3>翻译规则：</h3>
 * <table>
 * <tr><th>Windows</th><th>Unix</th></tr>
 * <tr><td>dir</td><td>ls</td></tr>
 * <tr><td>dir /a</td><td>ls -la</td></tr>
 * <tr><td>type</td><td>cat</td></tr>
 * <tr><td>copy</td><td>cp</td></tr>
 * <tr><td>move</td><td>mv</td></tr>
 * <tr><td>del /q</td><td>rm</td></tr>
 * <tr><td>rmdir /s /q</td><td>rm -rf</td></tr>
 * <tr><td>mkdir</td><td>mkdir -p</td></tr>
 * <tr><td>where</td><td>which</td></tr>
 * <tr><td>cd %USERPROFILE%</td><td>cd ~</td></tr>
 * <tr><td>C:\Users</td><td>/c/Users</td></tr>
 * <tr><td>%VAR%</td><td>$VAR</td></tr>
 * <tr><td>NUL</td><td>/dev/null</td></tr>
 * <tr><td>&amp;</td><td>&amp;&amp;</td></tr>
 * </table>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class WindowsToUnixTranslator implements CommandTranslator {

    private static final Map<String, String> COMMAND_MAP = Map.ofEntries(
        Map.entry("dir", "ls"),
        Map.entry("dir /a", "ls -la"),
        Map.entry("dir /s", "ls -R"),
        Map.entry("type", "cat"),
        Map.entry("copy", "cp"),
        Map.entry("move", "mv"),
        Map.entry("del /q", "rm"),
        Map.entry("del", "rm"),
        Map.entry("rmdir /s /q", "rm -rf"),
        Map.entry("mkdir", "mkdir -p"),
        Map.entry("where", "which"),
        Map.entry("cls", "clear"),
        Map.entry("set ", "export "),
        Map.entry("NUL", "/dev/null"),
        Map.entry("^|", "|")
    );

    // C:\Users\aaa 或 D:\Program Files
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile(
        "([A-Z]):\\\\(.*?)(?:\\\\|$)"
    );

    // %USERPROFILE%, %TEMP%
    private static final Pattern CMD_VAR = Pattern.compile("%([A-Za-z_][A-Za-z0-9_]*)%");

    @Override
    public String translate(String command, CommandStyleDetector.CommandStyle sourceStyle,
                           OsDetector.OsType targetOs) {
        if (targetOs == OsDetector.OsType.WINDOWS) {
            return command; // 不翻译到 Windows
        }

        String translated = command;

        // 1. 命令翻译
        translated = translateCommands(translated);

        // 2. 路径翻译 (C:\Users → /c/Users)
        translated = translatePaths(translated);

        // 3. 变量翻译 (%USERPROFILE% → $HOME)
        translated = translateVariables(translated);

        log.info("[WindowsToUnixTranslator] 翻译结果: {} → {}", command, translated);
        return translated;
    }

    @Override
    public boolean canTranslate(CommandStyleDetector.CommandStyle sourceStyle, OsDetector.OsType targetOs) {
        return (sourceStyle == CommandStyleDetector.CommandStyle.WINDOWS
             || sourceStyle == CommandStyleDetector.CommandStyle.POWERSHELL)
            && targetOs != OsDetector.OsType.WINDOWS;
    }

    private String translateCommands(String cmd) {
        String result = cmd;

        COMMAND_MAP.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .forEach(entry -> {
                String pattern = "\\b" + Pattern.quote(entry.getKey()) + "\\b";
                result = result.replaceAll(pattern, entry.getValue());
            });

        return result;
    }

    private String translatePaths(String cmd) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = WINDOWS_DRIVE_PATH.matcher(cmd);

        while (matcher.find()) {
            char drive = Character.toLowerCase(matcher.group(1).charAt(0));
            String path = matcher.group(2).replace("\\", "/");
            matcher.appendReplacement(sb, "/" + drive + "/" + path);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String translateVariables(String cmd) {
        Map<String, String> varMap = Map.of(
            "USERPROFILE", "HOME",
            "TEMP", "TMPDIR",
            "USERNAME", "USER",
            "PATH", "PATH"
        );

        StringBuffer sb = new StringBuffer();
        Matcher matcher = CMD_VAR.matcher(cmd);

        while (matcher.find()) {
            String varName = matcher.group(1);
            String unixVar = varMap.getOrDefault(varName, varName);
            matcher.appendReplacement(sb, "$" + unixVar);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }
}
```

### 3.6 UnifiedTranslator - 统一翻译器

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/UnifiedTranslator.java`

```java
package top.javarem.omni.tool.bash.translator;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一翻译器
 *
 * <p>协调多个翻译器，根据源命令风格和目标系统选择合适的翻译器。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class UnifiedTranslator {

    @Resource
    private OsDetector osDetector;

    @Resource
    private CommandStyleDetector styleDetector;

    @Resource
    private UnixToWindowsTranslator unixToWindowsTranslator;

    @Resource
    private WindowsToUnixTranslator windowsToUnixTranslator;

    /**
     * 翻译命令到当前系统
     *
     * @param command 用户输入的命令
     * @return 翻译后的命令
     */
    public String translate(String command) {
        CommandStyleDetector.CommandStyle sourceStyle = styleDetector.detect(command);
        OsDetector.OsType targetOs = osDetector.getCurrentOs();

        log.debug("[UnifiedTranslator] 翻译: style={}, target={}, cmd={}",
            sourceStyle, targetOs, command);

        // 检查是否需要翻译
        if (!styleDetector.needsTranslation(command, targetOs)) {
            log.debug("[UnifiedTranslator] 不需要翻译");
            return command;
        }

        // 选择合适的翻译器
        if (sourceStyle == CommandStyleDetector.CommandStyle.UNIX) {
            return unixToWindowsTranslator.translate(command, sourceStyle, targetOs);
        } else if (sourceStyle == CommandStyleDetector.CommandStyle.WINDOWS
                || sourceStyle == CommandStyleDetector.CommandStyle.POWERSHELL) {
            return windowsToUnixTranslator.translate(command, sourceStyle, targetOs);
        }

        return command;
    }

    /**
     * 获取翻译说明
     */
    public String getTranslationInfo(String command) {
        CommandStyleDetector.CommandStyle style = styleDetector.detect(command);
        OsDetector.OsType os = osDetector.getCurrentOs();

        if (!styleDetector.needsTranslation(command, os)) {
            return "命令风格与当前系统匹配，无需翻译";
        }

        String styleDesc = switch (style) {
            case UNIX -> "Unix/Linux";
            case WINDOWS -> "Windows CMD";
            case POWERSHELL -> "PowerShell";
            default -> "未知";
        };

        String osDesc = switch (os) {
            case WINDOWS -> "Windows";
            case LINUX -> "Linux";
            case MACOS -> "macOS";
        };

        return String.format("检测到 %s 命令，将翻译为 %s 命令", styleDesc, osDesc);
    }
}
```

---

## 4. BashFailureMemory - 失败模式记忆

**文件:** `src/main/java/top/javarem/omni/tool/bash/BashFailureMemory.java`

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bash 失败模式记忆
 *
 * <p>在单个对话会话中记录失败的命令模式，避免重复踩坑。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class BashFailureMemory {

    private final Map<String, Set<String>> failedPatterns = new ConcurrentHashMap<>();

    private static final Map<String, String> COMMAND_SUGGESTIONS = Map.of(
        "which", "where (Windows) 或 which (Unix)",
        "ls", "dir (Windows) 或 ls (Unix)",
        "mkdir -p", "mkdir (Windows 不需要 -p)",
        "cat", "type (Windows) 或 cat (Unix)",
        "grep", "findstr (Windows) 或 grep (Unix)"
    );

    private static final List<Pattern> FAILURE_PATTERNS = List.of(
        Pattern.compile("不是内部或外部命令", Pattern.CASE_INSENSITIVE),
        Pattern.compile("is not (an? )?internal or external command", Pattern.CASE_INSENSITIVE),
        Pattern.compile("not recognized", Pattern.CASE_INSENSITIVE),
        Pattern.compile("子目录或文件 .* 已经存在", Pattern.CASE_INSENSITIVE),
        Pattern.compile("The syntax of the command is incorrect", Pattern.CASE_INSENSITIVE),
        Pattern.compile("No such file or directory", Pattern.CASE_INSENSITIVE)
    );

    public void recordFailure(String conversationId, String command, String errorOutput) {
        if (!isFailure(errorOutput)) {
            return;
        }

        String pattern = extractCommandPattern(command);
        failedPatterns
            .computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet())
            .add(pattern);

        log.info("[BashFailureMemory] 记录失败模式: conversationId={}, pattern={}", conversationId, pattern);
    }

    public boolean shouldAvoid(String conversationId, String command) {
        Set<String> patterns = failedPatterns.get(conversationId);
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }

        String commandPattern = extractCommandPattern(command);
        return patterns.stream()
            .anyMatch(failed -> commandPattern.startsWith(failed) || command.contains(failed));
    }

    public String getSuggestion(String conversationId, String failedCommand) {
        Set<String> patterns = failedPatterns.get(conversationId);
        if (patterns != null) {
            for (String failed : patterns) {
                if (failedCommand.contains(failed)) {
                    return COMMAND_SUGGESTIONS.get(failed);
                }
            }
        }

        for (Map.Entry<String, String> entry : COMMAND_SUGGESTIONS.entrySet()) {
            if (failedCommand.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    public void clear(String conversationId) {
        failedPatterns.remove(conversationId);
    }

    private boolean isFailure(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return FAILURE_PATTERNS.stream()
            .anyMatch(p -> p.matcher(output).find());
    }

    private String extractCommandPattern(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        int spaceIdx = trimmed.indexOf(' ');
        return spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
    }
}
```

---

## 5. WorkingDirectoryManager - 工作目录管理

**文件:** `src/main/java/top/javarem/omni/tool/bash/WorkingDirectoryManager.java`

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
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class WorkingDirectoryManager {

    private final Map<String, Path> sessionWorkingDirs = new ConcurrentHashMap<>();
    private final Path defaultWorkspace;

    @Resource
    private translator.CommandTranslator commandTranslator;

    private static final Pattern CD_PATTERN = Pattern.compile(
        "^\\s*cd\\s+(.+?)\\s*(?:&&|$)", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:\\\\|^/|\\\\\\\\");

    public WorkingDirectoryManager(
            @Value("${bash.workspace:D:\\Develop\\ai\\omniAgent}") String workspace) {
        this.defaultWorkspace = Paths.get(workspace);
        log.info("[WorkingDirectoryManager] 初始化，默认工作目录: {}", defaultWorkspace);
    }

    public Path getWorkingDirectory(String conversationId) {
        return sessionWorkingDirs.getOrDefault(conversationId, defaultWorkspace);
    }

    public void setWorkingDirectory(String conversationId, Path path) {
        if (path.toFile().exists() && path.toFile().isDirectory()) {
            sessionWorkingDirs.put(conversationId, path.normalize());
            log.info("[WorkingDirectoryManager] 设置会话 {} 工作目录: {}", conversationId, path);
        }
    }

    public String prepareCommand(String conversationId, String command) {
        Path workDir = getWorkingDirectory(conversationId);

        // 检查 cd 命令
        Matcher matcher = CD_PATTERN.matcher(command);
        if (matcher.find()) {
            String target = matcher.group(1).replace("\"", "").replace("'", "");
            try {
                Path newDir = isAbsolutePath(target)
                    ? Paths.get(target)
                    : workDir.resolve(target).normalize();

                if (newDir.toFile().exists() && newDir.toFile().isDirectory()) {
                    sessionWorkingDirs.put(conversationId, newDir);
                }
            } catch (Exception e) {
                log.warn("[WorkingDirectoryManager] cd 解析失败: {}", target);
            }
        }

        // 如果是绝对路径命令，不处理
        if (isAbsolutePath(command.trim())) {
            return command;
        }

        // 注入工作目录
        translator.OsDetector.OsType os = commandTranslator instanceof translator.UnixToWindowsTranslator
            ? translator.OsDetector.OsType.WINDOWS
            : translator.OsDetector.OsType.LINUX;

        if (os == translator.OsDetector.OsType.WINDOWS) {
            return String.format("cd /d \"%s\" && %s", workDir, command);
        } else {
            return String.format("cd \"%s\" && %s", workDir, command);
        }
    }

    private boolean isAbsolutePath(String path) {
        return ABSOLUTE_PATH.matcher(path).find();
    }

    public void clear(String conversationId) {
        sessionWorkingDirs.remove(conversationId);
    }
}
```

---

## 6. BashToolConfig 集成

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
 * <p>提供跨平台的命令执行能力，支持命令风格自动翻译。</p>
 *
 * <h3>功能特性：</h3>
 * <ul>
 *   <li><b>自动翻译</b> - 检测命令风格并翻译到当前系统</li>
 *   <li><b>失败记忆</b> - 避免重复执行已知失败的命令</li>
 *   <li><b>目录管理</b> - 追踪并隔离会话工作目录</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class BashToolConfig {

    @Resource
    private UnifiedTranslator unifiedTranslator;

    @Resource
    private CommandStyleDetector styleDetector;

    @Resource
    private OsDetector osDetector;

    @Resource
    private BashExecutor bashExecutor;

    @Resource
    private BashFailureMemory failureMemory;

    @Resource
    private WorkingDirectoryManager workingDirectoryManager;

    /**
     * 执行 Bash 命令
     *
     * @param command       要执行的命令
     * @param conversationId 会话ID（用于工作目录追踪）
     * @param timeout       超时时间（秒）
     * @return 命令执行结果
     */
    @Tool(name = "bash", description = "Execute bash command. Auto-translates Unix/Windows commands to match the current OS.")
    public String bash(
            @ToolParam(description = "The command to execute") String command,
            @ToolParam(description = "Conversation ID for working directory tracking") String conversationId,
            @ToolParam(description = "Timeout in seconds", defaultValue = "30") Integer timeout
    ) {
        String originalCommand = command;

        // 1. 检查是否应该避免已知失败模式
        if (failureMemory.shouldAvoid(conversationId, command)) {
            String suggestion = failureMemory.getSuggestion(conversationId, command);
            String warning = String.format(
                "⚠️ 检测到已知失败命令模式: %s\n💡 建议: %s",
                command, suggestion != null ? suggestion : "请检查命令是否与当前系统匹配"
            );
            log.warn("[BashToolConfig] {}", warning);
            return warning;
        }

        // 2. 检测命令风格
        CommandStyleDetector.CommandStyle style = styleDetector.detect(command);
        OsDetector.OsType currentOs = osDetector.getCurrentOs();

        // 3. 自动翻译
        String translatedCommand = unifiedTranslator.translate(command);

        // 4. 注入工作目录
        String finalCommand = workingDirectoryManager.prepareCommand(conversationId, translatedCommand);

        // 5. 记录翻译日志
        if (!command.equals(translatedCommand)) {
            log.info("[BashToolConfig] 命令翻译: [{}] {} → [{}] {}",
                style, command, currentOs, finalCommand);
        }

        // 6. 执行命令
        String result;
        try {
            result = bashExecutor.execute(finalCommand, timeout, currentOs);
        } catch (Exception e) {
            log.error("[BashToolConfig] 命令执行异常: {}", e.getMessage());
            return "❌ 命令执行失败: " + e.getMessage();
        }

        // 7. 记录失败模式
        if (result.contains("不是内部或外部命令") || result.contains("not recognized")) {
            failureMemory.recordFailure(conversationId, finalCommand, result);
        }

        // 8. 返回结果（包含翻译说明）
        if (!command.equals(translatedCommand)) {
            String translationInfo = unifiedTranslator.getTranslationInfo(command);
            return translationInfo + "\n\n📝 执行命令: " + finalCommand + "\n\n" + result;
        }

        return result;
    }
}
```

---

## 7. 测试用例

### 7.1 翻译器单元测试

```java
@Nested
@DisplayName("UnixToWindowsTranslator 测试")
class UnixToWindowsTranslatorTest {

    private UnixToWindowsTranslator translator = new UnixToWindowsTranslator();
    private CommandStyleDetector.CommandStyle UNIX = CommandStyleDetector.CommandStyle.UNIX;
    private OsDetector.OsType WINDOWS = OsDetector.OsType.WINDOWS;

    @Test
    @DisplayName("ls → dir")
    void testLsToDir() {
        String result = translator.translate("ls", UNIX, WINDOWS);
        assertEquals("dir", result);
    }

    @Test
    @DisplayName("ls -la → dir /a")
    void testLsLaToDirA() {
        String result = translator.translate("ls -la", UNIX, WINDOWS);
        assertEquals("dir /a", result);
    }

    @Test
    @DisplayName("mkdir -p → mkdir")
    void testMkdirPToMkdir() {
        String result = translator.translate("mkdir -p /c/Users/test", UNIX, WINDOWS);
        assertEquals("mkdir C:\\Users\\test", result);
    }

    @Test
    @DisplayName("which → where")
    void testWhichToWhere() {
        String result = translator.translate("which python", UNIX, WINDOWS);
        assertEquals("where python", result);
    }

    @Test
    @DisplayName("路径 /c/Users → C:\\Users")
    void testUnixPathToWindows() {
        String result = translator.translate("ls /c/Users/aaa", UNIX, WINDOWS);
        assertEquals("dir C:\\Users\\aaa", result);
    }

    @Test
    @DisplayName("$HOME → %USERPROFILE%")
    void testBashVarToCmdVar() {
        String result = translator.translate("echo $HOME", UNIX, WINDOWS);
        assertEquals("echo %USERPROFILE%", result);
    }

    @Test
    @DisplayName("~ → %USERPROFILE%")
    void testTildeToUserProfile() {
        String result = translator.translate("cd ~", UNIX, WINDOWS);
        assertEquals("cd %USERPROFILE%", result);
    }

    @Test
    @DisplayName("Linux 上不翻译")
    void testLinuxNoTranslate() {
        String result = translator.translate("ls -la", UNIX, OsDetector.OsType.LINUX);
        assertEquals("ls -la", result);
    }
}

@Nested
@DisplayName("WindowsToUnixTranslator 测试")
class WindowsToUnixTranslatorTest {

    private WindowsToUnixTranslator translator = new WindowsToUnixTranslator();
    private CommandStyleDetector.CommandStyle WINDOWS = CommandStyleDetector.CommandStyle.WINDOWS;
    private CommandStyleDetector.CommandStyle POWERSHELL = CommandStyleDetector.CommandStyle.POWERSHELL;
    private OsDetector.OsType LINUX = OsDetector.OsType.LINUX;
    private OsDetector.OsType MACOS = OsDetector.OsType.MACOS;

    @Test
    @DisplayName("dir → ls")
    void testDirToLs() {
        String result = translator.translate("dir", WINDOWS, LINUX);
        assertEquals("ls", result);
    }

    @Test
    @DisplayName("dir /a → ls -la")
    void testDirAToLsLa() {
        String result = translator.translate("dir /a", WINDOWS, LINUX);
        assertEquals("ls -la", result);
    }

    @Test
    @DisplayName("C:\\Users → /c/Users")
    void testWindowsPathToUnix() {
        String result = translator.translate("dir C:\\Users\\aaa", WINDOWS, LINUX);
        assertEquals("ls /c/Users/aaa", result);
    }

    @Test
    @DisplayName("%USERPROFILE% → $HOME")
    void testCmdVarToBashVar() {
        String result = translator.translate("cd %USERPROFILE%", WINDOWS, LINUX);
        assertEquals("cd $HOME", result);
    }

    @Test
    @DisplayName("Windows 上不翻译")
    void testWindowsNoTranslate() {
        String result = translator.translate("dir", WINDOWS, OsDetector.OsType.WINDOWS);
        assertEquals("dir", result);
    }
}

@Nested
@DisplayName("CommandStyleDetector 测试")
class CommandStyleDetectorTest {

    private CommandStyleDetector detector = new CommandStyleDetector();

    @Test
    @DisplayName("检测 ls 为 UNIX")
    void testDetectLs() {
        assertEquals(CommandStyleDetector.CommandStyle.UNIX, detector.detect("ls -la"));
    }

    @Test
    @DisplayName("检测 dir 为 WINDOWS")
    void testDetectDir() {
        assertEquals(CommandStyleDetector.CommandStyle.WINDOWS, detector.detect("dir"));
    }

    @Test
    @DisplayName("检测 Get-ChildItem 为 POWERSHELL")
    void testDetectPowerShell() {
        assertEquals(CommandStyleDetector.CommandStyle.POWERSHELL, detector.detect("Get-ChildItem"));
    }

    @Test
    @DisplayName("检测 /home/ 为 UNIX 路径")
    void testDetectUnixPath() {
        assertEquals(CommandStyleDetector.CommandStyle.UNIX, detector.detect("/home/user/file.txt"));
    }

    @Test
    @DisplayName("检测 C:\\ 为 WINDOWS 路径")
    void testDetectWindowsPath() {
        assertEquals(CommandStyleDetector.CommandStyle.WINDOWS, detector.detect("C:\\Users\\file.txt"));
    }
}
```

### 7.2 端到端测试

```java
@SpringBootTest
@DisplayName("命令翻译端到端测试")
class CommandTranslationE2ETest {

    @Resource
    private BashToolConfig bashToolConfig;

    @Test
    @DisplayName("Unix 命令在 Windows 系统应被翻译并成功执行")
    void testUnixCommandOnWindows() {
        // 假设 AI 运行在 Windows 上
        String result = bashToolConfig.bash("ls -la", "test-session", 10);

        // 应该成功（被翻译为 dir /a）
        assertNotNull(result);
        // 不应包含 Unix 特有错误
        assertFalse(result.contains("不是内部或外部命令"));
    }

    @Test
    @DisplayName("失败模式应被记忆并警告")
    void testFailureMemoryWarning() {
        String sessionId = "test-session-" + UUID.randomUUID();

        // 第一次执行 which（会失败）
        bashToolConfig.bash("which unknown_cmd", sessionId, 5);

        // 第二次执行 which（应该被警告）
        String warning = bashToolConfig.bash("which python", sessionId, 5);

        assertTrue(warning.contains("⚠️") || warning.contains("已知失败"));
    }
}
```

---

## 8. 配置项

```yaml
bash:
  workspace: ${user.home}
  translation:
    enabled: true
    auto-detect-os: true
  failure-memory:
    enabled: true
    max-patterns-per-session: 50
  working-directory:
    enabled: true
```

---

## 9. 里程碑

| Phase | 内容 | 产出 |
|-------|------|------|
| **Phase 1** | `OsDetector` + `CommandStyleDetector` | 系统检测、风格检测 |
| **Phase 2** | `UnixToWindowsTranslator` + `WindowsToUnixTranslator` | 双向翻译器 |
| **Phase 3** | `UnifiedTranslator` 协调器 | 统一翻译入口 |
| **Phase 4** | `BashFailureMemory` + `WorkingDirectoryManager` | 辅助功能 |
| **Phase 5** | `BashToolConfig` 集成改造 | 端到端集成 |
| **Phase 6** | 单元测试 + 集成测试 | 质量保证 |

**总工期估算：约 2-3 人天**

---

## 10. 附录：命令对照表

### Unix → Windows

| Unix | Windows CMD | 说明 |
|------|-------------|------|
| `ls` | `dir` | 列出目录 |
| `ls -la` | `dir /a` | 详细列表 |
| `mkdir -p` | `mkdir` | 创建目录 |
| `which` | `where` | 查找命令 |
| `cat` | `type` | 显示文件 |
| `grep` | `findstr` | 文本搜索 |
| `cp` | `copy` | 复制文件 |
| `mv` | `move` | 移动文件 |
| `rm -rf` | `rmdir /s /q` | 删除目录 |
| `touch` | `type NUL >` | 创建空文件 |
| `cd ~` | `cd %USERPROFILE%` | 回家目录 |
| `/c/Users` | `C:\Users` | 路径转换 |
| `$VAR` | `%VAR%` | 变量语法 |
| `&&` | `&` | 命令连接 |
| `/dev/null` | `NUL` | 空设备 |

### Windows → Unix

| Windows CMD | Unix | 说明 |
|-------------|------|------|
| `dir` | `ls` | 列出目录 |
| `dir /a` | `ls -la` | 详细列表 |
| `mkdir` | `mkdir -p` | 创建目录 |
| `where` | `which` | 查找命令 |
| `type` | `cat` | 显示文件 |
| `copy` | `cp` | 复制文件 |
| `move` | `mv` | 移动文件 |
| `del /q` | `rm` | 删除文件 |
| `rmdir /s /q` | `rm -rf` | 删除目录 |
| `C:\Users` | `/c/Users` | 路径转换 |
| `%USERPROFILE%` | `$HOME` | 用户目录 |
| `NUL` | `/dev/null` | 空设备 |
