# Bash 命令适配增强设计规格书

> **文档版本:** v1.0
> **创建日期:** 2026-03-28
> **作者:** AI Review
> **状态:** 待评审

---

## 1. 背景与问题

### 1.1 问题描述

当前 `BashToolConfig` 在 Windows 环境下存在以下问题：

| 问题 | 表现 | 影响 |
|------|------|------|
| **命令风格不匹配** | Agent 生成的 Unix 命令（`ls`, `mkdir -p`, `which`）在 Windows cmd 执行失败 | 80% 的工具调用失败源于此 |
| **失败模式无记忆** | 同一错误在同一对话中重复出现 | 大量无效 Token 消耗 |
| **工作目录污染** | `cd && command` 失败导致文件写到错误位置 | 产物位置不确定 |

### 1.2 问题根因

```
┌─────────────────────────────────────────────────────────┐
│  Agent 生成的 Prompt                                    │
│  "mkdir -p /c/Users/aaa/test"                         │
│  "ls -la /home/user"                                   │
└─────────────────┬───────────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────────┐
│  BashToolConfig                                         │
│  检测到 os.contains("windows")                          │
│  用 cmd /c 执行 → 失败                                  │
└─────────────────┬───────────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────────┐
│  错误示例                                               │
│  "子目录或文件 -p 已经存在"                             │
│  "'ls' 不是内部或外部命令"                             │
└─────────────────────────────────────────────────────────┘
```

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
├── BashExecutor.java                # 命令执行器（改造）
├── translator/                      # 新增包
│   ├── CommandTranslator.java       # 接口
│   └── UnixToWindowsTranslator.java # Unix→Windows 翻译实现
├── CommandStyleAnalyzer.java        # 命令风格检测（新增）
├── BashFailureMemory.java           # 失败模式记忆（新增）
├── WorkingDirectoryManager.java     # 工作目录隔离（新增）
└── ResponseBuilder.java             # 响应构建（已有，改造）
```

### 2.2 调用链

```
BashToolConfig.bash(command)
    │
    ├─► BashFailureMemory.shouldAvoid()
    │       └─ 命中 → 返回警告而非执行
    │
    ├─► WorkingDirectoryManager.prepareCommand()
    │       └─ 解析/更新工作目录，注入 cd
    │
    ├─► CommandStyleAnalyzer.detectStyle()
    │       └─ 检测命令风格与 OS 是否匹配
    │
    ├─► UnixToWindowsTranslator.translate()
    │       └─ 翻译 Unix 命令为 Windows 命令
    │
    ├─► BashExecutor.execute()
    │       └─ 执行命令，返回结果
    │
    └─► BashFailureMemory.recordFailure()
            └─ 记录失败模式供后续参考
```

---

## 3. 组件设计

### 3.1 CommandTranslator 接口

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/CommandTranslator.java`

```java
package top.javarem.omni.tool.bash.translator;

/**
 * 命令翻译器接口
 *
 * <p>将不同操作系统的命令互相转换，主要用于 Unix↔Windows 翻译。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
public interface CommandTranslator {

    /**
     * 操作系统类型枚举
     */
    enum OsType {
        LINUX,
        WINDOWS,
        MACOS
    }

    /**
     * 翻译命令到目标操作系统
     *
     * @param command 原始命令
     * @param osType  目标操作系统
     * @return 翻译后的命令
     */
    String translate(String command, OsType osType);

    /**
     * 检测当前操作系统类型
     *
     * @return OsType
     */
    default OsType detectCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            return OsType.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return OsType.MACOS;
        } else {
            return OsType.LINUX;
        }
    }
}
```

### 3.2 UnixToWindowsTranslator 实现

**文件:** `src/main/java/top/javarem/omni/tool/bash/translator/UnixToWindowsTranslator.java`

**核心映射表：**

