package top.javarem.omni.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 审批创建事件
 * 用于通知前端显示待审批命令
 */
@Getter
public class ApprovalCreatedEvent extends ApplicationEvent {

    private final String ticketId;
    private final String command;
    private final long eventTime;

    public ApprovalCreatedEvent(Object source, String ticketId, String command) {
        super(source);
        this.ticketId = ticketId;
        this.command = command;
        this.eventTime = System.currentTimeMillis();
    }
}