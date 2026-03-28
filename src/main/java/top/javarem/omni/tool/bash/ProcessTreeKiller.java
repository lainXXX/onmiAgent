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
        try {
            process.destroyForcibly();

            long pid = process.pid();
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("windows")) {
                killOnWindows(pid);
            } else {
                killOnUnix(pid);
            }
        } catch (Exception e) {
            log.warn("销毁进程树失败", e);
        }
    }

    private void killOnWindows(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "taskkill /T /F /PID " + pid);
            pb.redirectErrorStream(true);
            Process killProcess = pb.start();
            killProcess.waitFor(BashConstants.KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            killProcess.destroyForcibly();
        } catch (Exception ignored) {}
    }

    private void killOnUnix(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pkill", "-P", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process killProcess = pb.start();
            killProcess.waitFor(BashConstants.KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            killProcess.destroyForcibly();
        } catch (Exception ignored) {}
    }
}
