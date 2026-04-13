package top.javarem.omni.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import top.javarem.omni.tool.bash.ApprovalService;

import java.util.Map;

@RestController
@RequestMapping("/approval")
@Slf4j
public class ApprovalController {

    @Resource
    private ApprovalService approvalService;

    @PostMapping
    public Map<String, Object> approve(@RequestBody ApprovalRequest request) {
        log.info("[ApprovalController] Approval request: ticketId={} approved={} cmd={}",
            request.ticketId(), request.approved(), request.command());

        boolean success;
        if (request.command() != null && !request.command().isBlank()) {
            // 标准审批：需命令匹配防篡改
            success = approvalService.submitApproval(
                request.ticketId(),
                request.command(),
                request.approved()
            );
        } else {
            // 快捷审批：仅需 ticketId
            success = request.approved()
                ? approvalService.quickApprove(request.ticketId())
                : false;
        }

        String message = success
            ? (request.approved() ? "已批准命令执行" : "已拒绝命令执行")
            : "审批失败：票根无效、已过期或命令不匹配";

        return Map.of("success", success, "message", message);
    }

    /**
     * 查询票根状态（用于界面展示待审批命令详情）
     */
    @GetMapping("/{ticketId}")
    public Map<String, Object> getTicket(@PathVariable String ticketId) {
        ApprovalService.ApprovalEntry entry = approvalService.getEntry(ticketId);
        if (entry == null) {
            return Map.of("success", false, "message", "票根不存在或已过期");
        }
        return Map.of(
            "success", true,
            "ticketId", ticketId,
            "command", entry.normalizedCommand(),
            "status", entry.approved() == null ? "PENDING"
                : entry.approved() ? "APPROVED" : "REJECTED",
            "createdAt", entry.timestamp()
        );
    }

    public record ApprovalRequest(
        String ticketId,
        Boolean approved,
        String command
    ) {}
}
