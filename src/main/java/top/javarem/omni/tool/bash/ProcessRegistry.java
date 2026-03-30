package top.javarem.omni.tool.bash;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ProcessRegistry {

    private static final int MAX_HISTORY = 50;
    private static final long CLEANUP_INTERVAL_MINUTES = 1;
    private static final long HISTORY_RETENTION_MINUTES = 5;

    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> historyOrder = new ConcurrentLinkedQueue<>();
    private final Map<String, ManagedProcess> history = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "ProcessRegistry-Cleanup");
            t.setDaemon(true);
            return t;
        }
    );

    public ProcessRegistry() {
        scheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(HISTORY_RETENTION_MINUTES);
            history.entrySet().removeIf(e ->
                e.getValue().state() != ManagedProcess.ProcessState.RUNNING &&
                e.getValue().startTime().toEpochMilli() < cutoff
            );
            while (history.size() > MAX_HISTORY) {
                String oldest = historyOrder.poll();
                if (oldest != null) history.remove(oldest);
                else break;
            }
        }, CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public void register(ManagedProcess process) {
        processes.put(process.pid(), process);
        log.info("[ProcessRegistry] Registered PID={} cmd={}", process.pid(), process.command());

        process.handle().onExit().thenAccept(p -> {
            process.transitionTo(ManagedProcess.ProcessState.TERMINATED);
            unregister(process.pid());
            log.info("[ProcessRegistry] Process exited PID={}", process.pid());
        });
    }

    public void unregister(String pid) {
        ManagedProcess removed = processes.remove(pid);
        if (removed != null) {
            history.put(pid, removed);
            historyOrder.offer(pid);
        }
    }

    public Optional<ManagedProcess> get(String pid) {
        return Optional.ofNullable(processes.get(pid));
    }

    public List<ManagedProcess> listAll() {
        return new ArrayList<>(processes.values());
    }

    public boolean kill(String pid) {
        ManagedProcess mp = processes.get(pid);
        if (mp == null) return false;
        try {
            boolean destroyed = mp.handle().destroyForcibly();
            mp.transitionTo(ManagedProcess.ProcessState.KILLED);
            log.info("[ProcessRegistry] Kill requested PID={} result={}", pid, destroyed);
            return destroyed;
        } catch (Exception e) {
            log.warn("[ProcessRegistry] Failed to kill PID={}", pid, e);
            return false;
        }
    }

    @PreDestroy
    public void cleanup() {
        scheduler.shutdown();
    }

    @PreDestroy
    public void forceKillAll() {
        log.info("[ProcessRegistry] forceKillAll triggered, killing {} processes", processes.size());
        processes.keySet().forEach(pid -> kill(pid));
        cleanup();
    }
}