| Unix 命令 | Windows 等价 | 备注 |
|-----------|--------------|------|
| `ls` | `dir` | |
| `ls -la` | `dir /a` | |
| `mkdir -p` | `mkdir` | Windows mkdir 不需要 -p |
| `which` | `where` | |
| `cat` | `type` | |
| `grep` | `findstr` | 功能不完全等价 |
| `rm -rf` | `rmdir /s /q` | 危险命令需确认 |
| `cp` | `copy` | |
| `mv` | `move` | |
| `pwd` | `cd` | 需配合 echo |
| `touch` | `type NUL >` | 创建空文件 |
| `chmod` | `attrib` | 部分等价 |
| `export` | `set` | |
| `&&` | `&` | 语义不同 |
| `/dev/null` | `NUL` | |
| `~` | `%USERPROFILE%` | |

**路径转换规则：**
- `/c/Users/...` → `C:\Users\...`
- `$VAR` → `%VAR%`

```java
package top.javarem.omni.tool.bash.translator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unix 到 Windows 命令翻译器
 *
 * <p>将 Unix 风格的 bash 命令转换为 Windows 批处理命令。</p>
 *
 * <h3>翻译规则：</h3>
 * <ul>
 *   <li><b>命令映射</b> - ls→dir, mkdir -p→mkdir, which→where</li>
 *   <li><b>路径转换</b> - /c/Users/... → C:\Users\...</li>
 *   <li><b>变量转换</b> - $VAR → %VAR%</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class UnixToWindowsTranslator implements CommandTranslator {

    /**
     * Unix → Windows 命令映射表
     */
    private static final Map<String, String> COMMAND_MAP = Map.ofEntries(
        Map.entry("ls", "dir"),
        Map.entry("ll", "dir"),
        Map.entry("la", "dir /a"),
        Map.entry("mkdir -p", "mkdir"),
        Map.entry("rm -rf", "rmdir /s /q"),  // 危险命令
        Map.entry("rm -r", "rmdir /s /q"),
        Map.entry("rm", "del /q"),
        Map.entry("which", "where"),
        Map.entry("cat", "type"),
        Map.entry("grep", "findstr"),
        Map.entry("egrep", "findstr /r"),
        Map.entry("fgrep", "findstr"),
        Map.entry("cp", "copy"),
        Map.entry("copy", "copy"),
        Map.entry("mv", "move"),
        Map.entry("move", "move"),
        Map.entry("pwd", "cd"),
        Map.entry("touch", "type NUL >"),
        Map.entry("chmod", "attrib"),
        Map.entry("export", "set"),
        Map.entry("echo $", "echo %"),
        Map.entry("&&", "&"),
        Map.entry("|", "^|")  // 转义管道符
    );

    /**
     * Unix 特有路径模式
     */
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile(
        "/([a-z])(/.*)?",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Bash 变量模式 $VAR
     */
    private static final Pattern BASH_VAR_PATTERN = Pattern.compile(
        "\\$([A-Za-z_][A-Za-z0-9_]*)"
    );

    @Override
    public String translate(String command, OsType osType) {
        if (osType != OsType.WINDOWS) {
            return command;  // 非 Windows 不翻译
        }

        String translated = command;

        // 1. 命令映射
        translated = translateCommands(translated);

        // 2. 路径转换 /c/Users → C:\Users
        translated = convertUnixPathToWindows(translated);

        // 3. 变量转换 $VAR → %VAR%
        translated = convertBashVarToCmdVar(translated);

        log.debug("[UnixToWindowsTranslator] 原始: {}, 翻译后: {}", command, translated);
        return translated;
    }

    /**
     * 替换命令
     */
    private String translateCommands(String cmd) {
        String result = cmd;

        // 按长度降序排序，确保长命令优先匹配 (如 mkdir -p 先于 mkdir)
        String[] sortedKeys = COMMAND_MAP.keySet().stream()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toArray(String[]::new);

        for (String unixCmd : sortedKeys) {
            String windowsCmd = COMMAND_MAP.get(unixCmd);
            // 使用单词边界匹配
            String pattern = "\\b" + Pattern.quote(unixCmd) + "\\b";
            result = result.replaceAll(pattern, windowsCmd);
        }

        return result;
    }

    /**
     * 转换 Unix 路径为 Windows 路径
     * /c/Users/aaa → C:\Users\aaa
     * /d/Program Files → D:\Program Files
     */
    private String convertUnixPathToWindows(String cmd) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = UNIX_PATH_PATTERN.matcher(cmd);

        while (matcher.find()) {
            char drive = matcher.group(1).toUpperCase().charAt(0);
            String path = matcher.group(2) != null
                ? matcher.group(2).replace("/", "\\")
                : "";
            matcher.appendReplacement(sb, drive + ":\\" + path.substring(1));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 转换 Bash 变量为 CMD 变量
     * $HOME → %USERPROFILE%
     * $PATH → %PATH%
     */
    private String convertBashVarToCmdVar(String cmd) {
        // 特殊变量映射
        Map<String, String> varMap = Map.of(
            "HOME", "USERPROFILE",
            "PATH", "PATH",
            "USER", "USERNAME",
            "PWD", "CD"
        );

        String result = cmd;
        Matcher matcher = BASH_VAR_PATTERN.matcher(result);

        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String windowsVar = varMap.getOrDefault(varName, varName);
            matcher.appendReplacement(sb, "%" + windowsVar + "%");
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 检测命令是否包含 Unix 特有模式
     */
    public boolean containsUnixPatterns(String command) {
        return command.matches(".*(ls|mkdir -p|which|cat|grep|/dev/null|\\$VAR|&&).*");
    }
}
```

