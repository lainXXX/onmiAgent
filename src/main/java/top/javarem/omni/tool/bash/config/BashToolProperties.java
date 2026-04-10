package top.javarem.omni.tool.bash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bash 工具配置属性
 *
 * <p>通过 {@code application.yml} 中的 {@code ai.tool.bash.} 前缀配置。</p>
 *
 * <h3>配置示例：</h3>
 * <pre>
 * ai:
 *   tool:
 *     bash:
 *       default-timeout-ms: 120000
 *       max-timeout-ms: 600000
 *       max-concurrent-commands: 4
 *       workspace: /path/to/project
 *       sandbox-enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "ai.tool.bash")
public class BashToolProperties {

    /**
     * 默认超时时间（毫秒）
     */
    private long defaultTimeoutMs = 120_000;

    /**
     * 最大超时时间（毫秒）
     */
    private long maxTimeoutMs = 600_000;

    /**
     * 最大并发命令数
     */
    private int maxConcurrentCommands = 4;

    /**
     * 工作目录
     */
    private String workspace = System.getProperty("user.dir");

    /**
     * 是否启用沙箱
     */
    private boolean sandboxEnabled = true;

    /**
     * 允许执行的命令白名单（空 = 全部允许）
     */
    private String[] allowedCommands = {};

    /**
     * 禁止执行的命令黑名单
     */
    private String[] blockedCommands = {};

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getMaxTimeoutMs() {
        return maxTimeoutMs;
    }

    public void setMaxTimeoutMs(long maxTimeoutMs) {
        this.maxTimeoutMs = maxTimeoutMs;
    }

    public int getMaxConcurrentCommands() {
        return maxConcurrentCommands;
    }

    public void setMaxConcurrentCommands(int maxConcurrentCommands) {
        this.maxConcurrentCommands = maxConcurrentCommands;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    public void setSandboxEnabled(boolean sandboxEnabled) {
        this.sandboxEnabled = sandboxEnabled;
    }

    public String[] getAllowedCommands() {
        return allowedCommands;
    }

    public void setAllowedCommands(String[] allowedCommands) {
        this.allowedCommands = allowedCommands;
    }

    public String[] getBlockedCommands() {
        return blockedCommands;
    }

    public void setBlockedCommands(String[] blockedCommands) {
        this.blockedCommands = blockedCommands;
    }
}
