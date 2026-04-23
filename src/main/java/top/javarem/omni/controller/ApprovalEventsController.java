package top.javarem.omni.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import top.javarem.omni.tool.bash.ApprovalService;

import java.time.Duration;
import java.util.Map;

/**
 * 审批事件 SSE 通道
 * 前端建立此 SSE 连接后，可实时接收待审批命令事件
 */
@RestController
@RequestMapping("/approval-events")
@Slf4j
public class ApprovalEventsController {

    @Resource
    private ApprovalService approvalService;

    /**
     * 实时审批事件流（SSE）
     * 每秒推送一次，检查是否有新的待审批票根
     * 注意：使用 takeUntil 确保持久连接不会在审批完成后继续发送
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamApprovalEvents() {
        log.info("[ApprovalEvents] SSE 连接建立");

        // 先发送初始连接确认
        Flux<ServerSentEvent<Map<String, Object>>> heartbeat = Flux.just(
                ServerSentEvent.<Map<String, Object>>builder()
                        .event("connected")
                        .data(Map.of("status", "connected"))
                        .build()
        );

        // 每秒检查一次待审批票根
        Flux<ServerSentEvent<Map<String, Object>>> polling = Flux.interval(Duration.ofSeconds(1))
                .flatMap(tick -> {
                    var pending = approvalService.getPendingTicketsWithId();
                    if (pending.isEmpty()) {
                        // 无待审批，发送心跳
                        return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                                .event("heartbeat")
                                .data(Map.of("tick", tick))
                                .build());
                    }
                    // 有待审批，发送事件
                    return Flux.fromIterable(pending)
                            .map(ticket -> ServerSentEvent.<Map<String, Object>>builder()
                                    .event("dangerous-command")
                                    .data(Map.of(
                                            "ticketId", ticket.ticketId(),
                                            "command", ticket.entry().normalizedCommand(),
                                            "message", "危险命令待审批"
                                    ))
                                    .build());
                });

        // 合并初始连接和轮询流
        return Flux.concat(heartbeat, polling)
                .doOnCancel(() -> log.info("[ApprovalEvents] SSE 连接断开"))
                .doOnError(e -> {
                    // 忽略 IOException - 这是客户端(浏览器)断开连接的正常情况
                    // 例如：用户关闭页面、刷新、跳转到其他页面等
                    if (e instanceof java.io.IOException ||
                            e.getCause() instanceof java.io.IOException ||
                            e.getMessage() != null && e.getMessage().contains("aborted")) {
                        log.info("[ApprovalEvents] 客户端断开连接（正常）");
                    } else {
                        log.error("[ApprovalEvents] SSE 异常", e);
                    }
                });
    }
}
