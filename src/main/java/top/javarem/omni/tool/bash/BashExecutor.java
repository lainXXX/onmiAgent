package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.Charset;
import java.util.concurrent.*;

/**
 * Bash 命令执行器
 */
@Slf4j
public class BashExecutor {

    private final ProcessTreeKiller processTreeKiller;

    public BashExecutor() {
        this.processTreeKiller = new ProcessTreeKiller();
    }

    /**
     * 执行命令
     */
    public String execute(String command, int timeoutSeconds) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            processBuilder.command("cmd", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }

        processBuilder.directory(new File(BashConstants.WORKSPACE));
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        String encoding = System.getProperty("sun.jnu.encoding", "GBK");
        Charset charset = Charset.forName(encoding);

        Future<?> future = executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                log.warn("读取命令输出失败", e);
            }
        });

        boolean finished = false;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String result;
        if (finished) {
            try {
                future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // 忽略
            }
            int exitCode = process.exitValue();
            result = OutputProcessor.process(output.toString(), exitCode, false);
        } else {
            processTreeKiller.kill(process);
            future.cancel(true);
            result = OutputProcessor.process(output.toString(), -1, true);
        }

        executor.shutdownNow();
        return result;
    }
}
