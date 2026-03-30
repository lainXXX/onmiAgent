package top.javarem.omni.tool.bash;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cleanupScheduler.shutdown();
    }

    public CheckResult createPendingTicket(String command) {
        String normalized = normalize(command);
        String ticketId = UUID.randomUUID().toString();
        tickets.put(ticketId, new ApprovalEntry(normalized, null, System.currentTimeMillis()));
        log.info("[ApprovalService] Ticket created: {} cmd={}", ticketId, normalized);
        return new CheckResult(CheckResult.Status.PENDING, ticketId, "⏸️ 命令待审批: " + command);
    }

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