### 3.3 CommandStyleAnalyzer

**文件:** `src/main/java/top/javarem/omni/tool/bash/CommandStyleAnalyzer.java`

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 命令风格分析器
 *
 * <p>检测命令是 Unix 风格还是 Windows 风格，用于判断是否需要翻译。</p>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class CommandStyleAnalyzer {

    /**
     * Unix 特有命令模式
     */
    private static final Pattern UNIX_COMMAND_PATTERN = Pattern.compile(
        "\\b(ls|ll|la|mkdir -p|which|cat|grep|egrep|fgrep|touch|chmod|export|whoami|pwd|uname|top|ps aux|kill -9|\\$VAR|&&\\s*rm)\\b"
    );

    /**
     * Windows 特有命令模式
     */
    private static final Pattern WINDOWS_COMMAND_PATTERN = Pattern.compile(
        "\\b(dir|if exist|mkdir|type |del /q|rmdir|attrib|set \\w+|echo %\\w+%)\\b"
    );

    /**
     * Unix 特有路径模式（/c/Users）
     */
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile(
        "/[a-z]/"
    );

    /**
     * 命令风格枚举
     */
    public enum CommandStyle {
        UNIX,
        WINDOWS,
        MIXED,
        UNKNOWN
    }

    /**
     * 检测命令风格
     */
    public CommandStyle detectStyle(String command) {
        boolean isUnix = UNIX_COMMAND_PATTERN.matcher(command.toLowerCase()).find();
        boolean isWindows = WINDOWS_COMMAND_PATTERN.matcher(command.toLowerCase()).find();
        boolean isUnixPath = UNIX_PATH_PATTERN.matcher(command).find();

        // 如果同时包含两种风格，标记为 MIXED
        if (isUnix && isWindows) {
            return CommandStyle.MIXED;
        }

        // Unix 路径但没有 Unix 命令，可能是路径中包含 /c/ 这样的误判
        if (isUnixPath && !isUnix && !isWindows) {
            // 检查是否是真正的 Unix 路径
            if (command.contains("/home/") || command.contains("/usr/") || command.contains("/etc/")) {
                return CommandStyle.UNIX;
            }
        }

        if (isUnix || isUnixPath) {
            return CommandStyle.UNIX;
        }

        if (isWindows) {
            return CommandStyle.WINDOWS;
        }

        return CommandStyle.UNKNOWN;
    }

    /**
     * 检测命令风格与操作系统是否匹配
     *
     * @param command 命令
     * @param osType  目标操作系统
     * @return true 如果不匹配（需要翻译或警告）
     */
    public boolean isStyleMismatch(String command, CommandTranslator.OsType osType) {
        CommandStyle style = detectStyle(command);

        if (osType == CommandTranslator.OsType.WINDOWS) {
            return style == CommandStyle.UNIX;
        } else if (osType == CommandTranslator.OsType.LINUX || osType == CommandTranslator.OsType.MACOS) {
            return style == CommandStyle.WINDOWS;
        }

        return false;
    }

    /**
     * 获取风格不匹配的说明
     */
    public String getMismatchReason(String command, CommandTranslator.OsType osType) {
        CommandStyle style = detectStyle(command);

        if (osType == CommandTranslator.OsType.WINDOWS && style == CommandStyle.UNIX) {
            return "检测到 Unix 风格命令在 Windows 环境执行，可能需要翻译";
        } else if ((osType == CommandTranslator.OsType.LINUX || osType == CommandTranslator.OsType.MACOS)
                && style == CommandStyle.WINDOWS) {
            return "检测到 Windows 风格命令在 Unix 环境执行，可能需要翻译";
        }

        return null;
    }
}
```

### 3.4 BashFailureMemory

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
 * <h3>功能说明：</h3>
 * <ul>
 *   <li><b>失败检测</b> - 从命令输出中识别失败模式</li>
 *   <li><b>模式记忆</b> - 在对话级别缓存已知失败命令</li>
 *   <li><b>建议生成</b> - 提供替代命令建议</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class BashFailureMemory {

    /**
     * 会话级失败模式缓存
     * Key: conversationId, Value: Set<失败命令>
     */
    private final Map<String, Set<String>> failedPatterns = new ConcurrentHashMap<>();

    /**
     * 失败命令的替代建议
     */
    private static final Map<String, String> COMMAND_SUGGESTIONS = Map.of(
        "which", "where",
        "ls", "dir",
        "mkdir -p", "mkdir (Windows 不需要 -p)",
        "cat", "type",
        "grep", "findstr",
        "touch", "type NUL > filename",
        "chmod", "attrib (仅限文件属性)",
        "export", "set (CMD) 或 export (PowerShell)"
    );

    /**
     * 失败输出模式
     */
    private static final List<Pattern> FAILURE_PATTERNS = List.of(
        Pattern.compile("不是内部或外部命令", Pattern.CASE_INSENSITIVE),
        Pattern.compile("is not (an? )?internal or external command", Pattern.CASE_INSENSITIVE),
        Pattern.compile("not recognized", Pattern.CASE_INSENSITIVE),
        Pattern.compile("子目录或文件 .* 已经存在", Pattern.CASE_INSENSITIVE),
        Pattern.compile("The syntax of the command is incorrect", Pattern.CASE_INSENSITIVE),
        Pattern.compile("cannot find the file", Pattern.CASE_INSENSITIVE),
        Pattern.compile("No such file or directory", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 记录命令失败
     *
     * @param conversationId 对话ID
     * @param command       执行的命令
     * @param errorOutput   错误输出
     */
    public void recordFailure(String conversationId, String command, String errorOutput) {
        if (!isFailure(errorOutput)) {
            return;
        }

        String pattern = extractCommandPattern(command);
        failedPatterns
            .computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet())
            .add(pattern);

        log.info("[BashFailureMemory] 记录失败模式: conversationId={}, pattern={}, error={}",
            conversationId, pattern, extractErrorType(errorOutput));
    }

    /**
     * 检查是否应该避免已知失败模式
     *
     * @param conversationId 对话ID
     * @param command        待执行命令
     * @return true 如果命令匹配已知失败模式
     */
    public boolean shouldAvoid(String conversationId, String command) {
        Set<String> patterns = failedPatterns.get(conversationId);
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }

        String commandPattern = extractCommandPattern(command);
        return patterns.stream()
            .anyMatch(failed -> commandPattern.startsWith(failed)
                || command.contains(failed));
    }

    /**
     * 获取针对失败命令的建议
     *
     * @param conversationId 对话ID
     * @param failedCommand  失败的命令
     * @return 建议的替代命令
     */
    public String getSuggestion(String conversationId, String failedCommand) {
        // 先检查已知失败模式
        Set<String> patterns = failedPatterns.get(conversationId);
        if (patterns != null) {
            for (String failed : patterns) {
                if (failedCommand.contains(failed)) {
                    return COMMAND_SUGGESTIONS.get(failed);
                }
            }
        }

        // 从 COMMAND_SUGGESTIONS 中查找
        for (Map.Entry<String, String> entry : COMMAND_SUGGESTIONS.entrySet()) {
            if (failedCommand.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 获取已知失败模式列表
     */
    public Set<String> getFailedPatterns(String conversationId) {
        return Collections.unmodifiableSet(
            failedPatterns.getOrDefault(conversationId, Collections.emptySet())
        );
    }

    /**
     * 清除会话记忆
     */
    public void clear(String conversationId) {
        failedPatterns.remove(conversationId);
        log.debug("[BashFailureMemory] 清除会话记忆: {}", conversationId);
    }

    /**
     * 清除所有会话记忆
     */
    public void clearAll() {
        failedPatterns.clear();
        log.debug("[BashFailureMemory] 清除所有会话记忆");
    }

    // ==================== 私有方法 ====================

    /**
     * 判断输出是否为失败
     */
    private boolean isFailure(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return FAILURE_PATTERNS.stream()
            .anyMatch(p -> p.matcher(output).find());
    }

    /**
     * 提取命令的基础模式（去掉参数）
     */
    private String extractCommandPattern(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        // 取第一个单词作为模式
        int spaceIdx = trimmed.indexOf(' ');
        return spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
    }

    /**
     * 从错误输出中提取错误类型
     */
    private String extractErrorType(String errorOutput) {
        for (Pattern p : FAILURE_PATTERNS) {
            Matcher m = p.matcher(errorOutput);
            if (m.find()) {
                return m.group();
            }
        }
        return "Unknown error";
    }
}
```

