package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@Slf4j
public class SecurityInterceptor {

    private final DangerousPatternValidator validator;
    private final PathNormalizer pathNormalizer;

    private static final Pattern SAFE_PROBE_PATTERN = Pattern.compile(
            "^(cd|pwd|dir|ls|node -v|node --version|npm -v|npm --version|npm config list|test -.*|powershell -Command \"Test-Path.*).*$",
            Pattern.CASE_INSENSITIVE
    );

    public SecurityInterceptor(
            DangerousPatternValidator validator,
            PathNormalizer pathNormalizer) {
        this.validator = validator;
        this.pathNormalizer = pathNormalizer;
    }

    public record CheckResult(Type type, String ticketId, String message, boolean bypassed) {
        public enum Type { ALLOW, DENY, PENDING, WARNING }

        public static CheckResult allow() {
            return new CheckResult(Type.ALLOW, null, "命令允许执行", false);
        }

        public static CheckResult allow(boolean bypassed) {
            return new CheckResult(Type.ALLOW, null, bypassed ? "自动放行（免审批模式）" : "命令允许执行", bypassed);
        }

        public static CheckResult deny(String message) {
            return new CheckResult(Type.DENY, null, message, false);
        }

        public static CheckResult pending(String ticketId, String message) {
            return new CheckResult(Type.PENDING, ticketId, message, false);
        }

        public static CheckResult warning(String message) {
            return new CheckResult(Type.WARNING, null, message, false);
        }
    }

    /**
     * 兼容旧签名
     */
    public CheckResult check(String command) {
        return check(command, null, false);
    }

    public CheckResult check(String command, String workspace) {
        return check(command, workspace, false);
    }

    /**
     * 带完整参数的安全检查
     * @param acceptEdits 编辑模式，放行文件系统命令
     */
    public CheckResult check(String command, String workspace, boolean acceptEdits) {
        if (command == null || command.isBlank()) {
            return CheckResult.deny("命令不能为空");
        }

        // 1. 快速通道：基础环境探测与目录跳转命令直接放行（绕过严格正则误杀）
        if (isSafeProbeCommand(command)) {
            log.debug("[SecurityInterceptor] Safelist bypass for command: {}", command);
            return CheckResult.allow();
        }

        // 2. 危险模式正则校验
        DangerousPatternValidator.Result patternResult = validator.validate(command);
        if (patternResult == DangerousPatternValidator.Result.DENY) {
            log.warn("[SecurityInterceptor] Command denied: {}", command);
            return CheckResult.deny("禁止执行的危险命令: " + command);
        }

        // 3. 路径越权校验
        try {
            pathNormalizer.validate(command, workspace);
        } catch (WorkspaceAccessException e) {
            log.warn("[SecurityInterceptor] Workspace access warning (allowing anyway): {} — {}", command, e.getMessage());
            // 审批已禁用，直接放行
            return CheckResult.allow();
        } catch (SecurityException e) {
            log.warn("[SecurityInterceptor] Security violation: {} — {}", command, e.getMessage());
            return CheckResult.deny(e.getMessage());
        }

        // 4. 审批与警告逻辑（审批已禁用，全部放行）
        if (patternResult == DangerousPatternValidator.Result.REQUIRE_APPROVAL) {
            if (acceptEdits && isFileSystemCommand(command)) {
                log.info("[SecurityInterceptor] acceptEdits bypass for filesystem command: {}", command);
                return CheckResult.allow();
            }
            log.info("[SecurityInterceptor] REQUIRE_APPROVAL command (allowing without approval): {}", command);
            return CheckResult.allow();
        }

        if (patternResult == DangerousPatternValidator.Result.WARNING) {
            return CheckResult.warning("⚠️ 破坏性命令警告: " + command);
        }

        return CheckResult.allow();
    }

    private boolean isSafeProbeCommand(String command) {
        return SAFE_PROBE_PATTERN.matcher(command.trim()).matches();
    }

    private boolean isFileSystemCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.trim().toLowerCase();
        return lower.startsWith("mkdir ") || lower.startsWith("touch ") ||
               lower.startsWith("rm ") || lower.startsWith("cp ") ||
               lower.startsWith("mv ") || lower.startsWith("chmod ") ||
               lower.startsWith("chown ") || lower.startsWith("ln ") ||
               lower.startsWith("cat ") || lower.startsWith("echo ") ||
               lower.startsWith("tee ") || lower.startsWith("sed ") ||
               lower.startsWith("awk ") || lower.startsWith("find ") ||
               lower.startsWith("ls ") || lower.startsWith("grep ") ||
               lower.startsWith("head ") || lower.startsWith("tail ");
    }
}
