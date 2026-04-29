package top.javarem.omni.tool.bash;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import top.javarem.omni.event.ApprovalCreatedEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class ApprovalService {

    private static final long TTL_MINUTES = 5;
    private static final long CONSUMED_TTL_MINUTES = 1;
    private static final long SCHEDULER_INTERVAL_MINUTES = 1;

    private final ConcurrentHashMap<String, ApprovalEntry> tickets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingFutures = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "ApprovalService-Cleanup");
            t.setDaemon(true);
            return t;
        }
    );
    private static final long DEFAULT_APPROVAL_TIMEOUT_SECONDS = 600; // 10 minutes

    public ApprovalService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        cleanupScheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(TTL_MINUTES);
            long consumedCutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(CONSUMED_TTL_MINUTES);
            tickets.entrySet().removeIf(e -> {
                ApprovalEntry entry = e.getValue();
                if (entry.approved() == null && entry.timestamp() < cutoff) return true;
                if (entry.approved() != null && entry.timestamp() < consumedCutoff) return true;
                return false;
            });
            // 清理超时的 pending Futures（防止内存泄漏）
            long futureCutoff = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(DEFAULT_APPROVAL_TIMEOUT_SECONDS);
            pendingFutures.entrySet().removeIf(e -> {
                if (e.getValue().isDone()) return true; // 已完成的清理掉
                // 超时的标记为失败
                if (!e.getValue().isDone()) {
                    e.getValue().completeExceptionally(new TimeoutException("审批超时"));
                    return true;
                }
                return false;
            });
        }, SCHEDULER_INTERVAL_MINUTES, SCHEDULER_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public CheckResult createPendingTicket(String command) {
        String normalized = normalize(command);
        String ticketId = UUID.randomUUID().toString();
        tickets.put(ticketId, new ApprovalEntry(normalized, null, System.currentTimeMillis()));
        log.info("[ApprovalService] Ticket created: {} cmd={}", ticketId, normalized);

        // 发布审批创建事件，让 UI 监听器负责推送
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ApprovalCreatedEvent(this, ticketId, normalized));
        }

        return new CheckResult(CheckResult.Status.PENDING, ticketId, "⏸️ 命令待审批: " + command);
    }

    /**
     * 提交审批（需提供完整命令以防篡改）
     */
    public boolean submitApproval(String ticketId, String command, boolean approved) {
        String normalized = normalize(command);
        ApprovalEntry entry = tickets.get(ticketId);

        if (entry == null) {
            log.warn("[ApprovalService] Ticket not found: {}", ticketId);
            return false;
        }

        if (!entry.normalizedCommand().equals(normalized)) {
            log.warn("[ApprovalService] Command mismatch for ticket {}: expected={} got={}",
                ticketId, entry.normalizedCommand(), normalized);
            return false;
        }

        tickets.compute(ticketId, (k, v) -> {
            if (v == null) return null;
            return new ApprovalEntry(v.normalizedCommand(), approved, v.timestamp());
        });

        // 完成等待中的 Future
        completeApproval(ticketId, approved);

        log.info("[ApprovalService] Ticket {} approved={}", ticketId, approved);
        return true;
    }

    /**
     * 快捷审批（仅需 ticketId），适用于用户在界面直接批准已知的命令。
     * 注意：仅当票根状态为待审批时有效。
     */
    public boolean quickApprove(String ticketId) {
        ApprovalEntry entry = tickets.get(ticketId);
        if (entry == null) {
            log.warn("[ApprovalService] Ticket not found for quick approve: {}", ticketId);
            return false;
        }
        if (entry.approved() != null) {
            log.warn("[ApprovalService] Ticket {} already processed (approved={})", ticketId, entry.approved());
            return false;
        }
        tickets.compute(ticketId, (k, v) -> {
            if (v == null) return null;
            return new ApprovalEntry(v.normalizedCommand(), true, v.timestamp());
        });

        // 完成等待中的 Future
        completeApproval(ticketId, true);

        log.info("[ApprovalService] Ticket {} quick-approved", ticketId);
        return true;
    }

    /**
     * 获取待审批票根的 Future（Agent 端阻塞等待）
     */
    public CompletableFuture<Boolean> getApprovalFuture(String ticketId) {
        return pendingFutures.computeIfAbsent(ticketId, id -> {
            log.info("[ApprovalService] 【审批触发】检测到危险命令，已创建票根。TicketId: {}, 命令: {}",
                id, tickets.get(id) != null ? tickets.get(id).normalizedCommand() : "未知");
            return new CompletableFuture<>();
        });
    }

    /**
     * 完成审批 Future（内部使用或审批完成后调用）
     */
    public boolean completeApproval(String ticketId, boolean approved) {
        CompletableFuture<Boolean> future = pendingFutures.remove(ticketId);
        if (future != null) {
            future.complete(approved);
            log.info("[ApprovalService] 【{}】票根审批完成。TicketId: {}, Approved: {}",
                approved ? "审批通过" : "审批拒绝", ticketId, approved);
            return true;
        }
        log.debug("[ApprovalService] 无等待中的 Future: ticketId={}", ticketId);
        return false;
    }

    /**
     * 查询票根状态（不含消费，用于展示）
     */
    public ApprovalEntry getEntry(String ticketId) {
        return tickets.get(ticketId);
    }

    /**
     * 获取所有待审批的票根（用于前端轮询）
     */
    public List<ApprovalEntry> getPendingTickets() {
        return tickets.values().stream()
                .filter(e -> e.approved() == null)  // 未处理
                .filter(e -> System.currentTimeMillis() - e.timestamp() < TimeUnit.MINUTES.toMillis(TTL_MINUTES))
                .toList();
    }

    /**
     * 获取所有待审批的票根（包含 ID，用于前端轮询）
     */
    public List<PendingTicket> getPendingTicketsWithId() {
        return tickets.entrySet().stream()
                .filter(e -> e.getValue().approved() == null)  // 未处理
                .filter(e -> System.currentTimeMillis() - e.getValue().timestamp() < TimeUnit.MINUTES.toMillis(TTL_MINUTES))
                .map(e -> new PendingTicket(e.getKey(), e.getValue()))
                .toList();
    }

    public record PendingTicket(String ticketId, ApprovalEntry entry) {}

    public CheckResult checkAndConsume(String ticketId, String command) {
        String normalized = normalize(command);
        ApprovalEntry entry = tickets.remove(ticketId);

        if (entry == null) {
            return new CheckResult(CheckResult.Status.EXPIRED, ticketId, "审批超时或票根无效");
        }

        if (!entry.normalizedCommand().equals(normalized)) {
            log.warn("[ApprovalService] Command mismatch at consume: {} vs {}",
                entry.normalizedCommand(), normalized);
            return new CheckResult(CheckResult.Status.REJECTED, ticketId, "命令已被篡改，拒绝执行");
        }

        if (entry.approved() == null) {
            return new CheckResult(CheckResult.Status.PENDING, ticketId, "审批尚未完成");
        }

        if (entry.approved()) {
            return new CheckResult(CheckResult.Status.APPROVED, ticketId, "命令已审批通过");
        } else {
            return new CheckResult(CheckResult.Status.REJECTED, ticketId, "命令已被拒绝");
        }
    }

    private String normalize(String command) {
        return command.replaceAll("\\s+", " ").trim();
    }

    public record ApprovalEntry(String normalizedCommand, Boolean approved, long timestamp) {}

    public record CheckResult(Status status, String ticketId, String message) {
        public enum Status { APPROVED, PENDING, REJECTED, EXPIRED }
    }
}