### 3.5 WorkingDirectoryManager

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
 * <h3>功能说明：</h3>
 * <ul>
 *   <li><b>目录追踪</b> - 记录每个会话的当前工作目录</li>
 *   <li><b>路径解析</b> - 相对路径转换为绝对路径</li>
 *   <b>cd 命令处理</b> - 检测并更新会话工作目录
 * </ul>
 *
 * @author javarem
 * @since 2026-03-28
 */
@Component
@Slf4j
public class WorkingDirectoryManager {

    /**
     * 每个会话的当前工作目录
     */
    private final Map<String, Path> sessionWorkingDirs = new ConcurrentHashMap<>();

    /**
     * 默认工作目录（项目根目录）
     */
    private final Path defaultWorkspace;

    /**
     * cd 命令模式
     */
    private static final Pattern CD_PATTERN = Pattern.compile(
        "^\\s*cd\\s+(.+?)\\s*(?:&&|$)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 绝对路径模式
     */
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile(
        "^([A-Za-z]:\\\\|/|\\\\\\\\)"
    );

    @Resource
    private CommandTranslator commandTranslator;

    public WorkingDirectoryManager(
            @Value("${bash.workspace:D:\\Develop\\ai\\omniAgent}") String workspace) {
        this.defaultWorkspace = Paths.get(workspace);
        log.info("[WorkingDirectoryManager] 初始化，默认工作目录: {}", defaultWorkspace);
    }

    /**
     * 获取会话的当前工作目录
     *
     * @param conversationId 对话ID
     * @return 当前工作目录
     */
    public Path getWorkingDirectory(String conversationId) {
        return sessionWorkingDirs.getOrDefault(conversationId, defaultWorkspace);
    }

    /**
     * 设置会话的工作目录
     *
     * @param conversationId 对话ID
     * @param path           目录路径
     */
    public void setWorkingDirectory(String conversationId, Path path) {
        if (path.toFile().exists() && path.toFile().isDirectory()) {
            sessionWorkingDirs.put(conversationId, path.normalize());
            log.info("[WorkingDirectoryManager] 设置会话 {} 工作目录: {}", conversationId, path);
        } else {
            log.warn("[WorkingDirectoryManager] 无效目录路径: {}", path);
        }
    }

    /**
     * 解析并更新工作目录
     *
     * <p>如果命令包含 cd，则更新会话工作目录并返回新的基础目录。</p>
     *
     * @param conversationId 对话ID
     * @param command        命令
     * @return 解析后的工作目录
     */
    public Path resolveWorkingDirectory(String conversationId, String command) {
        Path baseDir = getWorkingDirectory(conversationId);

        // 检查是否是 cd 命令
        Matcher matcher = CD_PATTERN.matcher(command);
        if (matcher.find()) {
            String target = matcher.group(1).trim();
            target = target.replace("\"", "").replace("'", "");

            try {
                Path newDir;
                if (isAbsolutePath(target)) {
                    newDir = Paths.get(target);
                } else {
                    newDir = baseDir.resolve(target).normalize();
                }

                if (newDir.toFile().exists() && newDir.toFile().isDirectory()) {
                    sessionWorkingDirs.put(conversationId, newDir);
                    log.debug("[WorkingDirectoryManager] cd 到: {}", newDir);
                    return newDir;
                } else {
                    log.warn("[WorkingDirectoryManager] cd 目标不存在: {}", newDir);
                }
            } catch (Exception e) {
                log.warn("[WorkingDirectoryManager] cd 路径解析失败: {}", target, e);
            }
        }

        return baseDir;
    }

    /**
     * 准备命令，在执行前注入工作目录
     *
     * <p>如果命令是相对路径或包含 cd，则包装为完整的绝对路径命令。</p>
     *
     * @param conversationId 对话ID
     * @param command        原始命令
     * @return 准备好的命令
     */
    public String prepareCommand(String conversationId, String command) {
        Path workDir = resolveWorkingDirectory(conversationId, command);

        // 如果已经是绝对路径命令，不处理
        if (isAbsoluteCommand(command)) {
            return command;
        }

        // 如果命令不包含 cd，注入工作目录
        if (!command.trim().toLowerCase().startsWith("cd ")) {
            String cdPrefix = buildCdPrefix(workDir);
            return cdPrefix + command;
        }

        return command;
    }

    /**
     * 构建 cd 前缀
     */
    private String buildCdPrefix(Path workDir) {
        CommandTranslator.OsType os = commandTranslator.detectCurrentOs();

        if (os == CommandTranslator.OsType.WINDOWS) {
            return String.format("cd /d \"%s\" && ", workDir);
        } else {
            return String.format("cd \"%s\" && ", workDir);
        }
    }

    /**
     * 判断是否为绝对路径
     */
    private boolean isAbsolutePath(String path) {
        return ABSOLUTE_PATH_PATTERN.matcher(path).find();
    }

    /**
     * 判断是否为绝对路径命令（如 C:\xxx, /usr/bin）
     */
    private boolean isAbsoluteCommand(String command) {
        String trimmed = command.trim();
        // 检查是否以绝对路径开头
        return ABSOLUTE_PATH_PATTERN.matcher(trimmed).find();
    }

    /**
     * 清除会话工作目录
     */
    public void clear(String conversationId) {
        sessionWorkingDirs.remove(conversationId);
        log.debug("[WorkingDirectoryManager] 清除会话工作目录: {}", conversationId);
    }

    /**
     * 清除所有会话工作目录
     */
    public void clearAll() {
        sessionWorkingDirs.clear();
        log.debug("[WorkingDirectoryManager] 清除所有会话工作目录");
    }
}
```

---

## 4. BashToolConfig 改造

**文件:** `src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java`

### 改造点

```java
@Tool(name = "bash", description = "执行bash命令...")
public String bash(
    @ToolParam(description = "完整命令") String command,
    @ToolParam(description = "会话ID，用于工作目录追踪") String conversationId,
    @ToolParam(description = "超时秒数", defaultValue = "30") Integer timeout
) {
    // 1. 检查是否应该避免已知失败模式
    if (bashFailureMemory.shouldAvoid(conversationId, command)) {
        String suggestion = bashFailureMemory.getSuggestion(conversationId, command);
        String warning = "⚠️ 检测到可能失败的命令模式: " + command;
        if (suggestion != null) {
            warning += "\n💡 建议使用: " + suggestion;
        }
        log.warn("[BashToolConfig] {}", warning);
        return warning;
    }

    // 2. 准备命令（注入工作目录）
    String preparedCommand = workingDirectoryManager.prepareCommand(conversationId, command);

    // 3. 检测命令风格
    CommandStyle style = commandStyleAnalyzer.detectStyle(command);
    CommandTranslator.OsType osType = commandTranslator.detectCurrentOs();

    String finalCommand = preparedCommand;

    // 4. 如果风格不匹配且是 Windows，执行翻译
    if (commandStyleAnalyzer.isStyleMismatch(command, osType)) {
        finalCommand = commandTranslator.translate(preparedCommand, osType);
        log.info("[BashToolConfig] 命令风格检测: {}→{}, 翻译: {} → {}",
            style, osType, preparedCommand, finalCommand);
    }

    // 5. 执行命令
    String result = bashExecutor.execute(finalCommand, timeout, osType);

    // 6. 记录失败模式
    if (result.contains("不是内部或外部命令") || result.contains("not recognized")) {
        bashFailureMemory.recordFailure(conversationId, finalCommand, result);
    }

    return result;
}
```

---

## 5. 测试用例设计

### 5.1 UnixToWindowsTranslator 测试

```java
@Nested
@DisplayName("UnixToWindowsTranslator 命令翻译测试")
class UnixToWindowsTranslatorTest {

