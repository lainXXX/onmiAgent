package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@Slf4j
public class SecurityInterceptor {

    private final DangerousPatternValidator validator;
    private final PathNormalizer pathNormalizer;
    private final ApprovalService approvalService;

    private static final Pattern SAFE_PROBE_PATTERN = Pattern.compile(
            "^(cd|pwd|dir|ls|node -v|node --version|npm -v|npm --version|npm config list|test -.*|powershell -Command \"Test-Path.*).*$",
            Pattern.CASE_INSENSITIVE
    );

    public SecurityInterceptor(
            DangerousPatternValidator validator,
            PathNormalizer pathNormalizer,
            ApprovalService approvalService) {
        this.validator = validator;
        this.pathNormalizer = pathNormalizer;
        this.approvalService = approvalService;
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
     * 仅 bypassApproval 参数的重载
     */
    public CheckResult check(String command, boolean bypassApproval) {
        return check(command, null, false, bypassApproval);
    }

    /**
     * 兼容旧签名（bypassApproval 默认为 false）
     */
    public CheckResult check(String command) {
        return check(command, null, false, false);
    }

    public CheckResult check(String command, String workspace) {
        return check(command, workspace, false, false);
    }

    public CheckResult check(String command, String workspace, boolean acceptEdits) {
        return check(command, workspace, acceptEdits, false);
    }

    /**
     * 带完整参数的安全检查
     * @param acceptEdits 编辑模式，放行文件系统命令（不拦截审批）
     * @param bypassApproval 跳过审批模式，放行 REQUIRE_APPROVAL 级别的命令
     */
    public CheckResult check(String command, String workspace, boolean acceptEdits, boolean bypassApproval) {
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
            log.warn("[SecurityInterceptor] Workspace access requires approval: {} — {}", command, e.getMessage());
            return requireApproval(command, "⚠️ 访问受限路径（需用户审批）: " + e.getMessage());
        } catch (SecurityException e) {
            log.warn("[SecurityInterceptor] Security violation: {} — {}", command, e.getMessage());
            return CheckResult.deny(e.getMessage());
        }

        // 4. 审批与警告逻辑
        if (patternResult == DangerousPatternValidator.Result.REQUIRE_APPROVAL) {
            if (acceptEdits && isFileSystemCommand(command)) {
                log.info("[SecurityInterceptor] acceptEdits bypass for filesystem command: {}", command);
                return CheckResult.allow();
            }
            // bypassApproval 模式下，放行 REQUIRE_APPROVAL 级别的命令
            if (bypassApproval) {
                log.warn("[Bypass] Command '{}' requires approval, but was bypassed by user session.", command);
                return CheckResult.allow(true);
            }
            return requireApproval(command, "⚠️ 危险命令（需用户审批）: " + command);
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

    private CheckResult requireApproval(String command, String message) {
        if (approvalService == null) {
            return CheckResult.deny(
                    "❌ 审批服务不可用，无法提交受限命令审批: " + command + "\n\n" +
                    "请联系管理员确认审批服务已启动。");
        }
        ApprovalService.CheckResult approval = approvalService.createPendingTicket(command);
        return CheckResult.pending(approval.ticketId(), message);
    }
}