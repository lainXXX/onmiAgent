package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.javarem.omni.event.ApprovalCreatedEvent;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批 UI 桥接器
 * 监听审批创建事件，通过 SSE 推送审批卡片到前端
 */
@Component
@Slf4j
public class ApprovalUiBridge {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 前端订阅审批事件
     */
    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 分钟
        emitters.put(sessionId, emitter);

        emitter.onCompletion(() -> {
            log.debug("[ApprovalUiBridge] SSE 完成: sessionId={}", sessionId);
            emitters.remove(sessionId);
        });
        emitter.onTimeout(() -> {
            log.debug("[ApprovalUiBridge] SSE 超时: sessionId={}", sessionId);
            emitters.remove(sessionId);
        });
        emitter.onError(e -> {
            log.debug("[ApprovalUiBridge] SSE 错误: sessionId={} - {}", sessionId, e.getMessage());
            emitters.remove(sessionId);
        });

        log.info("[ApprovalUiBridge] 前端订阅审批事件: sessionId={}", sessionId);
        return emitter;
    }

    /**
     * 监听审批创建事件，推送至前端
     */
    @EventListener
    public void onApprovalCreated(ApprovalCreatedEvent event) {
        log.info("[ApprovalUiBridge] 【UI 更新】检测到审批创建事件，准备推送。TicketId: {}, 命令: {}",
            event.getTicketId(), event.getCommand());

        // 广播给所有订阅的前端
        emitters.values().forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("approval-pending")
                        .data(java.util.Map.of(
                                "type", "APPROVAL_PENDING",
                                "ticketId", event.getTicketId(),
                                "command", event.getCommand(),
                                "timestamp", event.getEventTime(),
                                "message", "⏸️ 危险命令待审批，请尽快处理"
                        )));
                log.debug("[ApprovalUiBridge] 审批事件已推送: ticketId={}", event.getTicketId());
            } catch (IOException e) {
                log.warn("[ApprovalUiBridge] 推送审批事件失败: ticketId={}", event.getTicketId());
                emitter.completeWithError(e);
            }
        });
    }
}