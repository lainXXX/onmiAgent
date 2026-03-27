package top.javarem.skillDemo.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 子 Agent ChatClient 工厂
 * 根据 Agent 类型创建专用的 ChatClient 实例
 */
@Slf4j
@Component
public class SubAgentChatClientFactory {

    private final ChatModel chatModel;

    public SubAgentChatClientFactory(@Qualifier("miniMaxChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行子 Agent 任务
     */
    public AgentResult execute(String taskId, AgentType type, String task, String conversationId, String userId) {
        long startTime = System.currentTimeMillis();
        log.info("[SubAgentFactory] 启动子Agent: taskId={}, type={}, userId={}", taskId, type.getValue(), userId);

        try {
            // 构建子 Agent 的系统提示
            String systemPrompt = buildSystemPrompt(type);

            // 创建子 Agent 专用的 ChatClient
            // 注意：不需要手动设置 resolver，Spring AI 会自动使用 ToolsManager 注册的工具
            ChatClient client = ChatClient.builder(chatModel)
                    .build();

            // 执行任务
            String result = client.prompt()
                    .messages(
                            new SystemMessage(systemPrompt),
                            new UserMessage(task)
                    )
                    .call()
                    .content();

            long duration = System.currentTimeMillis() - startTime;
            log.info("[SubAgentFactory] 子Agent完成: taskId={}, duration={}ms", taskId, duration);

            return AgentResult.completed(taskId, type.getValue(), result, duration);

        } catch (Exception e) {
            log.error("[SubAgentFactory] 子Agent执行失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return AgentResult.failed(taskId, type.getValue(), e.getMessage());
        }
    }

    /**
     * 根据 Agent 类型构建系统提示
     */
    private String buildSystemPrompt(AgentType type) {
        return switch (type) {
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
        };
    }
}