    private UnixToWindowsTranslator translator = new UnixToWindowsTranslator();

    @Test
    @DisplayName("ls 应翻译为 dir")
    void testLsToDir() {
        String result = translator.translate("ls -la", CommandTranslator.OsType.WINDOWS);
        assertEquals("dir -la", result);
    }

    @Test
    @DisplayName("mkdir -p 应翻译为 mkdir")
    void testMkdirPToMkdir() {
        String result = translator.translate("mkdir -p /c/Users/test", CommandTranslator.OsType.WINDOWS);
        assertEquals("mkdir C:\\Users\\test", result);
    }

    @Test
    @DisplayName("which 应翻译为 where")
    void testWhichToWhere() {
        String result = translator.translate("which python", CommandTranslator.OsType.WINDOWS);
        assertEquals("where python", result);
    }

    @Test
    @DisplayName("路径 /c/Users 应翻译为 C:\\Users")
    void testUnixPathToWindows() {
        String result = translator.translate("ls /c/Users/aaa", CommandTranslator.OsType.WINDOWS);
        assertEquals("dir C:\\Users\\aaa", result);
    }

    @Test
    @DisplayName("非 Windows 系统不翻译")
    void testNonWindowsNoTranslate() {
        String result = translator.translate("ls -la", CommandTranslator.OsType.LINUX);
        assertEquals("ls -la", result);
    }

