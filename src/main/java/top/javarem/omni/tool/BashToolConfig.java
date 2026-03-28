package top.javarem.onmi.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.onmi.tool.file.GrepToolConfig;

import java.io.*;
import java.nio.charset.Charset;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Bash 终端命令执行工具
 * 执行系统命令（编译、测试、环境勘探）
 * 包含沙箱隔离、高危拦截、智能截断、超时强杀
 */
@Component
@Slf4j
public class BashToolConfig implements AgentTool {

    /**
     * 默认超时时间（秒）
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * 最大输出字符数
     */
    private static final int MAX_OUTPUT_CHARS = 6000;

    /**
     * 截断保留字符数：头部（启动参数）、尾部（报错堆栈）
     */
    private static final int KEEP_START = 1000;
    private static final int KEEP_END = 5000;

    /**
     * 高危命令模式
     */
    private static final Pattern DANGEROUS_PATTERNS[] = {
            // 破坏性删除
            Pattern.compile("rm\\s+-rf\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rmdir\\s+/s\\s+/q", Pattern.CASE_INSENSITIVE),
            Pattern.compile("del\\s+/[sq]\\s+/[qa]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("format\\s+[a-z]:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("mkfs\\s+", Pattern.CASE_INSENSITIVE),

            // 数据库危险操作
            Pattern.compile("DROP\\s+DATABASE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+TABLE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DELETE\\s+FROM\\s+\\w+\\s*;?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),

            // 系统修改
            Pattern.compile("chmod\\s+777", Pattern.CASE_INSENSITIVE),
            Pattern.compile("sysctl\\s+-w", Pattern.CASE_INSENSITIVE),
            Pattern.compile("reg\\s+(add|delete)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("systemctl\\s+stop\\s+(sshd|cron|network)", Pattern.CASE_INSENSITIVE),

            // Windows 高危操作
            Pattern.compile("Remove-Item\\s+-Recurse", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Stop-Process", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Set-ExecutionPolicy\\s+Unrestricted", Pattern.CASE_INSENSITIVE),

            // 交互式命令（空运行时会被阻塞，带参数则允许）
            Pattern.compile("^\\s*(vim?|nano|emacs|less|more|pine|elm|mutt)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(irb|mysql|psql|sqlite3)\\s*$", Pattern.CASE_INSENSITIVE),
            // Windows 交互式命令（空运行阻塞）
            Pattern.compile("^\\s*(powershell|cmd|pause)\\s*$", Pattern.CASE_INSENSITIVE),

            // 网络扫描/攻击
            Pattern.compile("nmap\\s+(-O|-sS|-sV)\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("netcat\\s+-[lzw]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("curl\\s+(http|ftp).*\\b-I\\b.*", Pattern.CASE_INSENSITIVE),

            // 下载执行
            Pattern.compile("curl\\s+.*\\|\\s*(bash|sh|python|perl)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("wget\\s+.*\\|\\s*(bash|sh|python|perl)", Pattern.CASE_INSENSITIVE),
    };

    /**
     * 工作目录
     */
    private static final String WORKSPACE = System.getProperty("user.dir");

    private final PathApprovalService approvalService;

    public BashToolConfig(PathApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * 执行终端命令
     *
     * @param command 完整的 Shell/CMD 命令
     * @param timeout 超时时间（秒），默认60
     * @return 命令执行结果
     */
    @Tool(name = "bash", description = """
        适用场景：编译构建(mvn/npm)、运行测试、查看进程/端口、Git版本控制、环境探测。
        禁止场景：1.交互式命令(如vim/python/node REPL)；2.后台持续运行服务(如直接运行npm start)；3.高危删除(rm/del)或系统关键修改。
        约束：必须使用 Windows 语法(如 dir/type)。执行复杂构建务必添加 --batch-mode 以防阻塞
    """)
    public String bash(
            @ToolParam(description = "完整命令。Windows用DOS语法，支持mvn/npm等构建工具") String command,
            @ToolParam(description = "超时秒数。默认60", required = false) Integer timeout) {
        log.info("执行命令: {}", command);
        // 1. 参数校验
        if (command == null || command.trim().isEmpty()) {
            return buildErrorResponse("命令不能为空", "请提供要执行的命令");
        }

        String normalizedCommand = command.trim();

        // 2. 高危命令检测
        DangerousCheckResult dangerousCheck = checkDangerousCommand(normalizedCommand);
        if (dangerousCheck.isDangerous()) {
            // 需要审批
            ApprovalCheckResult approvalResult = requestCommandApproval(normalizedCommand, dangerousCheck.reason);
            if (!approvalResult.approved()) {
                return buildDeniedResponse(normalizedCommand, approvalResult.reason);
            }
        }

        // 3. 规范化超时时间
        int timeoutSeconds = (timeout == null || timeout < 1) ? DEFAULT_TIMEOUT_SECONDS : Math.min(timeout, 300);

        // 4. 执行命令
        try {
            return executeCommand(normalizedCommand, timeoutSeconds);
        } catch (GrepToolConfig.AgentToolSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("命令执行失败: command={}", normalizedCommand, e);
            return buildErrorResponse("命令执行失败: " + e.getMessage(),
                    "请检查命令是否正确");
        }
    }

    /**
     * 检查是否为危险命令
     */
    private DangerousCheckResult checkDangerousCommand(String command) {
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                String reason = "检测到高危操作模式: " + pattern.pattern();
                return new DangerousCheckResult(true, reason);
            }
        }
        return new DangerousCheckResult(false, null);
    }

    /**
     * 请求命令审批
     */
    private ApprovalCheckResult requestCommandApproval(String command, String reason) {
        String message = "⚠️ 安全警告：Agent 尝试执行高危命令\n\n" +
                "执行的命令: " + command + "\n\n" +
                "风险原因: " + reason + "\n\n" +
                "这可能会对系统造成不可逆的影响。是否批准执行？";

        PathApprovalService.ApprovalResult result = approvalService.requestApproval(
                "COMMAND_EXEC",
                message,
                30
        );

        if (!result.approved()) {
            return new ApprovalCheckResult(false, result.reason());
        }

        return new ApprovalCheckResult(true, null);
    }

    /**
     * 执行命令
     */
    private String executeCommand(String command, int timeoutSeconds) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();

        // 根据操作系统选择 shell
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            processBuilder.command("cmd", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }

        // 设置工作目录
        processBuilder.directory(new File(WORKSPACE));

        // 合并错误流和标准输出
        processBuilder.redirectErrorStream(true);

        // 启动进程
        Process process = processBuilder.start();

        // 使用线程读取输出
        StringBuilder output = new StringBuilder();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 自动探测 Windows 系统的底层编码，默认回退到 GBK
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

        // 等待进程结束或超时
        boolean finished = false;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String result;
        if (finished) {
            // 等待输出读取完成
            try {
                future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // 忽略
            }

            int exitCode = process.exitValue();
            result = processOutput(output.toString(), exitCode, false);
        } else {
            // 超时，强制终止进程及子进程
            destroyProcessTree(process);
            future.cancel(true);
            result = processOutput(output.toString(), -1, true);
        }

        executor.shutdownNow();
        return result;
    }

    /**
     * 递归销毁进程树（处理僵尸进程）
     */
    private void destroyProcessTree(Process process) {
        try {
            process.destroyForcibly();

            // 尝试获取进程 PID 并杀掉子进程
            long pid = process.pid();
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("windows")) {
                // Windows: 使用 taskkill /T /F 杀掉进程树
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "taskkill /T /F /PID " + pid);
                pb.redirectErrorStream(true);
                Process killProcess = pb.start();
                try {
                    killProcess.waitFor(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                killProcess.destroyForcibly();
            } else {
                // Unix: 使用 pkill -P 杀掉子进程
                try {
                    ProcessBuilder pb = new ProcessBuilder("pkill", "-P", String.valueOf(pid));
                    pb.redirectErrorStream(true);
                    Process killProcess = pb.start();
                    try {
                        killProcess.waitFor(3, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                    killProcess.destroyForcibly();
                } catch (Exception ignored) {
                    // pkill 不可用时忽略
                }
            }
        } catch (Exception e) {
            log.warn("销毁进程树失败", e);
        }
    }

    /**
     * 去除 ANSI 转义序列
     */
    private String stripAnsiCodes(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        // 移除 ANSI 颜色转义序列 (如 \u001B[32m, \u001B[0m 等)
        return input.replaceAll("\\u001B\\[[;\\d]*[A-Za-z]", "")
                   .replaceAll("\\u001B\\]0;[^\u001B]*\\u001B\\\\", "")
                   .replaceAll("\\u001B\\]P[0-9a-fA-F][^\u001B]*\\u001B\\\\", "");
    }

    /**
     * 处理输出（包含智能截断）
     */
    private String processOutput(String rawOutput, int exitCode, boolean timeout) {
        // 去除 ANSI 转义序列
        String cleanOutput = stripAnsiCodes(rawOutput);

        StringBuilder sb = new StringBuilder();

        if (timeout) {
            sb.append("⏱️ 命令执行超时（已强制终止）\n\n");
        } else {
            sb.append(exitCode == 0 ? "✅ 命令执行成功\n\n" : "❌ 命令执行失败 (退出码: " + exitCode + ")\n\n");
        }

        sb.append("💻 命令输出:\n");
        sb.append("─────────────────────────────────────────\n");

        if (cleanOutput.isEmpty()) {
            sb.append("(无输出)");
        } else if (cleanOutput.length() <= MAX_OUTPUT_CHARS) {
            sb.append(cleanOutput);
        } else {
            // 智能截断：保留头部（启动参数）和尾部（报错堆栈）
            String start = cleanOutput.substring(0, Math.min(KEEP_START, cleanOutput.length()));
            int endStart = Math.max(0, cleanOutput.length() - KEEP_END);
            String end = cleanOutput.substring(endStart);

            sb.append(start);
            sb.append("\n\n... [系统警告：输出超长，中间部分已省略以节省 Token] ...\n\n");
            sb.append(end);
            sb.append("\n─────────────────────────────────────────\n");
            sb.append("⚠️ 输出已截断（原始长度: ").append(cleanOutput.length()).append(" 字符）");
        }

        return sb.toString();
    }

    /**
     * 构建成功响应
     */
    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }

    /**
     * 构建拒绝响应
     */
    private String buildDeniedResponse(String command, String reason) {
        return "⛔ 命令执行被拒绝\n\n" +
                "执行的命令: " + command + "\n\n" +
                "原因: " + reason + "\n\n" +
                "💡 为了系统安全，某些高危命令需要人工审批才能执行。";
    }

    /**
     * 危险命令检查结果
     */
    private record DangerousCheckResult(boolean isDangerous, String reason) {
    }

    /**
     * 审批检查结果
     */
    private record ApprovalCheckResult(boolean approved, String reason) {
    }
}
