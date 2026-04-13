package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DangerousPatternValidator {

    // sed 严格白名单：仅允许 s/pattern/replacement/flags 格式
    // flags 限定为: g(全局), p(打印), i/I(忽略大小写), m/M(多行), 1-9(第N次匹配)
    // 禁止: -i(原地编辑), -e(表达式), -f(脚本文件), w(写入文件), W(写入行号)
    //       a/i/c/d(追加/插入/更改/删除行), y/(翻译), =(行号), q(退出), D(删除直到\n)
    private static final Pattern SED_SAFE_PATTERN = Pattern.compile(
        "(?i)^\\s*sed\\s+['\"]s/.+?/.*?/[gipImM1-9]*['\"](\\s+\\S+)*(\\s+#[^\\n]*)?\\s*$"
    );

    private static final Pattern SED_DANGEROUS_PATTERN = Pattern.compile(
        "(?i)^\\s*sed\\s+.*(-[iwedrqDG]|--version|--help|\\ba\\b|\\bi\\b|\\bc\\b|\\bd\\b|\\by\\b|\\b=\\b|\\b1,|\\b\\$\\b)"
    );

    private static final String[] DIRECT_DENY_PATTERNS = {
        "rm\\s+-rf\\s+/\\s*\\*?\\s*$",
        "rm\\s+-rf\\s+/\\s",
        "\\bmkfs\\b",
        ":\\s*\\(\\s*\\)\\s*\\{.*:.*\\|.*:.*\\&.*\\}.*",
        "fork\\s*bomb"
    };

    private static final String[] REQUIRES_APPROVAL_PATTERNS = {
        "\\brm\\s+-rf\\b",
        "\\bchmod\\s+777\\b",
        "\\bdd\\s+if\\b"
    };

    // 破坏性命令：警告但不阻止（WARNING 级别）
    // 注意：rm -rf, chmod 777, dd if 在 REQUIRES_APPROVAL_PATTERNS 中已处理
    private static final String[] DESTRUCTIVE_WARNING_PATTERNS = {
        "\\bgit\\s+reset\\s+--hard\\b",
        "\\bgit\\s+push\\s+--force\\b",
        "\\bgit\\s+push\\s+-f\\b",
        "\\bkubectl\\s+delete\\b",
        "\\bkubectl\\s+apply\\s+--force\\b",
        "\\bdocker\\s+rm\\s+-f\\b",
        "\\bdocker\\s+rmi\\s+-f\\b",
        "\\bdocker\\s+volume\\s+rm\\b",
        "\\bdocker\\s+system\\s+prune\\b",
        "\\bterraform\\s+destroy\\b",
        "\\bterraform\\s+apply\\s+--destroy\\b",
        "\\bhelm\\s+uninstall\\b",
        "\\bhelm\\s+delete\\b",
        "\\bk3s\\s+uninstall\\b",
        // Windows 危险命令（去除末尾 \\b，允许 flags）
        "\\bdel\\s+/[sqf]\\b",
        "\\brmdir\\s+/s\\b",
        "\\bformat\\s+[a-z]:",
        // 数据库危险操作
        "\\bDROP\\s+(TABLE|DATABASE|INDEX|VIEW)\\b",
        "\\bTRUNCATE\\s+TABLE\\b",
        "\\bDELETE\\s+FROM\\s+\\w+\\s+WHERE\\s+1=1\\b",
        "\\bALTER\\s+TABLE\\s+\\w+\\s+DROP\\b"
    };

    private static final char[] INJECTION_SYMBOLS = {';', '|', '&'};

    private final Set<String> allowedCommands;

    /**
     * 构造函数 - 由 Spring 调用，注入 approved-commands.properties
     */
    public DangerousPatternValidator(@Value("classpath:approved-commands.properties") Resource resource) {
        this.allowedCommands = new HashSet<>();
        try {
            for (String line : Files.readAllLines(resource.getFile().toPath())) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    allowedCommands.add(trimmed);
                }
            }
            log.info("[DangerousPatternValidator] loaded {} approved commands", allowedCommands.size());
        } catch (Exception e) {
            log.error("[DangerousPatternValidator] failed to load approved-commands.properties", e);
        }
    }

    public enum Result { ALLOW, DENY, REQUIRE_APPROVAL, WARNING }

    public Result validate(String command) {
        if (command == null || command.isBlank()) return Result.DENY;

        String trimmed = command.trim();

        // ============================================================
        // 第零层：sed 命令严格验证
        // 允许: sed 's/old/new/flags' (仅 s 命令，flags 受限)
        // 拒绝: sed -i, sed -e, sed -f, sed a/i/c/d/y 等危险操作
        // ============================================================
        Result sedResult = validateSed(trimmed);
        if (sedResult != null) {
            return sedResult;
        }



        // ============================================================
        // 第一层：命令链中的危险模式检测
        // 命令链（|, ;, && 等）需单独检测每个危险片段
        // 注意：REQUIRES_APPROVAL_PATTERNS 不适用于命令链
        // ============================================================
        if (hasCommandChainingOutsideQuotes(trimmed)) {
            String[] segments = trimmed.split("[&|;]+");
            for (String segment : segments) {
                String seg = segment.trim();
                if (seg.isEmpty()) continue;
                for (String denyPattern : DIRECT_DENY_PATTERNS) {
                    if (seg.matches("(?i).*" + denyPattern + ".*")) {
                        return Result.DENY;
                    }
                }
            }
            if (hasBacktickSubshellOutsideQuotes(trimmed)) {
                return Result.DENY;
            }
            if (hasRedirectionOutsideQuotes(trimmed)) {
                return Result.DENY;
            }
            if (hasEnvInjectionRisk(trimmed)) {
                return Result.DENY;
            }
            // 检查第一片段是否在白名单中（白名单链命令）
            String firstSeg = segments[0].trim();
            String mainCmd = extractMainCommand(firstSeg);
            for (String allowed : allowedCommands) {
                if (allowed.equalsIgnoreCase(mainCmd)) {
                    log.debug("[DangerousPatternValidator] ALLOW (whitelist chain): {}", trimmed);
                    return Result.ALLOW;
                }
            }
            // 白名单外链 → 放行（非危险命令）
            return Result.ALLOW;
        }

        // ============================================================
        // 第二层：危险命令需审批（仅对单条非链命令）
        // ============================================================
        for (String pattern : REQUIRES_APPROVAL_PATTERNS) {
            if (trimmed.matches("(?i).*" + pattern + ".*")) {
                return Result.REQUIRE_APPROVAL;
            }
        }

        // ============================================================
        // 第三层：白名单快速通道（零信任 + 高效）
        // ============================================================
        if (!allowedCommands.isEmpty()) {
            for (String allowed : allowedCommands) {
                if (trimmed.equals(allowed) || trimmed.startsWith(allowed + " ")) {
                    log.debug("[DangerousPatternValidator] ALLOW (whitelist): {} <= {}", allowed, trimmed);
                    return Result.ALLOW;
                }
            }
        }

        // ============================================================
        // 第四层：硬拒绝危险模式（白名单外的命令）
        // ============================================================
        for (String pattern : DIRECT_DENY_PATTERNS) {
            if (trimmed.matches("(?i).*" + pattern + ".*")) {
                return Result.DENY;
            }
        }

        // ============================================================
        // 第五层：白名单外的单条命令 — 注入检测
        // ============================================================

        // 5.1 反引号子shell（注入）
        if (hasBacktickSubshellOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        // 5.2 重定向攻击检测
        if (hasRedirectionOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        // 5.3 环境变量注入（仅在高风险位置检测）
        if (hasEnvInjectionRisk(trimmed)) {
            return Result.DENY;
        }

        // 白名单外的单条命令：无危险特征 → 放行
        return Result.ALLOW;
    }

    /**
     * 检测破坏性命令（WARNING 级别，不阻止但记录）
     */
    public boolean isDestructive(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.toLowerCase();
        for (String pattern : DESTRUCTIVE_WARNING_PATTERNS) {
            if (lower.matches("(?i).*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * sed 命令严格验证
     * @return null 表示不是 sed 命令（继续其他检查），否则返回 ALLOW/DENY/REQUIRE_APPROVAL
     */
    private Result validateSed(String command) {
        if (!isSedCommand(command)) {
            return null;
        }

        // 危险 sed 模式直接拒绝（-i, -e, -f, a/i/c/d/y 等）
        if ( SED_DANGEROUS_PATTERN.matcher(command).matches()) {
            log.warn("[DangerousPatternValidator] sed DENY (dangerous): {}", command);
            return Result.DENY;
        }

        // 安全 sed 模式检查：仅允许 sed 's/old/new/flags'
        if (SED_SAFE_PATTERN.matcher(command).matches()) {
            log.debug("[DangerousPatternValidator] sed ALLOW: {}", command);
            return Result.ALLOW;
        }

        // sed 命令但不符合安全模式 → 拒绝
        log.warn("[DangerousPatternValidator] sed DENY (pattern not allowed): {}", command);
        return Result.DENY;
    }

    private boolean isSedCommand(String command) {
        String lower = command.trim().toLowerCase();
        // 匹配 sed 开头，排除带管道和其他复杂操作
        return lower.startsWith("sed ") && !lower.contains("|") && !lower.contains(";") && !lower.contains("&&");
    }

    private boolean hasCommandChainingOutsideQuotes(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '^' && i + 1 < command.length() && !inSingleQuote && !inDoubleQuote) {
                return true;
            }

            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;

            if (!inSingleQuote && !inDoubleQuote) {
                for (char sym : INJECTION_SYMBOLS) {
                    if (c == sym) return true;
                }
                if ((c == '&' || c == '|') && i + 1 < command.length()
                        && command.charAt(i + 1) == c) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasRedirectionOutsideQuotes(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '^' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;

            if (!inSingleQuote && !inDoubleQuote && c == '>') {
                if (i + 1 < command.length() && command.charAt(i + 1) == '>') {
                    return true;
                }
                if (i + 1 < command.length()) {
                    char next = command.charAt(i + 1);
                    if (!Character.isWhitespace(next)) {
                        return true;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private boolean hasBacktickSubshellOutsideQuotes(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '^' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;

            if (!inSingleQuote && !inDoubleQuote && c == '`') {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查命令链（&&, |, ;）的所有部分是否都在白名单中
     */
    private boolean isAllSegmentsApproved(String command) {
        // 按 &&, |, ; 分隔
        String[] segments = command.split("[&|;]+");
        log.debug("[DangerousPatternValidator] isAllSegmentsApproved: command={}, segments={}", command, Arrays.toString(segments));
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            // 提取主命令（第一个单词）
            String mainCmd = extractMainCommand(trimmed);
            log.debug("[DangerousPatternValidator]   segment={}, mainCmd={}", trimmed, mainCmd);
            boolean found = false;
            for (String allowed : allowedCommands) {
                if (allowed.equals(mainCmd) || trimmed.equals(allowed) || trimmed.startsWith(allowed + " ")) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                log.debug("[DangerousPatternValidator]   mainCmd {} NOT in allowedCommands", mainCmd);
                return false;
            }
        }
        return true;
    }

    private String extractMainCommand(String segment) {
        String trimmed = segment.trim();
        // cd 后面跟任意路径都是安全的，直接返回 cd
        if (trimmed.startsWith("cd ")) {
            return "cd";
        }
        // 移除 sudo 前缀
        if (trimmed.startsWith("sudo ")) {
            trimmed = trimmed.substring(5).trim();
        }
        // 取第一个单词作为主命令
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            trimmed = trimmed.substring(0, spaceIdx);
        }
        return trimmed.toLowerCase();
    }

    /**
     * 环境变量注入风险检测（精确版）
     *
     * 仅在危险上下文中检测 $VAR 展开：
     * - eval/exec 后跟 $VAR
     * - 字符串插值 `${VAR}` 在非单引号中
     *
     * 不检测：set VAR=value（无$，安全）、路径中的$（如 /path/$USER/）
     */
    private boolean hasEnvInjectionRisk(String command) {
        String lower = command.toLowerCase().trim();

        // 高危上下文：eval, exec, bash -c, source 后跟环境变量
        if (lower.matches("^(eval|exec|source|\\.)\\s+.*\\$")) {
            return true;
        }

        // 检测双引号中的 `${VAR}` 或 `$VAR` 模式（可能被展开）
        // 跳过路径中的 $（如 /opt/$APP/bin/）
        return hasUnprotectedEnvVar(command);
    }

    /**
     * 检测未受保护的环境变量引用（在双引号字符串中可被展开）
     */
    private boolean hasUnprotectedEnvVar(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && i + 1 < command.length()) {
                i++; // skip escaped char
                continue;
            }

            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (!inSingleQuote && c == '$' && i + 1 < command.length()) {
                char next = command.charAt(i + 1);
                if (Character.isLetterOrDigit(next) || next == '{' || next == '(') {
                    // 在双引号中，且非路径上下文
                    if (inDoubleQuote) {
                        return true;
                    }
                    // 不在引号中：检查是否是路径前缀
                    if (i > 0) {
                        char prev = command.charAt(i - 1);
                        if (prev == '/' || prev == ':') {
                            continue; // 路径中的 $VAR，跳过
                        }
                    }
                    // 裸 $VAR 不在引号中且非路径 → 高风险
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 原始激进版环境变量检测（保留，暂未使用）
     */
    private boolean hasEnvironmentVariableOutsideQuotes(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '^' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;

            if (!inSingleQuote && !inDoubleQuote) {
                if (c == '$' && i + 1 < command.length()) {
                    char next = command.charAt(i + 1);
                    if (Character.isLetterOrDigit(next) || next == '{' || next == '(') {
                        if (i > 0) {
                            char prev = command.charAt(i - 1);
                            if (prev == '/' || prev == '\\' || prev == ':') {
                                continue;
                            }
                        }
                        return true;
                    }
                }
                if (c == '%' && command.indexOf('%', i + 1) > i + 1) {
                    return true;
                }
            }
        }
        return false;
    }
}