package top.javarem.onmi.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.javarem.onmi.tool.ToolsManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent ChatClient 工厂
 * 根据 Agent 类型创建专用的 ChatClient 实例
 */
@Slf4j
@Component
public class SubAgentChatClientFactory {

    private final ChatModel chatModel;
    private final ToolsManager toolsManager;
    private final AgentSessionManager sessionManager;
    private final WorktreeManager worktreeManager;

    @Value("${agent.max-iterations:50}")
    private int maxIterations;

    @Value("${agent.working-directory:${user.dir}}")
    private String workingDirectory;

    public SubAgentChatClientFactory(
            @Qualifier("miniMaxChatModel") ChatModel chatModel,
            ToolsManager toolsManager,
            AgentSessionManager sessionManager,
            WorktreeManager worktreeManager) {
        this.chatModel = chatModel;
        this.toolsManager = toolsManager;
        this.sessionManager = sessionManager;
        this.worktreeManager = worktreeManager;
    }

    /**
     * 执行子 Agent 任务
     */
    public AgentResult execute(String taskId, AgentType type, String prompt, String conversationId, String userId) {
        return execute(taskId, type, prompt, conversationId, userId, null, null);
    }

    /**
     * 执行子 Agent 任务（带 resume 和 worktree 支持）
     */
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
                messages = sessionManager.buildResumePrompt(resumeFromTaskId, prompt);
                log.info("[SubAgentFactory] Resume会话: from={}, messages={}", resumeFromTaskId, messages.size());
            } else {
                sessionManager.createSession(taskId, type.getValue(), prompt);
                messages = new ArrayList<>(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(prompt)
                ));
            }

            String finalOutput = executeLoop(taskId, type, messages, userId);

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
     * 执行对话循环
     */
    private String executeLoop(String taskId, AgentType type, List<Message> messages, String userId) {
        int iterations = 0;
        String lastOutput = null;

        ChatClient client = ChatClient.builder(chatModel)
                .build();

        while (iterations < maxIterations) {
            iterations++;
            log.debug("[SubAgentFactory] 对话迭代: taskId={}, iteration={}", taskId, iterations);

            try {
                List<Message> currentMessages = new ArrayList<>(messages);
                Map<String, Object> toolContext = Map.of(
                        "userId", userId,
                        "taskId", taskId,
                        "agentType", type.getValue()
                );

                String content = client.prompt()
                        .messages(currentMessages)
                        .toolContext(toolContext)
                        .call()
                        .content();

                if (content == null || content.isBlank()) {
                    lastOutput = extractLastAssistantOutput(messages);
                    break;
                }

                AssistantMessage assistantMessage = new AssistantMessage(content);
                messages.add(assistantMessage);
                sessionManager.addAssistantMessage(taskId, content);

                if (isTerminalOutput(content)) {
                    lastOutput = content;
                    break;
                }

                lastOutput = content;

            } catch (Exception e) {
                log.error("[SubAgentFactory] 迭代异常: taskId={}, iteration={}, error={}",
                        taskId, iterations, e.getMessage());
                lastOutput = "执行过程中出错: " + e.getMessage();
                break;
            }
        }

        if (iterations >= maxIterations) {
            log.warn("[SubAgentFactory] 达到最大迭代次数: taskId={}, max={}", taskId, maxIterations);
            lastOutput = (lastOutput != null ? lastOutput + "\n\n" : "") +
                    "[警告: 达到最大迭代次数 " + maxIterations + "，任务被强制终止]";
        }

        sessionManager.updateLastOutput(taskId, lastOutput);
        return lastOutput;
    }

    private String extractLastAssistantOutput(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof AssistantMessage)
                .reduce((first, second) -> second)
                .map(m -> ((AssistantMessage) m).getText())
                .orElse("Agent completed without output");
    }

    private boolean isTerminalOutput(String text) {
        if (text == null || text.isBlank()) return true;
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
                4. 总结发现并给出建议

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
                4. 综合分析并给出建议

                输出要求：
                - 列出发现的问题（分严重程度）
                - 提供具体的修复建议
                - 给出优化示例代码
                """;
            case CLAUDE_CODE_GUIDE -> """
                你是一个专业的文档助手 Agent。

                职责：
                - 回答关于 Claude Code CLI 工具的问题
                - 解答关于 Claude Agent SDK 的问题
                - 说明 Claude API 的使用方法

                工作方式：
                1. 用 WebSearch 搜索最新文档
                2. 用 WebFetch 获取详细文档
                3. 用 Read 查看本地配置文件

                注意事项：
                - 只负责回答关于工具本身的问题
                - 不编写实际代码
                - 提供准确的技术文档链接
                """;
        };
        return basePrompt + "\n\n工作目录: " + workDir;
    }
}
