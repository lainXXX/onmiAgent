package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SecurityInterceptor {

    private final DangerousPatternValidator validator;
    private final PathNormalizer pathNormalizer;
    private final ApprovalService approvalService;

    public SecurityInterceptor(
            DangerousPatternValidator validator,
            PathNormalizer pathNormalizer,
            ApprovalService approvalService) {
        this.validator = validator;
        this.pathNormalizer = pathNormalizer;
        this.approvalService = approvalService;
    }

    public record CheckResult(Type type, String ticketId, String message) {
        public enum Type { ALLOW, DENY, PENDING, WARNING }
    }

    public CheckResult check(String command) {
        return check(command, null, false);
    }

    public CheckResult check(String command, String workspace) {
        return check(command, workspace, false);
    }

    /**
     * 带 workspace 和 acceptEdits 参数的安全检查
     * @param acceptEdits true = 编辑模式，放行文件系统命令（不拦截审批）
     */
    public CheckResult check(String command, String workspace, boolean acceptEdits) {
        if (command == null || command.isBlank()) {
            return new CheckResult(CheckResult.Type.DENY, null, "命令不能为空");
        }

        DangerousPatternValidator.Result patternResult = validator.validate(command);
        switch (patternResult) {
            case DENY:
                log.warn("[SecurityInterceptor] Command denied: {}", command);
                return new CheckResult(CheckResult.Type.DENY, null, "禁止执行的危险命令: " + command);
            case REQUIRE_APPROVAL:
                break;
            case ALLOW:
                break;
            case WARNING:
                break;
        }

        try {
            pathNormalizer.validate(command, workspace);
        } catch (WorkspaceAccessException e) {
            // 越界访问：可审批，用户可主动确认
            log.warn("[SecurityInterceptor] Workspace access requires approval: {} — {}", command, e.getMessage());
            return requireApproval(command, "⚠️ 访问受限路径（需用户审批）: " + e.getMessage());
        } catch (SecurityException e) {
            // 其他安全异常（注入攻击等）：硬拒绝
            log.warn("[SecurityInterceptor] Security violation: {} — {}", command, e.getMessage());
            return new CheckResult(CheckResult.Type.DENY, null, e.getMessage());
        }

        if (patternResult == DangerousPatternValidator.Result.REQUIRE_APPROVAL) {
            // acceptEdits 模式下，文件系统相关命令自动放行
            if (acceptEdits && isFileSystemCommand(command)) {
                log.info("[SecurityInterceptor] acceptEdits bypass for filesystem command: {}", command);
                return new CheckResult(CheckResult.Type.ALLOW, null, "命令允许执行（acceptEdits 模式）");
            }
            return requireApproval(command, "⚠️ 危险命令（需用户审批）: " + command);
        }

        if (patternResult == DangerousPatternValidator.Result.WARNING) {
            return new CheckResult(CheckResult.Type.WARNING, null,
                    "⚠️ 破坏性命令警告: " + command);
        }

        return new CheckResult(CheckResult.Type.ALLOW, null, "命令允许执行");
    }

    /**
     * 判断是否为文件系统操作命令（acceptEdits 模式下可自动放行）
     */
    private boolean isFileSystemCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.trim().toLowerCase();
        return lower.startsWith("mkdir ") || lower.startsWith("touch ") ||
               lower.startsWith("rm ") || lower.startsWith("cp ") ||
               lower.startsWith("mv ") || lower.startsWith("chmod ") ||
               lower.startsWith("chown ") || lower.startsWith("ln ") ||
               lower.startsWith("cat ") || lower.startsWith("echo ") ||
               lower.startsWith("tee ") || lower.startsWith("sed ") ||
               lower.startsWith("awk ") || lower.startsWith("find ");
    }

    /**
     * 创建待审批票根
     */
    private CheckResult requireApproval(String command, String message) {
        if (approvalService == null) {
            return new CheckResult(CheckResult.Type.DENY, null,
                    "❌ 审批服务不可用，无法提交受限命令审批: " + command + "\n\n" +
                    "请联系管理员确认审批服务已启动。");
        }
        ApprovalService.CheckResult approval = approvalService.createPendingTicket(command);
        return new CheckResult(CheckResult.Type.PENDING, approval.ticketId(), message);
    }
}
