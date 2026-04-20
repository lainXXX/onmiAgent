package top.javarem.omni.tool.bash;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ApprovalService {

    private static final long TTL_MINUTES = 5;
    private static final long CONSUMED_TTL_MINUTES = 1;
    private static final long SCHEDULER_INTERVAL_MINUTES = 1;

    private final ConcurrentHashMap<String, ApprovalEntry> tickets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "ApprovalService-Cleanup");
            t.setDaemon(true);
            return t;
        }
    );

    public ApprovalService() {
        cleanupScheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(TTL_MINUTES);
            long consumedCutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(CONSUMED_TTL_MINUTES);
            tickets.entrySet().removeIf(e -> {
                ApprovalEntry entry = e.getValue();
                if (entry.approved() == null && entry.timestamp() < cutoff) return true;
                if (entry.approved() != null && entry.timestamp() < consumedCutoff) return true;
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
        log.info("[ApprovalService] Ticket {} quick-approved", ticketId);
        return true;
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