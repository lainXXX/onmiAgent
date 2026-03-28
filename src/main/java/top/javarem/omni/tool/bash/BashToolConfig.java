package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.AgentTool;
import top.javarem.omni.tool.PathApprovalService;
import top.javarem.omni.tool.file.GrepToolConfig;

/**
 * Bash 终端命令执行工具
 * 执行系统命令（编译、测试、环境勘探）
 * 包含沙箱隔离、高危拦截、智能截断、超时强杀、防自杀
 */
@Component
@Slf4j
public class BashToolConfig implements AgentTool {

    private final DangerousPatternValidator validator;
    private final SuicideCommandDetector suicideDetector;
    private final CommandApprover approver;
    private final BashExecutor executor;

    public BashToolConfig(PathApprovalService approvalService) {
        this.validator = new DangerousPatternValidator();
        this.suicideDetector = new SuicideCommandDetector();
        this.approver = new CommandApprover(approvalService);
        this.executor = new BashExecutor();
        log.info("BashToolConfig 初始化完成，当前进程 PID: {}", BashConstants.CURRENT_PID);
    }

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
            return ResponseBuilder.buildError("命令不能为空", "请提供要执行的命令");
        }

        String normalizedCommand = command.trim();

        // 2. 防自杀检查
        if (suicideDetector.isSuicide(normalizedCommand)) {
            log.warn("检测到自杀命令，已拦截: {}", normalizedCommand);
            return ResponseBuilder.buildSuicideBlocked(normalizedCommand);
        }

        // 3. 危险命令检测
        DangerousCheckResult check = validator.validate(normalizedCommand);
        if (check.isDangerous()) {
            ApprovalCheckResult approval = approver.requestApproval(normalizedCommand, check.reason());
            if (!approval.approved()) {
                return ResponseBuilder.buildDenied(normalizedCommand, approval.reason());
            }
        }

        // 4. 规范化超时时间
        int timeoutSeconds = (timeout == null || timeout < 1)
                ? BashConstants.DEFAULT_TIMEOUT_SECONDS
                : Math.min(timeout, BashConstants.MAX_TIMEOUT_SECONDS);

        // 5. 执行命令
        try {
            return executor.execute(normalizedCommand, timeoutSeconds);
        } catch (GrepToolConfig.AgentToolSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("命令执行失败: command={}", normalizedCommand, e);
            return ResponseBuilder.buildError("命令执行失败: " + e.getMessage(), "请检查命令是否正确");
        }
    }
}