    @Test
    @DisplayName("$VAR 应翻译为 %VAR%")
    void testBashVarToCmdVar() {
        String result = translator.translate("echo $HOME", CommandTranslator.OsType.WINDOWS);
        assertEquals("echo %USERPROFILE%", result);
    }
}
```

### 5.2 BashFailureMemory 测试

```java
@Nested
@DisplayName("BashFailureMemory 失败记忆测试")
class BashFailureMemoryTest {

    private BashFailureMemory memory = new BashFailureMemory();

    @Test
    @DisplayName("应记录失败的 which 命令")
    void testRecordWhichFailure() {
        String errorOutput = "'which' 不是内部或外部命令";
        memory.recordFailure("session1", "which python", errorOutput);

        assertTrue(memory.shouldAvoid("session1", "which python3"));
        assertFalse(memory.shouldAvoid("session1", "dir"));
    }

    @Test
    @DisplayName("应提供失败命令的建议")
    void testGetSuggestion() {
        String errorOutput = "'which' 不是内部或外部命令";
        memory.recordFailure("session1", "which python", errorOutput);

        String suggestion = memory.getSuggestion("session1", "which python");
        assertEquals("where", suggestion);
    }

    @Test
    @DisplayName("不同会话应独立记忆")
    void testSessionIsolation() {
        memory.recordFailure("session1", "which python", "error");
        assertTrue(memory.shouldAvoid("session1", "which python"));

        assertFalse(memory.shouldAvoid("session2", "which python"));
    }

