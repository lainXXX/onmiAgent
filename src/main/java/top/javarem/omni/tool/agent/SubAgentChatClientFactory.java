package top.javarem.omni.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import top.javarem.omni.loader.SkillLoader;
import top.javarem.omni.tool.ToolsManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent ChatClient 工厂
 * 支持 Worktree 沙盒隔离，并实现了完整的 ReAct (思考-行动) 循环，完美处理 Tool Calls。
 */
@Slf4j
@Component
public class SubAgentChatClientFactory {

    private final ChatModel chatModel;
    private final ToolsManager toolsManager;
    private final AgentSessionManager sessionManager;
    private final WorktreeManager worktreeManager;
    private final SkillLoader skillLoader;

    @Value("${agent.max-iterations:50}")
    private int maxIterations;

    @Value("${agent.working-directory:${user.dir}}")
    private String workingDirectory;

    public SubAgentChatClientFactory(
            @Qualifier("miniMaxChatModel") ChatModel chatModel,
            ToolsManager toolsManager,
            AgentSessionManager sessionManager,
            WorktreeManager worktreeManager,
            SkillLoader skillLoader) {
        this.chatModel = chatModel;
        this.toolsManager = toolsManager;
        this.sessionManager = sessionManager;
        this.worktreeManager = worktreeManager;
        this.skillLoader = skillLoader;
    }

    public AgentResult execute(String taskId, AgentType type, String prompt, String conversationId, String userId) {
        return execute(taskId, type, prompt, conversationId, userId, null, null);
    }

