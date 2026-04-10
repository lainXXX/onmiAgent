package top.javarem.omni.tool.bash.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import top.javarem.omni.tool.bash.*;

/**
 * Bash 工具自动配置
 *
 * <p>所有 Bash 组件均已通过 {@link org.springframework.stereotype.Component} 注解
 * 实现自动扫描。此配置类仅用于：</p>
 * <ul>
 *   <li>启用 {@link BashToolProperties} 配置绑定</li>
 *   <li>声明配置类存在，便于 Spring Boot 扫描</li>
 *   <li>通过 {@code ai.tool.bash.enabled=false} 完全禁用</li>
 * </ul>
 *
 * <h3>组件装配顺序（由 Spring 自动管理）：</h3>
 * <ol>
 *   <li>DangerousPatternValidator — 危险命令检测</li>
 *   <li>PathNormalizer — 路径归一化</li>
 *   <li>ApprovalService — 审批服务（Web 场景降级）</li>
 *   <li>SecurityInterceptor — 安全拦截链</li>
 *   <li>CommandSemantics — 退出码语义解释</li>
 *   <li>WorkingDirectoryManager — 工作目录跟踪</li>
 *   <li>ResponseFormatter — 响应格式化</li>
 *   <li>ProcessRegistry — 进程注册表</li>
 *   <li>ProcessTreeKiller — 进程树终结器</li>
 *   <li>BashExecutor — 核心执行器</li>
 *   <li>BashToolConfig — Spring AI Tool 接口</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.tool.bash", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BashToolProperties.class)
public class BashToolAutoConfiguration {
    // 所有组件通过 @Component 注解自动装配
    // 此处仅作为配置入口和装配顺序文档
}