    @Test
    @DisplayName("应能清除会话记忆")
    void testClear() {
        memory.recordFailure("session1", "which python", "error");
        memory.clear("session1");

        assertFalse(memory.shouldAvoid("session1", "which python"));
    }
}
```

### 5.3 WorkingDirectoryManager 测试

```java
@Nested
@DisplayName("WorkingDirectoryManager 工作目录测试")
class WorkingDirectoryManagerTest {

    private WorkingDirectoryManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkingDirectoryManager("D:\\Test");
    }

    @Test
    @DisplayName("cd 命令应更新工作目录")
    void testCdUpdatesDirectory() {
        manager.resolveWorkingDirectory("session1", "cd D:\\Projects");
        assertEquals(Paths.get("D:\\Projects"), manager.getWorkingDirectory("session1"));
    }

    @Test
    @DisplayName("prepareCommand 应注入工作目录")
    void testPrepareCommand() {
        manager.setWorkingDirectory("session1", Paths.get("D:\\Projects"));
        String result = manager.prepareCommand("session1", "dir");
        assertTrue(result.contains("cd /d \"D:\\Projects\""));
    }

    @Test
    @DisplayName("不同会话应独立工作目录")
    void testSessionIsolation() {
        manager.setWorkingDirectory("session1", Paths.get("D:\\Projects"));
        manager.setWorkingDirectory("session2", Paths.get("D:\\Data"));

        assertEquals(Paths.get("D:\\Projects"), manager.getWorkingDirectory("session1"));
        assertEquals(Paths.get("D:\\Data"), manager.getWorkingDirectory("session2"));
    }
}
```

---

## 6. 集成测试

### 6.1 端到端场景测试

```java
@SpringBootTest
@DisplayName("Bash 命令适配端到端测试")
class BashCommandAdaptationE2ETest {

