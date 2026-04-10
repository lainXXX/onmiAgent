package top.javarem.omni.model;

/**
 * Bash 执行到危险命令时抛出，中断 Flux，
 * 触发 ChatController 发送 DANGEROUS_COMMAND_PENDING 事件到 SSE。
 */
public class DangerousCommandPendingException extends RuntimeException {

    private final String ticketId;
    private final String command;

    public DangerousCommandPendingException(String ticketId, String command) {
        super("DANGEROUS_COMMAND_PENDING: " + ticketId);
        this.ticketId = ticketId;
        this.command = command;
    }

    public String ticketId() {
        return ticketId;
    }

    public String command() {
        return command;
    }
}
