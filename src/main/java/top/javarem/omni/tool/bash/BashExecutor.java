package top.javarem.omni.tool.bash;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

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

    @Autowired
    private WorkingDirectoryManager workingDirectoryManager;

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
     * 1. 使用传入的 userWorkspace
     * 2. 校验是否存在且为目录，无效则降级到 defaultWorkspace
     */
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

    /**
     * 执行命令（同步等待结果）
     */
    public String execute(String command, long timeoutMs, String userWorkspace) throws Exception {
        return execute(command, timeoutMs, userWorkspace, false);
    }

    /**
     * 执行命令（同步等待结果）
     * @param acceptEdits 编辑模式：放行文件系统命令的审批
     */
    public String execute(String command, long timeoutMs, String userWorkspace, boolean acceptEdits) throws Exception {
        String effectiveWorkspace = resolveEffectiveWorkspace(userWorkspace);
        workingDirectoryManager.syncWorkspace(effectiveWorkspace);
        SecurityInterceptor.CheckResult check = securityInterceptor.check(command, effectiveWorkspace, acceptEdits);
        boolean destructiveWarning = false;
        switch (check.type()) {
            case DENY:
                return formatter.formatError("安全拦截: " + check.message(), -1, command);
            case PENDING:
                return formatter.formatPending(check.ticketId(), check.message());
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
     * 后台执行命令
     */
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
        ProcessBuilder builder = new ProcessBuilder();
        configureProcessBuilder(builder, command);
        // 优先使用 WorkingDirectoryManager 跟踪的目录，支持 cd 命令动态切换
        Path trackedDir = workingDirectoryManager.getCurrentDir();
        builder.directory(trackedDir.toFile());
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
            // 超时时也跟踪目录（命令可能改变了目录）
            workingDirectoryManager.trackAndValidate(rawOutput, null);
            return formatter.formatTimeout(rawOutput, timeoutMs);
        }

        try {
            readerFuture.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Ignore — process already done
        }

        processRegistry.unregister(pid);

        // 跟踪命令执行后的工作目录变化（成功或失败都要跟踪）
        Path newDir = workingDirectoryManager.trackAndValidate(rawOutput, null);
        if (!newDir.equals(workingDirectoryManager.getCurrentDir())) {
            log.info("[BashExecutor] 工作目录切换: -> {}", newDir);
        }

        int exitCode = process.exitValue();
        if (exitCode == 0) {
            return formatter.formatSuccess(rawOutput);
        } else {
            return formatter.formatError(rawOutput, exitCode, command);
        }
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
