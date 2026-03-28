package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.*;

/**
 * Bash 命令执行器
 */
@Component
@Slf4j
public class BashExecutor {

    private final ResponseFormatter formatter;
    private final ProcessTreeKiller processKiller;

    public BashExecutor(ResponseFormatter formatter) {
        this.formatter = formatter;
        this.processKiller = new ProcessTreeKiller();
    }

    /**
     * 后台执行命令
     * @return 包含 PID 的结果
     */
    public String executeBackground(String command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        configureProcessBuilder(builder, command);
        builder.directory(new File(BashConstants.WORKSPACE));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        log.info("[BashExecutor] 后台进程已启动: PID={}", process.pid());

        return String.format("✅ 后台命令已启动\n\nPID: %d\n命令: %s", process.pid(), command);
    }

    /**
     * 执行命令（同步等待结果）
     */
    public String execute(String command, long timeoutMs) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        configureProcessBuilder(builder, command);
        builder.directory(new File(BashConstants.WORKSPACE));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        log.debug("[BashExecutor] 进程已启动: PID={}", process.pid());

        StringBuilder output = new StringBuilder();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String encoding = System.getProperty("sun.jnu.encoding", "GBK");
        Charset charset = Charset.forName(encoding);

        Future<?> readerFuture = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (Exception e) {
                log.warn("[BashExecutor] 读取输出失败", e);
            }
        });

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        String rawOutput = output.toString();

        if (!finished) {
            processKiller.kill(process);
            readerFuture.cancel(true);
            log.warn("[BashExecutor] 命令超时: {} ms, command={}", timeoutMs, command);
            return formatter.formatTimeout(rawOutput, timeoutMs);
        }

        try {
            readerFuture.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // 忽略
        }

        int exitCode = process.exitValue();

        if (exitCode == 0) {
            return formatter.formatSuccess(rawOutput);
        } else {
            return formatter.formatError(rawOutput, exitCode);
        }
    }

    private void configureProcessBuilder(ProcessBuilder builder, String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            builder.command("cmd", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
    }
}
