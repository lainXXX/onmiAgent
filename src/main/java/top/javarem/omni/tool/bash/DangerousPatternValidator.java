package top.javarem.omni.tool.bash;

import org.springframework.stereotype.Component;

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

    public enum Result { ALLOW, DENY, REQUIRE_APPROVAL }

    public Result validate(String command) {
        if (command == null || command.isBlank()) return Result.DENY;

        String trimmed = command.trim();

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