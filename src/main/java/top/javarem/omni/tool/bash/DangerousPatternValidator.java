package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 危险命令模式验证器
 */
@Slf4j
public class DangerousPatternValidator {

    /**
     * 检查是否为危险命令
     */
    public DangerousCheckResult validate(String command) {
        for (Pattern pattern : BashConstants.DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                String reason = "检测到高危操作模式: " + pattern.pattern();
                return new DangerousCheckResult(true, reason);
            }
        }
        return new DangerousCheckResult(false, null);
    }
}
