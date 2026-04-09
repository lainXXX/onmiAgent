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

        boolean success = approvalService.submitApproval(
            request.ticketId(),
            request.command(),
            request.approved()
        );

        String message = success
            ? (request.approved() ? "已批准命令执行" : "已拒绝命令执行")
            : "审批失败：票根无效或命令不匹配";

        return Map.of("success", success, "message", message);
    }

    public record ApprovalRequest(
        String ticketId,
        Boolean approved,
        String command
    ) {}
}
