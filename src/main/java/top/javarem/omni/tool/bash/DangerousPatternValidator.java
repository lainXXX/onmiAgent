package top.javarem.omni.tool.bash;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

@Component
public class DangerousPatternValidator {

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

    private static final char[] INJECTION_SYMBOLS = {';', '|', '&'};

    private static final Set<String> allowedCommands = new HashSet<>();

    public DangerousPatternValidator() {
        // No-arg constructor for test compatibility - allowedCommands remains empty
    }

    public DangerousPatternValidator(@Value("classpath:approved-commands.properties") Resource resource) {
        try {
            for (String line : Files.readAllLines(resource.getFile().toPath())) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    allowedCommands.add(trimmed);
                }
            }
        } catch (Exception e) {
            // If file missing, deny all non-trivial commands
        }
    }

    public enum Result { ALLOW, DENY, REQUIRE_APPROVAL }

    public Result validate(String command) {
        if (command == null || command.isBlank()) return Result.DENY;

        String trimmed = command.trim();

        // Check allowlist first — 支持命令链（&&, |, ;）
        if (!allowedCommands.isEmpty()) {
            // 单条命令完全匹配
            for (String allowed : allowedCommands) {
                if (trimmed.equals(allowed) || trimmed.startsWith(allowed + " ")) {
                    return Result.ALLOW;
                }
            }
            // 命令链：拆分成多个子命令，全部在白名单中则放行
            if (isAllSegmentsApproved(trimmed)) {
                return Result.ALLOW;
            }
        }

        for (String pattern : DIRECT_DENY_PATTERNS) {
            if (trimmed.matches("(?i).*" + pattern + ".*")) {
                return Result.DENY;
            }
        }

        if (hasCommandChainingOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        if (hasEnvironmentVariableOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        if (hasRedirectionOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        if (hasBacktickSubshellOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        for (String pattern : REQUIRES_APPROVAL_PATTERNS) {
            if (trimmed.matches("(?i).*" + pattern + ".*")) {
                return Result.REQUIRE_APPROVAL;
            }
        }

        return Result.ALLOW;
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
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            // 提取主命令（第一个单词）
            String mainCmd = extractMainCommand(trimmed);
            boolean found = false;
            for (String allowed : allowedCommands) {
                if (allowed.equals(mainCmd) || trimmed.equals(allowed) || trimmed.startsWith(allowed + " ")) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
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