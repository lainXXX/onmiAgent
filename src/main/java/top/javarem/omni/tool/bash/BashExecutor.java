package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy; // 注意：如果是 SpringBoot 2.x 请换成 javax.annotation.PreDestroy
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Bash 命令执行器
 *
 * <p>集成了安全拦截、进程注册、ProcessHandle 生命周期管理、
 * 独立的 I/O 读取无界线程池（防死锁）、字符集处理和 stderr 合并、以及智能指令加速。</p>
 */
@Component
@Slf4j
public class BashExecutor {

    private final ProcessRegistry processRegistry;
    private final SecurityInterceptor securityInterceptor;
    private final WorkingDirectoryManager workingDirectoryManager;
    private final ResponseFormatter formatter;

    @Value("${agent.working-directory:${user.dir}}")
    private String defaultWorkspace;

    private final ProcessTreeKiller processKiller = new ProcessTreeKiller();

    // 最大输出行数限制，防止执行类似 cat /dev/urandom 或超大日志导致 OOM
    private static final int MAX_OUTPUT_LINES = 100_000;

    // 审批超时时间（秒）
    private static final long DEFAULT_APPROVAL_TIMEOUT_SECONDS = 600; // 10 分钟

    // 命令默认超时（毫秒）
    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 300_000; // 5 分钟

    // 专用于读取 InputStream 的独立线程池
    private volatile ExecutorService ioReaderExecutor;

    private final ApprovalService approvalService;

    public BashExecutor(ProcessRegistry processRegistry,
                        SecurityInterceptor securityInterceptor,
                        WorkingDirectoryManager workingDirectoryManager,
                        ResponseFormatter formatter,
                        ApprovalService approvalService) {
        this.processRegistry = processRegistry;
        this.securityInterceptor = securityInterceptor;
        this.workingDirectoryManager = workingDirectoryManager;
        this.formatter = formatter;
        this.approvalService = approvalService;
    }

    @PreDestroy
    public void destroy() {
        if (ioReaderExecutor != null) {
            ioReaderExecutor.shutdownNow();
        }
    }

    /**
     * 获取读流专用的隔离线程池。
     * 【核心优化】使用 SynchronousQueue 拒绝排队，直接创建新线程读取，彻底解决输出缓冲区满导致的进程死锁。
     */
    private ExecutorService getIoReaderExecutor() {
        if (ioReaderExecutor == null) {
            synchronized (this) {
                if (ioReaderExecutor == null) {
                    ioReaderExecutor = new ThreadPoolExecutor(
                            10, 200, 60L, TimeUnit.SECONDS,
                            new SynchronousQueue<>(),
                            r -> {
                                Thread t = new Thread(r, "bash-io-reader");
                                t.setDaemon(true);
                                return t;
                            }
                    );
                }
            }
        }
        return ioReaderExecutor;
    }

