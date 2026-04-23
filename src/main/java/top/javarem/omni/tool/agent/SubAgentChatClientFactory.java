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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

            // 使用 ConcurrentHashMap 确保外部传递的 Map 是可变且线程安全的
            Map<String, Object> toolContext = new ConcurrentHashMap<>();
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

        // 使用 defaultToolCallbacks 接收编程式工具实例
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
                        .options(options) // 强制禁用自动执行，暴露出 ToolCalls
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
                            // 【修复点】：捕获异常并转为结果反馈给模型，避免应用崩溃
                            log.error("[SubAgentFactory] 工具执行异常: {}", toolName, e);
                            toolResult = "工具执行异常: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()) + "。请检查参数后重试。";
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
                // 3. 处理常规文本输出 (Reasoning 阶段 / 任务完成)
                // =================================================================================
                String contentText = assistantMessage.getText();
                if (contentText != null && !contentText.isBlank()) {
                    sessionManager.addAssistantMessage(taskId, contentText);
                    lastOutputText = contentText;
                }

                // 【修复点】：既然没有任何 Tool Calls，说明大模型认为当前任务已经推理和回复完毕，绝对必须跳出循环，防止无限复读
                log.info("[SubAgentFactory] 任务完成 (未产生工具调用): taskId={}, iteration={}", taskId, iterations);
                break;

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

        ChatClient client = ChatClient.builder(chatModel)
                .defaultToolCallbacks(filteredCallbacks.toArray(new ToolCallback[0]))
                .build();

        try {
            // OneShot 模式我们允许框架自动执行内部工具
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

    private String buildSystemPrompt(AgentType type, String workDir) {
        // (维持你原有的 prompt，这里省略多余展开)
        String basePrompt = switch (type) {
            case EXPLORE -> """
                你是一个专业的代码探索 Agent。
                // ... 省略，与你的原始代码保持一致 ...
                输出要求：
                - 结构化展示发现
                - 包含关键代码路径
                - 提供可操作的建议
                """;
            // ... (与原版完全一致的 prompt 配置) ...
            default -> AgentType.GENERAL.getDescription();
        };

        // 仅在 AgentType 允许 Skill 工具时才添加技能描述
        if (type.isToolAllowed("Skill")) {
            String skillsSection = skillLoader.getSkillsDescription();
            if (skillsSection != null && !skillsSection.isBlank()) {
                basePrompt = basePrompt + "\n\n=== 可用技能 (Skills) ===\n" + skillsSection;
            }
        }

        return basePrompt + "\n\n=== 环境信息 ===\n当前分配给你的沙盒工作目录是: " + workDir + "\n请确保你所有的操作都限制在此目录下。";
    }

    /**
     * 使用反射调用工具，确保 ToolContext 被正确传递
     * 【修复】：注入可变的 Map 防止 Spring AI 默认的 UnmodifiableMap 导致 UnsupportedOperationException
     */
    private String invokeToolWithContext(ToolCallback callback, String arguments, Map<String, Object> toolContextMap) {
        try {
            if (callback instanceof MethodToolCallback methodCallback) {
                // SpringAI 在高版本中使用 Map.copyOf 会生成不可变集合，
                // 为了兼容你的 ReadStateHolder 内部使用 put 方法，我们强制注入可变的 ConcurrentHashMap。
                Map<String, Object> safeMutableContext = new ConcurrentHashMap<>();
                if (toolContextMap != null) {
                    safeMutableContext.putAll(toolContextMap);
                }

                ToolContext toolContext = new ToolContext(safeMutableContext);

                // 【核心修复】：反射强行替换 ToolContext 内部为不可变的 map (如果有的话)
                try {
                    Field contextField = ToolContext.class.getDeclaredField("context");
                    contextField.setAccessible(true);
                    contextField.set(toolContext, safeMutableContext);
                } catch (Exception ignored) {
                    // 若版本差异找不到该字段则忽略，继续走流程
                }

                // 使用反射调用 MethodToolCallback 的内部方法
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
            log.error("[SubAgentFactory] 工具调用异常: {}", e.getMessage(), e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "工具执行遇到系统异常: " + cause.getMessage() + "。如果是因为缺少参数请重试。";
        }
    }
}