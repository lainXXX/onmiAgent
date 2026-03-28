package top.javarem.onmi.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路径审批服务
 * 支持：人工确认、目录白名单授权、并发锁控制、超时拒绝
 */
@Slf4j
@Service
public class PathApprovalService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // ANSI 颜色代码
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String RESET = "\u001B[0m";

    // 白名单
    private final Set<String> approvedDirectories = ConcurrentHashMap.newKeySet();

    // 全局读取器与锁
    private static final BufferedReader CONSOLE_READER = new BufferedReader(new InputStreamReader(System.in));
    private static final Object CONSOLE_LOCK = new Object();

    /**
     * 请求审批
     */
    public ApprovalResult requestApproval(String operationType, String message) {
        return requestApproval(operationType, message, DEFAULT_TIMEOUT_SECONDS);
    }

    public ApprovalResult requestApproval(String operationType, String message, int timeoutSeconds) {
        synchronized (CONSOLE_LOCK) {
            // 清除之前的滞留输入
            try { while (CONSOLE_READER.ready()) { CONSOLE_READER.readLine(); } } catch (IOException ignored) {}

            // 打印紧凑精简的提示
            System.out.println("\n" + RED + "⚠️ [安全拦截] Agent 申请越权操作：" + RESET);
            System.out.println("   " + YELLOW + "目标: " + message + RESET);
            System.out.print(CYAN + "👉 授权指令 [ y:单次 | d:放行该目录 | n:拒绝(默认" + timeoutSeconds + "s超时) ]: " + RESET);
            System.out.flush();

            return waitForApproval(timeoutSeconds);
        }
    }

    /**
     * 等待用户输入（非阻塞轮询）
     */
    private ApprovalResult waitForApproval(int timeoutSeconds) {
        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        try {
            while (System.currentTimeMillis() < endTime) {
                if (CONSOLE_READER.ready()) {
                    String input = CONSOLE_READER.readLine();
                    if (input != null) {
                        String cmd = input.trim().toLowerCase();
                        if ("y".equals(cmd) || "yes".equals(cmd)) return new ApprovalResult(true, "用户批准（单次）", null);
                        if ("d".equals(cmd) || "directory".equals(cmd)) return new ApprovalResult(true, "用户批准（目录授权）", "directory");
                        if ("n".equals(cmd) || "no".equals(cmd)) return new ApprovalResult(false, "用户拒绝", null);
                    }
                }
                Thread.sleep(100);
            }
            System.out.println("\n[系统提示] 审批超时，自动拒绝。");
            return new ApprovalResult(false, "审批超时", null);
        } catch (Exception e) {
            log.error("审批读取异常", e);
            return new ApprovalResult(false, "审批异常", null);
        }
    }

    /**
     * 校验路径是否在白名单
     */
    public boolean isApproved(String path) {
        try {
            Path target = Paths.get(path).toAbsolutePath().normalize();
            String targetStr = target.toString().replace("\\", "/");
            for (String dir : approvedDirectories) {
                if (targetStr.startsWith(dir.replace("\\", "/"))) return true;
            }
        } catch (Exception e) { log.warn("白名单检查异常", e); }
        return false;
    }

    /**
     * 自动授权目录
     */
    public void addToWhitelist(String path) {
        try {
            Path p = Paths.get(path).toAbsolutePath().normalize();
            String dir = (Files.isDirectory(p) ? p : p.getParent()).toString().replace("\\", "/");
            approvedDirectories.add(dir);
            System.out.println(CYAN + "📂 已授权目录: [" + dir + "] 后续访问自动放行。" + RESET);
        } catch (Exception e) {
            log.error("添加白名单失败", e);
        }
    }

    public record ApprovalResult(boolean approved, String reason, String scope) {}
}