    private String resolveEffectiveWorkspace(String userWorkspace) {
        if (userWorkspace == null || userWorkspace.isBlank()) {
            return defaultWorkspace;
        }
        try {
            Path path = Paths.get(userWorkspace).toAbsolutePath().normalize();
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                log.warn("[BashExecutor] 用户指定的 workspace 无效: {}, 降级到默认: {}",
                        userWorkspace, defaultWorkspace);
                return defaultWorkspace;
            }
            return path.toString().replace("\\", "/");
        } catch (Exception e) {
            log.warn("[BashExecutor] workspace 解析异常: {}, 降级到默认: {}",
                    userWorkspace, defaultWorkspace);
            return defaultWorkspace;
        }
    }

    public String execute(String command, long timeoutMs, String userWorkspace) throws Exception {
        return execute(command, timeoutMs, userWorkspace, false);
    }

    public String execute(String command, long timeoutMs, String userWorkspace, boolean acceptEdits) throws Exception {
        String effectiveWorkspace = resolveEffectiveWorkspace(userWorkspace);
        workingDirectoryManager.syncWorkspace(effectiveWorkspace);

        SecurityInterceptor.CheckResult check = securityInterceptor.check(command, effectiveWorkspace, acceptEdits);
        boolean destructiveWarning = false;

        switch (check.type()) {
            case DENY:
                return formatter.formatError("安全拦截: " + check.message(), -1, command);
            case PENDING:
                // 阻塞等待审批结果
                return executeWithPendingApproval(command, check.ticketId(), effectiveWorkspace);
            case WARNING:
                destructiveWarning = true;
                break;
            case ALLOW:
                break;
        }

        String result = doExecute(command, timeoutMs, false, effectiveWorkspace);
        if (destructiveWarning) {
            return formatter.formatDestructiveWarning(command) + result;
        }
        return result;
    }

    /**
     * 阻塞等待审批完成后执行命令
     */
    private String executeWithPendingApproval(String command, String ticketId, String effectiveWorkspace) throws Exception {
        log.info("[BashExecutor] 【审批等待】线程已挂起等待审批。TicketId: {}, 超时时间: {}分钟, 命令: {}",
            ticketId, DEFAULT_APPROVAL_TIMEOUT_SECONDS / 60, command);

        try {
            // 获取 Future 并阻塞等待
            CompletableFuture<Boolean> future = approvalService.getApprovalFuture(ticketId);
            boolean approved = future.get(DEFAULT_APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (approved) {
                log.info("[BashExecutor] 【审批通过】收到用户批准，准备恢复命令执行。TicketId: {}, 命令: {}",
                    ticketId, command);
                // 审批通过，真正执行命令
                return doExecute(command, DEFAULT_COMMAND_TIMEOUT_MS, false, effectiveWorkspace);
            } else {
                log.info("[BashExecutor] 【审批拒绝】用户拒绝命令执行。TicketId: {}, 命令: {}",
                    ticketId, command);
                return formatter.formatError("用户拒绝了命令执行: " + command, -1, command);
            }
        } catch (TimeoutException e) {
            log.warn("[BashExecutor] 【审批超时】票根审批超时。TicketId: {}, 命令: {}", ticketId, command);
            approvalService.completeApproval(ticketId, false); // 清理
            return formatter.formatError("审批超时（10分钟），命令未执行: " + command, -1, command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[BashExecutor] 【审批中断】等待审批被中断。TicketId: {}, 命令: {}", ticketId, command);
            return formatter.formatError("等待审批被中断: " + command, -1, command);
        } catch (ExecutionException e) {
            log.error("[BashExecutor] 【审批异常】审批过程出错。TicketId: {}, 命令: {}, 错误: {}",
                ticketId, command, e.getMessage());
            return formatter.formatError("审批过程出错: " + e.getMessage(), -1, command);
        }
    }

    public String executeBackground(String command, String userWorkspace) throws Exception {
        String effectiveWorkspace = resolveEffectiveWorkspace(userWorkspace);
        workingDirectoryManager.syncWorkspace(effectiveWorkspace);

        SecurityInterceptor.CheckResult check = securityInterceptor.check(command, effectiveWorkspace);
        if (check.type() == SecurityInterceptor.CheckResult.Type.DENY) {
            return formatter.formatError("安全拦截: " + check.message(), -1, command);
        }
        if (check.type() == SecurityInterceptor.CheckResult.Type.PENDING) {
            return formatter.formatPending(check.ticketId(), check.message());
        }
        if (check.type() == SecurityInterceptor.CheckResult.Type.WARNING) {
            log.warn("[BashExecutor] Destructive command warning (background): {}", command);
        }

        return doExecute(command, 0, true, effectiveWorkspace);
    }

    private String doExecute(String command, long timeoutMs, boolean background, String effectiveWorkspace) throws Exception {
        // 【加速优化】针对 LLM 常用的耗时命令进行静默调优
        String optimizedCommand = optimizeCommand(command);

        ProcessBuilder builder = new ProcessBuilder();
        configureProcessBuilder(builder, optimizedCommand);

        Path trackedDir = workingDirectoryManager.getCurrentDir();
        builder.directory(trackedDir.toFile());
        builder.redirectErrorStream(true); // 合并 stderr 到 stdout

        Process process = builder.start();
        String pid = String.valueOf(process.pid());
        log.info("[BashExecutor] Process started: PID={} cmd={}", pid, optimizedCommand);

        if (background) {
            ManagedProcess mp = new ManagedProcess(pid, process.toHandle(), optimizedCommand, "",
                    Instant.now(), ManagedProcess.ProcessState.RUNNING, true);
            processRegistry.register(mp);
            return formatter.formatBackgroundStarted(pid, optimizedCommand);
        }

        ManagedProcess mp = new ManagedProcess(pid, process.toHandle(), optimizedCommand, "",
                Instant.now(), ManagedProcess.ProcessState.RUNNING, false);
        processRegistry.register(mp);

        Charset charset = detectCharset();
        StringBuilder output = new StringBuilder();
        ExecutorService readerExecutor = getIoReaderExecutor();

        Future<?> readerFuture = readerExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    if (lineCount < MAX_OUTPUT_LINES) {
                        output.append(line).append("\n");
                    } else if (lineCount == MAX_OUTPUT_LINES) {
                        output.append("\n... [输出超过限制，已截断截取前 ").append(MAX_OUTPUT_LINES).append(" 行] ...\n");
                    }
                    lineCount++;
                }
            } catch (Exception e) {
                log.warn("[BashExecutor] Output reading failed for PID={}", pid, e);
            }
        });

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        String rawOutput = output.toString();

        if (!finished) {
            log.warn("[BashExecutor] Command timeout: {}ms cmd={}", timeoutMs, optimizedCommand);
            processKiller.kill(process);
            readerFuture.cancel(true);
            processRegistry.kill(pid);
            workingDirectoryManager.trackAndValidate(rawOutput, null);
            return formatter.formatTimeout(rawOutput, timeoutMs);
        }

        try {
            // 确保流彻底读取完毕
            readerFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            log.warn("[BashExecutor] Reader thread took too long to close, ignoring.", e);
        }

        processRegistry.unregister(pid);

        // 跟踪工作目录变化
        Path newDir = workingDirectoryManager.trackAndValidate(rawOutput, null);
        if (!newDir.equals(trackedDir)) {
            log.info("[BashExecutor] 工作目录切换: -> {}", newDir);
        }

        int exitCode = process.exitValue();
        if (exitCode == 0) {
            return formatter.formatSuccess(rawOutput);
        } else {
            return formatter.formatError(rawOutput, exitCode, optimizedCommand);
        }
    }

    /**
     * 智能优化 LLM 生成的命令，避免无意义的性能损耗
     */
    private String optimizeCommand(String command) {
        String optimized = command;
        // 如果使用了 PowerShell，默认强制追加 -NoProfile -NonInteractive 以避免加载配置文件导致巨慢
        if (optimized.toLowerCase().contains("powershell") && !optimized.toLowerCase().contains("-noprofile")) {
            optimized = optimized.replaceAll("(?i)(powershell(?:\\.exe)?)\\s+(-command|-c)\\s+",
                    "$1 -NoProfile -NonInteractive -Command ");
        }
        return optimized;
    }

    private void configureProcessBuilder(ProcessBuilder builder, String command) {
        String[] shellPrefix = OsHelper.current().getShellCommandPrefix();
        String[] fullCmd = new String[shellPrefix.length + 1];
        System.arraycopy(shellPrefix, 0, fullCmd, 0, shellPrefix.length);
        fullCmd[fullCmd.length - 1] = command;
        builder.command(fullCmd);
    }

    private Charset detectCharset() {
        Charset detected = Charset.defaultCharset();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (!StandardCharsets.UTF_8.equals(detected)) {
                try {
                    return Charset.forName("GBK");
                } catch (Exception ignored) {}
            }
        }
        return detected;
    }
}