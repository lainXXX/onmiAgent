package top.javarem.omni.tool.bash;

import java.util.regex.Pattern;

/**
 * Bash 工具常量定义
 */
public final class BashConstants {

    private BashConstants() {}

    /** 默认超时时间（秒） */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** 最大输出字符数 */
    public static final int MAX_OUTPUT_CHARS = 6000;

    /** 截断保留头部字符数 */
    public static final int KEEP_START = 1000;

    /** 截断保留尾部字符数 */
    public static final int KEEP_END = 5000;

    /** 超时强制终止等待时间 */
    public static final int KILL_WAIT_SECONDS = 3;

    /** 最大允许超时时间 */
    public static final int MAX_TIMEOUT_SECONDS = 300;

    /** 工作目录 */
    public static final String WORKSPACE = System.getProperty("user.dir");

    /** 当前进程 PID */
    public static final long CURRENT_PID = ProcessHandle.current().pid();

    /** 高危命令模式 */
    public static final Pattern[] DANGEROUS_PATTERNS = {
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

            // 交互式命令（空运行时阻塞）
            Pattern.compile("^\\s*(vim?|nano|emacs|less|more|pine|elm|mutt)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(irb|mysql|psql|sqlite3)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(powershell|cmd|pause)\\s*$", Pattern.CASE_INSENSITIVE),

            // 网络扫描/攻击
            Pattern.compile("nmap\\s+(-O|-sS|-sV)\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("netcat\\s+-[lzw]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("curl\\s+(http|ftp).*\\b-I\\b.*", Pattern.CASE_INSENSITIVE),

            // 下载执行
            Pattern.compile("curl\\s+.*\\|\\s*(bash|sh|python|perl)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("wget\\s+.*\\|\\s*(bash|sh|python|perl)", Pattern.CASE_INSENSITIVE),
    };
}
