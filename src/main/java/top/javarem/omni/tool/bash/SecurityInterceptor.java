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
        public enum Type { ALLOW, DENY, PENDING }
    }

    public CheckResult check(String command) {
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
        }

        try {
            pathNormalizer.validate(command);
        } catch (SecurityException e) {
            log.warn("[SecurityInterceptor] Path validation failed: {} — {}", command, e.getMessage());
            return new CheckResult(CheckResult.Type.DENY, null, e.getMessage());
        }

        if (patternResult == DangerousPatternValidator.Result.REQUIRE_APPROVAL) {
            if (approvalService == null) {
                return new CheckResult(CheckResult.Type.DENY, null, "审批服务不可用，拒绝执行: " + command);
            }
            ApprovalService.CheckResult approval = approvalService.createPendingTicket(command);
            return new CheckResult(CheckResult.Type.PENDING, approval.ticketId(), approval.message());
        }

        return new CheckResult(CheckResult.Type.ALLOW, null, "命令允许执行");
    }

    /**
     * 带 workspace 参数的安全检查（支持用户动态指定 workspace）
     */
    public CheckResult check(String command, String workspace) {
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
        }

        try {
            pathNormalizer.validate(command, workspace);
        } catch (SecurityException e) {
            log.warn("[SecurityInterceptor] Path validation failed: {} — {}", command, e.getMessage());
            return new CheckResult(CheckResult.Type.DENY, null, e.getMessage());
        }

        if (patternResult == DangerousPatternValidator.Result.REQUIRE_APPROVAL) {
            if (approvalService == null) {
                return new CheckResult(CheckResult.Type.DENY, null, "审批服务不可用，拒绝执行: " + command);
            }
            ApprovalService.CheckResult approval = approvalService.createPendingTicket(command);
            return new CheckResult(CheckResult.Type.PENDING, approval.ticketId(), approval.message());
        }

        return new CheckResult(CheckResult.Type.ALLOW, null, "命令允许执行");
    }
}