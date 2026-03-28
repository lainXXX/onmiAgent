package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import top.javarem.omni.tool.PathApprovalService;

/**
 * 命令审批器
 */
@Slf4j
public class CommandApprover {

    private static final int APPROVAL_TIMEOUT_SECONDS = 30;

    private final PathApprovalService approvalService;

    public CommandApprover(PathApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * 请求命令审批
     */
    public ApprovalCheckResult requestApproval(String command, String reason) {
        String message = "⚠️ 安全警告：Agent 尝试执行高危命令\n\n" +
                "执行的命令: " + command + "\n\n" +
                "风险原因: " + reason + "\n\n" +
                "这可能会对系统造成不可逆的影响。是否批准执行？";

        PathApprovalService.ApprovalResult result = approvalService.requestApproval(
                "COMMAND_EXEC",
                message,
                APPROVAL_TIMEOUT_SECONDS
        );

        if (!result.approved()) {
            return new ApprovalCheckResult(false, result.reason());
        }
        return new ApprovalCheckResult(true, null);
    }
}
