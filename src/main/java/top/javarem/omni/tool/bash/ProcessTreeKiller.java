package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 进程树销毁器
 * 用于超时强制终止进程及其所有子进程
 */
@Slf4j
public class ProcessTreeKiller {

    /**
     * 销毁进程及其子进程
     */
    public void kill(Process process) {
        long pid = process.pid();
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
        }

        String[] killCmd = OsHelper.current().buildKillCommand(pid);
        runCommand(killCmd);
    }

    private void runCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process killProcess = pb.start();
            boolean completed = killProcess.waitFor(BashConstants.KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[ProcessTreeKiller] Kill command did not complete: {}", String.join(" ", command));
            }
        } catch (Exception e) {
            log.warn("[ProcessTreeKiller] Failed to execute kill command: {} - {}", String.join(" ", command), e.getMessage());
        }
    }
}
