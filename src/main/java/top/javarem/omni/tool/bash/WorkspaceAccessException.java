package top.javarem.omni.tool.bash;

/**
 * 表示命令尝试访问 WORKSPACE 之外的路径。
 *
 * <p>与 {@link SecurityException} 的区别：
 * <ul>
 *   <li>SecurityException：硬拒绝，不可审批（如注入攻击）</li>
 *   <li>WorkspaceAccessException：软拒绝，用户可主动审批</li>
 * </ul>
 */
public class WorkspaceAccessException extends SecurityException {

    private final String attemptedPath;

    public WorkspaceAccessException(String attemptedPath) {
        super("尝试访问 WORKSPACE 之外的路径: " + attemptedPath);
        this.attemptedPath = attemptedPath;
    }

    public String getAttemptedPath() {
        return attemptedPath;
    }
}