    public AgentResult execute(String taskId, AgentType type, String prompt, String conversationId, String userId,
                               String resumeFromTaskId, Path worktreePath) {
        long startTime = System.currentTimeMillis();
        log.info("[SubAgentFactory] 启动子Agent: taskId={}, type={}, userId={}, resumeFrom={}, worktree={}",
                taskId, type.getValue(), userId, resumeFromTaskId, worktreePath);

        try {
            Path effectiveWorkDir = worktreePath != null ? worktreePath : Path.of(workingDirectory);
            String systemPrompt = buildSystemPrompt(type, effectiveWorkDir.toString());

            List<Message> messages;
            if (resumeFromTaskId != null && sessionManager.sessionExists(resumeFromTaskId)) {
                messages = new ArrayList<>(sessionManager.buildResumePrompt(resumeFromTaskId, prompt));
            } else {
                sessionManager.createSession(taskId, type.getValue(), prompt);
                messages = new ArrayList<>();
                messages.add(new SystemMessage(systemPrompt));
                messages.add(new UserMessage(prompt));
            }

            Map<String, Object> toolContext = new HashMap<>();
            toolContext.put("userId", userId);
            toolContext.put("taskId", taskId);
            toolContext.put("agentType", type.getValue());
            if (worktreePath != null) {
                toolContext.put("worktreePath", worktreePath.toAbsolutePath().toString());
            }

            String finalOutput = executeLoop(taskId, type, messages, toolContext);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[SubAgentFactory] 子Agent完成: taskId={}, duration={}ms", taskId, duration);

            return AgentResult.completed(taskId, type.getValue(), finalOutput, duration,
                    worktreePath != null ? worktreePath.toString() : null);

        } catch (Exception e) {
            log.error("[SubAgentFactory] 子Agent执行失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return AgentResult.failed(taskId, type.getValue(), e.getMessage());
        }
    }

    /**
     * 核心 ReAct 循环 (手动接管 ToolCalling 逻辑)
     */
    private String executeLoop(String taskId, AgentType type, List<Message> messages, Map<String, Object> toolContext) {
        if (type.isOneShot()) {
            return executeOneShot(taskId, type, messages, toolContext);
        }

        int iterations = 0;
        String lastOutputText = null;

        List<ToolCallback> filteredCallbacks = getFilteredToolCallbacks(type);

        // 【修复点】：使用 defaultToolCallbacks 接收编程式工具实例
        ChatClient client = ChatClient.builder(chatModel)
                .defaultToolCallbacks(filteredCallbacks.toArray(new ToolCallback[0]))
                .build();

        // 禁用框架自动执行工具，由我们的 while 循环接管，从而记录思考过程
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        while (iterations < maxIterations) {
            iterations++;
            log.debug("[SubAgentFactory] 进入 ReAct 迭代: taskId={}, iteration={}", taskId, iterations);

            try {
                ChatResponse response = client.prompt()
                        .messages(new ArrayList<>(messages))
                        .toolContext(toolContext)
                        .options(options) // 强制禁用自动执行，暴露出 ToolCalls 供下面处理
                        .call()
                        .chatResponse();

                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    lastOutputText = extractLastAssistantText(messages);
                    break;
                }

                // 1. 获取模型生成的包含内容的 AssistantMessage
                AssistantMessage assistantMessage = response.getResult().getOutput();
                messages.add(assistantMessage);

                // =================================================================================
                // 2. 核心优化：ReAct 循环中的 Action 阶段 (处理 ToolCalls)
                // =================================================================================
                if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
                    List<String> requestedTools = assistantMessage.getToolCalls().stream()
                            .map(AssistantMessage.ToolCall::name).toList();
                    log.info("[SubAgentFactory] 模型请求执行工具: taskId={}, tools={}", taskId, requestedTools);

                    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

                    // 遍历执行所有被请求的工具
                    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                        String toolName = toolCall.name();
                        String arguments = toolCall.arguments();
                        String toolResult;

                        try {
                            ToolCallback callback = filteredCallbacks.stream()
                                    .filter(cb -> cb.getToolDefinition().name().equals(toolName))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException("模型调用了未授权或不存在的工具: " + toolName));

                            // 执行工具
                            log.debug("[SubAgentFactory] 执行工具 {} 参数 {}", toolName, arguments);
                            toolResult = invokeToolWithContext(callback, arguments, toolContext);
                        } catch (Exception e) {
                            log.error("[SubAgentFactory] 工具执行异常: {}", toolName, e);
                            toolResult = "Error executing tool: " + e.getMessage();
                        }

                        toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolResult));
                    }

                    // 将执行结果包装为 ToolResponseMessage 并放入上下文中
                    ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(toolResponses).build();
                    messages.add(toolResponseMessage);

                    // 记录会话状态，方便审计和 Resume
                    sessionManager.addAssistantMessage(taskId, "【工具执行完毕】" + requestedTools);

                    // 继续下一轮循环，将工具执行结果交还给模型进行下一轮 Reasoning
                    continue;
                }

                // =================================================================================
                // 3. 处理常规文本输出 (Reasoning 阶段)
                // =================================================================================
                String contentText = assistantMessage.getText();
                if (contentText != null && !contentText.isBlank()) {
                    sessionManager.addAssistantMessage(taskId, contentText);
                    lastOutputText = contentText;
                }

                if (isTerminalOutput(contentText)) {
                    log.info("[SubAgentFactory] 命中任务完成标识: taskId={}, iteration={}", taskId, iterations);
                    break;
                }

            } catch (Exception e) {
                log.error("[SubAgentFactory] 迭代发生异常: taskId={}, iteration={}", taskId, iterations, e);
                lastOutputText = "执行过程中出错中断: " + e.getMessage();
                break;
            }
        }

        if (iterations >= maxIterations) {
            log.warn("[SubAgentFactory] 达到最大迭代次数保护阈值: taskId={}", taskId);
            lastOutputText = (lastOutputText != null ? lastOutputText + "\n\n" : "") +
                    "[系统警告: 达到最大循环次数 " + maxIterations + "，已被强制终止]";
        }

        sessionManager.updateLastOutput(taskId, lastOutputText);
        return lastOutputText;
    }

    private String executeOneShot(String taskId, AgentType type, List<Message> messages, Map<String, Object> toolContext) {
        List<ToolCallback> filteredCallbacks = getFilteredToolCallbacks(type);

        // 【修复点】：使用 defaultToolCallbacks
        ChatClient client = ChatClient.builder(chatModel)
                .defaultToolCallbacks(filteredCallbacks.toArray(new ToolCallback[0]))
                .build();

        try {
            // OneShot 模式我们允许框架自动执行内部工具（如果大模型觉得有必要调用的话）
            ChatResponse response = client.prompt()
                    .messages(new ArrayList<>(messages))
                    .toolContext(toolContext)
                    .call()
                    .chatResponse();

            if (response == null || response.getResult() == null) {
                return extractLastAssistantText(messages);
            }

            String content = response.getResult().getOutput().getText();
            if (content != null && !content.isBlank()) {
                sessionManager.addAssistantMessage(taskId, content);
                sessionManager.updateLastOutput(taskId, content);
                return content;
            } else {
                return extractLastAssistantText(messages);
            }

        } catch (Exception e) {
            log.error("[SubAgentFactory] One-Shot执行异常", e);
            return "执行出错: " + e.getMessage();
        }
    }

    private List<ToolCallback> getFilteredToolCallbacks(AgentType type) {
        return toolsManager.getToolCallbacks().stream()
                .filter(cb -> type.isToolAllowed(cb.getToolDefinition().name()))
                .toList();
    }

    private String extractLastAssistantText(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof AssistantMessage)
                .reduce((first, second) -> second)
                .map(m -> ((AssistantMessage) m).getText())
                .orElse("Agent 执行结束，但未产生有效文本输出");
    }

    private boolean isTerminalOutput(String text) {
        if (text == null || text.isBlank()) return false;

        String lower = text.toLowerCase().trim();
        return lower.endsWith("任务完成") ||
                lower.endsWith("完成") ||
                lower.endsWith("done") ||
                lower.contains("【总结】") ||
                lower.contains("[总结]") ||
                lower.contains("final result");
    }

    private String buildSystemPrompt(AgentType type, String workDir) {
        String basePrompt = switch (type) {
            case EXPLORE -> """
                你是一个专业的代码探索 Agent。

                职责：
                - 深入分析代码库结构
                - 理解组件之间的关系和依赖
                - 识别关键文件和模块
                - 提供清晰的分析报告

                工作方式：
                1. 先用 Glob 了解整体结构
                2. 用 Grep 搜索关键代码
                3. 用 Read 深入理解具体实现
                4. Bash工具只可使用只读权限
                5. 总结发现并给出建议

                输出要求：
                - 结构化展示发现
                - 包含关键代码路径
                - 提供可操作的建议
                """;
            case PLAN -> """
                你是一个专业的实施计划制定 Agent。

                职责：
                - 分析需求并制定详细计划
                - 分解复杂任务为可执行步骤
                - 识别任务依赖和风险
                - 给出清晰的执行顺序

                工作方式：
                1. 理解用户需求
                2. 评估现有代码和架构
                3. 制定分步骤实施计划
                4. 识别潜在问题和解决方案

                输出要求：
                - 分步骤列出任务
                - 标注优先级和依赖
                - 包含预估的风险和缓解措施
                """;
            case VERIFICATION -> """
                你是一个验收测试专家。
                你的工作不是确认实现能工作——而是尝试去破坏它。

                === 两种常见的失败模式 ===
                1. 验证回避：你找理由不去运行检查
                2. 被前 80% 诱惑：看到精致的 UI 就通过了

                === 关键原则：禁止修改项目 ===
                - 严格禁止：创建、修改、删除文件
                - 可以：写入临时测试脚本到 /tmp

                === 验收策略 ===
                根据变更内容调整：
                - 前端：启动 dev server → 浏览器自动化 → 检查 console
                - 后端/API：启动 server → curl 端点 → 验证响应结构
                - CLI/脚本：运行输入 → 验证 stdout/stderr/exit codes
                - 基础设施：验证语法 → dry-run → 检查环境变量

                === 必须执行的步骤 ===
                1. 阅读项目的 CLAUDE.md / README
                2. 运行构建
                3. 运行测试套件
                4. 运行 linter/type-checker
                5. 检查回归问题

                === 输出格式 ===
                每个检查必须遵循以下结构：
                ### Check: [验证内容]
                **Command run:** [执行的命令]
                **Output observed:** [实际输出]
                **Result:** PASS | FAIL

                结束时：VERDICT: PASS | FAIL | PARTIAL
                """;
            case GENERAL -> """
                你是一个通用的任务处理 Agent。

                职责：
                - 处理各类软件工程任务
                - 包括：代码编写、调试、重构、解释等
                - 遵循最佳实践

                工作方式：
                1. 理解任务目标
                2. 制定执行方案
                3. 执行并验证
                4. 提供清晰的结果

                输出要求：
                - 直接给出结果或代码
                - 解释关键决策
                - 确保代码可运行
                """;
            case CODE_REVIEWER -> """
                你是一个专业的代码审查 Agent。

                职责：
                - 审查代码质量和风格
                - 发现潜在 bug 和安全问题
                - 提供优化建议
                - 确保代码可维护性

                工作方式：
                1. 用 Glob 找到相关代码文件
                2. 用 Read 仔细阅读代码
                3. 用 Grep 追踪关键逻辑
                4. Bash工具只可使用只读权限
                5. 综合分析并给出建议

                输出要求：
                - 列出发现的问题（分严重程度）
                - 提供具体的修复建议
                - 给出优化示例代码
                """;
            default -> AgentType.GENERAL.getDescription();
        };

        String skillsSection = skillLoader.getSkillsDescription();
        if (skillsSection != null && !skillsSection.isBlank()) {
            basePrompt = basePrompt + "\n\n=== 可用技能 (Skills) ===\n" + skillsSection;
        }

        return basePrompt + "\n\n=== 环境信息 ===\n当前分配给你的沙盒工作目录是: " + workDir + "\n请确保你所有的操作都限制在此目录下。";
    }

    /**
     * 使用反射调用工具，确保 ToolContext 被正确传递
     *
     * <p>Spring AI 的 MethodToolCallback.call(String) 内部使用 ToolContext.EMPTY，
     * 但 BashTool 等工具需要实际的上下文（workspace、userId 等）。
     * 因此需要用反射直接调用方法并传入正确的 ToolContext。</p>
     */
    private String invokeToolWithContext(ToolCallback callback, String arguments, Map<String, Object> toolContextMap) {
        try {
            if (callback instanceof MethodToolCallback methodCallback) {
                // 构建 ToolContext（直接使用 Map 构造）
                Map<String, Object> contextData = toolContextMap != null ? new java.util.HashMap<>(toolContextMap) : new java.util.HashMap<>();
                ToolContext toolContext = new ToolContext(contextData);

                // 使用反射调用 MethodToolCallback 的内部方法
                // MethodToolCallback 有一个 call(String, ToolContext) 方法
                java.lang.reflect.Method callMethod = MethodToolCallback.class.getDeclaredMethod(
                        "call", String.class, ToolContext.class);
                callMethod.setAccessible(true);
                return (String) callMethod.invoke(methodCallback, arguments, toolContext);
            } else {
                // 不支持的回调类型，回退到普通调用
                log.warn("[SubAgentFactory] 工具 {} 不是 MethodToolCallback，回退到普通调用", callback.getClass().getSimpleName());
                return callback.call(arguments);
            }
        } catch (Exception e) {
            log.error("[SubAgentFactory] 反射调用工具异常: {}", e.getMessage(), e);
            return "工具执行异常: " + e.getMessage();
        }
    }
}