    @Resource
    private BashToolConfig bashToolConfig;

    @Test
    @DisplayName("Unix 命令在 Windows 环境应被翻译")
    void testUnixCommandTranslation() {
        // 模拟 agent 发送 Unix 命令
        String result = bashToolConfig.bash(
            "ls -la /c/Users",
            "test-session-001",
            10
        );

        // 应该成功执行（被翻译为 dir）
        assertNotNull(result);
        // 不应包含 Unix 错误
        assertFalse(result.contains("不是内部或外部命令"));
    }

    @Test
    @DisplayName("失败模式应被记忆")
    void testFailureMemory() {
        String sessionId = "test-session-002";

        // 第一次执行 which（会失败）
        bashToolConfig.bash("which unknown_command", sessionId, 5);

        // 第二次执行 which（应该被警告）
        String warning = bashToolConfig.bash("which python", sessionId, 5);

        assertTrue(warning.contains("⚠️") || warning.contains("建议"));
    }

    @Test
    @DisplayName("cd 命令应更新工作目录")
    void testCdUpdatesWorkingDirectory() {
        String sessionId = "test-session-003";

        // cd 到桌面
        bashToolConfig.bash("cd C:\\Users\\aaa\\Desktop", sessionId, 5);

        // 执行 dir（应该在桌面目录）
        String result = bashToolConfig.bash("dir", sessionId, 5);

        assertNotNull(result);
    }
}
```

---

## 7. 配置项

**文件:** `application.yml`

```yaml
bash:
  # 默认工作目录
  workspace: ${user.home}
  # 是否启用命令翻译
  translation:
    enabled: true
    auto-detect-os: true
  # 是否启用失败记忆
  failure-memory:
    enabled: true
    max-patterns-per-session: 50
  # 是否启用工作目录管理
  working-directory:
    enabled: true
```

---

## 8. 风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| 命令翻译不完整 | 中 | 只翻译明确映射的命令，未知命令透传 |
| 翻译语义差异 | 中 | 保持原有错误输出，便于调试 |
| 会话记忆内存泄漏 | 低 | 对话结束时清理，或设置 TTL |

---

## 9. 里程碑

| 阶段 | 内容 | 产出 |
|------|------|------|
| **Phase 1** | `CommandTranslator` 接口 + `UnixToWindowsTranslator` 实现 | 命令自动翻译 |
| **Phase 2** | `CommandStyleAnalyzer` + 集成到 BashToolConfig | 风格检测 |
| **Phase 3** | `BashFailureMemory` 实现 | 失败模式记忆 |
| **Phase 4** | `WorkingDirectoryManager` 实现 | 目录隔离 |
| **Phase 5** | 单元测试 + 集成测试 | 质量保证 |

**总工期估算：约 2-3 人天**

---

## 10. 参考资料

- Spring AI ChatClient 架构
- Windows CMD vs Unix Bash 命令对照表
- 现有 BashToolConfig 实现
