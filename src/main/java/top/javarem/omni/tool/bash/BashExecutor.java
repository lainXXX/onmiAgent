package top.javarem.omni.tool.bash;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import top.javarem.omni.utils.RequestContextHolder;

import java.io.BufferedReader;
import java.io.File;
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
 * 共享线程池、字符集处理和 stderr 合并。</p>
 */
@Component
@Slf4j
public class BashExecutor {

    @Autowired
    private ProcessRegistry processRegistry;

    @Autowired
    private SecurityInterceptor securityInterceptor;

    @Resource
    private ResponseFormatter formatter;

    /**
     * 默认 workspace（来自配置），当用户未指定 workspace 时使用
     */
    @Value("${agent.working-directory:${user.dir}}")
    private String defaultWorkspace;

    private final ProcessTreeKiller processKiller = new ProcessTreeKiller();

    /**
     * 共享线程池，用于输出读取
     * 替代每次执行创建新的 ExecutorService，避免资源泄漏
     */
    @Autowired(required = false)
    private ThreadPoolTaskExecutor taskExecutor;

    /**
     * 当 taskExecutor 不可用时的降级共享线程池
     */
    private volatile ExecutorService sharedExecutor;

    private volatile String detectedShell;
    private volatile boolean shellDetected = false;

    @PostConstruct
    public void init() {
        detectBash();
    }

    private ExecutorService getOrCreateExecutor() {
        if (taskExecutor != null) {
            return taskExecutor.getThreadPoolExecutor();
        }
        if (sharedExecutor == null) {
            synchronized (this) {
                if (sharedExecutor == null) {
                    sharedExecutor = new ThreadPoolExecutor(
                        2, 4, 60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(100),
                        r -> {
                            Thread t = new Thread(r, "bash-output-reader");
                            t.setDaemon(true);
                            return t;
                        }
                    );
                }
            }
        }
        return sharedExecutor;
    }

    /**
     * 解析有效的 workspace：
     * 1. 从 RequestContextHolder 获取用户指定的 workspace
     * 2. 校验是否存在且为目录，无效则降级到 defaultWorkspace
     */
    private String resolveEffectiveWorkspace() {
        String userWorkspace = RequestContextHolder.getWorkspace();
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

    /**
     * 执行命令（同步等待结果）
     */
    public String execute(String command, long timeoutMs) throws Exception {
        String effectiveWorkspace = resolveEffectiveWorkspace();
        SecurityInterceptor.CheckResult check = securityInterceptor.check(command, effectiveWorkspace);
        switch (check.type()) {
            case DENY:
                return formatter.formatError("安全拦截: " + check.message(), -1, command);
            case PENDING:
                return formatter.formatPending(check.ticketId(), check.message());
            case ALLOW:
                break;
        }
        return doExecute(command, timeoutMs, false);
    }

    /**
     * 后台执行命令
     */
    public String executeBackground(String command) throws Exception {
        String effectiveWorkspace = resolveEffectiveWorkspace();
        SecurityInterceptor.CheckResult check = securityInterceptor.check(command, effectiveWorkspace);
        if (check.type() == SecurityInterceptor.CheckResult.Type.DENY) {
            return formatter.formatError("安全拦截: " + check.message(), -1, command);
        }
        if (check.type() == SecurityInterceptor.CheckResult.Type.PENDING) {
            return formatter.formatPending(check.ticketId(), check.message());
        }
        return doExecute(command, 0, true);
    }

    private String doExecute(String command, long timeoutMs, boolean background) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        configureProcessBuilder(builder, command);
        builder.directory(new File(BashConstants.WORKSPACE));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String pid = String.valueOf(process.pid());
        log.info("[BashExecutor] Process started: PID={} cmd={}", pid, command);

        if (background) {
            ManagedProcess mp = new ManagedProcess(pid, process.toHandle(), command, "",
                    Instant.now(), ManagedProcess.ProcessState.RUNNING, true);
            processRegistry.register(mp);
            return formatter.formatBackgroundStarted(pid, command);
        }

        ManagedProcess mp = new ManagedProcess(pid, process.toHandle(), command, "",
                Instant.now(), ManagedProcess.ProcessState.RUNNING, false);
        processRegistry.register(mp);

        Charset charset = detectCharset();
        StringBuilder output = new StringBuilder();
        ExecutorService readerExecutor = getOrCreateExecutor();

        Future<?> readerFuture = readerExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (Exception e) {
                log.warn("[BashExecutor] Output reading failed", e);
            }
        });

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        String rawOutput = output.toString();

        if (!finished) {
            processKiller.kill(process);
            readerFuture.cancel(true);
            processRegistry.kill(pid);
            log.warn("[BashExecutor] Command timeout: {}ms cmd={}", timeoutMs, command);
            return formatter.formatTimeout(rawOutput, timeoutMs);
        }

        try {
            readerFuture.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Ignore — process already done
        }

        processRegistry.unregister(pid);

        int exitCode = process.exitValue();
        if (exitCode == 0) {
            return formatter.formatSuccess(rawOutput);
        } else {
            return formatter.formatError(rawOutput, exitCode, command);
        }
    }

    private void configureProcessBuilder(ProcessBuilder builder, String command) {
        String shell = detectBash();
        if (shell.equals("sh")) {
            builder.command("sh", "-c", command);
        } else if (shell.equals("bash")) {
            builder.command("bash", "-c", command);
        } else if (shell.equals("cmd")) {
            builder.command("cmd", "/c", command);
        } else {
            builder.command(shell, "-c", command);
        }
    }

    private synchronized String detectBash() {
        if (shellDetected) {
            return detectedShell;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            detectedShell = "sh";
            shellDetected = true;
            return detectedShell;
        }

        try {
            ProcessBuilder testBuilder = new ProcessBuilder("bash", "-c", "echo test");
            testBuilder.redirectErrorStream(true);
            Process test = testBuilder.start();
            if (test.waitFor(3, TimeUnit.SECONDS) && test.exitValue() == 0) {
                detectedShell = "bash";
                shellDetected = true;
                log.info("[BashExecutor] Detected global bash");
                return detectedShell;
            }
        } catch (Exception e) {
            // fall through
        }

        String[][] gitBashPaths = {
                {"C:\\Program Files\\Git\\bin\\bash.exe", "Git Bash (默认)"},
                {System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe", "Git Bash (用户)"},
        };
        for (String[] pathAndDesc : gitBashPaths) {
            File bash = new File(pathAndDesc[0]);
            if (bash.exists()) {
                detectedShell = bash.getAbsolutePath();
                shellDetected = true;
                log.info("[BashExecutor] Detected {}: {}", pathAndDesc[1], detectedShell);
                return detectedShell;
            }
        }

        detectedShell = "cmd";
        shellDetected = true;
        log.warn("[BashExecutor] No bash found, falling back to cmd");
        return detectedShell;
    }

    private Charset detectCharset() {
        Charset detected = Charset.defaultCharset();
        if (StandardCharsets.UTF_8.equals(detected) || StandardCharsets.ISO_8859_1.equals(detected)) {
            return detected;
        }
        try {
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            return detected;
        }
    }
}
