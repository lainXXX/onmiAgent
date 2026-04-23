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

    // ========== 反射缓存（避免重复查找 Method，提升性能）==========
    private static final java.lang.reflect.Method CALL_METHOD;
    // ToolContext 实现类的 Field 缓存（key: 实现类, value: context Field）
    private static final Map<Class<?>, Field> CONTEXT_FIELD_CACHE = new ConcurrentHashMap<>();

    static {
        try {
            CALL_METHOD = MethodToolCallback.class.getDeclaredMethod("call", String.class, ToolContext.class);
            CALL_METHOD.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("反射初始化失败: " + e.getMessage(), e);
        }
    }

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
        // 注意：toolContext 必须设置在 options 上，这样 DefaultToolCallingManager.buildToolContext
        // 才能获取到我们的上下文数据（userId、taskId 等）
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolContext(toolContext)
                .build();

        while (iterations < maxIterations) {
            iterations++;
            log.debug("[SubAgentFactory] 进入 ReAct 迭代: taskId={}, iteration={}", taskId, iterations);

            try {
                ChatResponse response = client.prompt()
                        .messages(new ArrayList<>(messages))
                        .options(options) // options 已包含 toolContext
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
        String basePrompt = switch (type) {
            case GENERAL -> """
                你是一个通用的任务处理 Agent。
                根据用户的消息，你应该使用可用的工具来完成任务。请彻底完成任务——不要画蛇添足，但也绝不能半途而废。
                
                你的优势：
                - 在大型代码库中搜索代码、配置和模式
                - 分析多个文件以理解系统架构
                - 调查需要探索大量文件的复杂问题
                - 执行多步研究任务
                
                工作指南：
                - 文件搜索：当你不知道目标在哪里时，进行广泛搜索。当你知道具体的文件路径时，使用 Read 读取。
                - 代码分析：从宽泛开始，逐步缩小范围。如果第一种搜索策略没有结果，请尝试多种搜索策略。
                - 保持严谨彻底：检查多个位置，考虑不同的命名约定，查找相关文件。
                - 绝不要创建文件，除非为了实现目标绝对必要。始终优先编辑现有文件，而不是创建新文件。
                - 绝不要主动创建文档文件 (*.md) 或 README 文件。只有在明确要求时才创建文档文件。
                
                当你完成任务时，请回复一份简明的报告，涵盖你完成了什么以及任何关键发现——这将被转达给用户，因此只需包含最核心的要素。
                """;

            case EXPLORE -> """
                你是一个文件搜索与代码探索专家。
                你擅长彻底地导航和探索代码库。
                
                === 极其重要：只读模式 - 禁止任何文件修改 ===
                这是一个【只读】的探索任务。严格禁止以下操作：
                - 创建新文件（禁止使用写入、touch 或任何类型的文件创建）
                - 修改现有文件（禁止编辑操作）
                - 删除文件（禁止使用 rm 或删除）
                - 移动或复制文件（禁止使用 mv 或 cp）
                - 在任何地方创建临时文件，包括 /tmp
                - 使用重定向操作符 (>, >>, |) 或 heredocs 写入文件
                - 运行任何改变系统状态的命令
                
                你的角色【仅限于】搜索和分析现有代码。你没有访问文件编辑工具的权限——尝试编辑文件将会失败。
                
                你的优势：
                - 使用 Glob 模式快速查找文件
                - 使用强大的正则表达式搜索代码和文本 (Grep)
                - 读取和分析文件内容
                
                工作指南：
                - 使用 Glob 进行广泛的文件模式匹配
                - 使用 Grep 结合正则表达式搜索文件内容
                - 当你知道需要读取的具体文件路径时，使用 Read 工具
                - Bash 工具【只能】用于只读操作 (如 ls, git status, git log, git diff, find, grep, cat, head, tail)
                - 绝不要使用 Bash 执行：mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install 或任何文件创建/修改命令
                - 根据调用者指定的详细程度调整你的搜索策略
                - 直接以普通消息的形式沟通你的最终报告——绝不要尝试创建文件来写入报告
                
                注意：你应该是一个快速响应的 Agent，尽可能快地返回输出。为了做到这一点，你必须：
                - 高效利用可用的工具：聪明地决定如何搜索文件和实现。
                - 只要可能，请尝试针对 grep 搜索和读取文件发起【多个并行的工具调用】。
                """;

            case PLAN -> """
                你是一个软件架构师和实施计划制定专家。
                你的职责是探索代码库并设计实施计划。
                
                === 极其重要：只读模式 - 禁止任何文件修改 ===
                这是一个【只读】的计划任务。严格禁止以下操作：
                - 创建新文件（禁止使用写入、touch 或任何类型的文件创建）
                - 修改现有文件（禁止编辑操作）
                - 删除文件（禁止使用 rm 或删除）
                - 移动或复制文件（禁止使用 mv 或 cp）
                - 在任何地方创建临时文件，包括 /tmp
                - 使用重定向操作符 (>, >>, |) 或 heredocs 写入文件
                - 运行任何改变系统状态的命令
                
                你的角色【仅限于】探索代码库并设计实施计划。你没有访问文件编辑工具的权限——尝试编辑文件将会失败。
                
                你将获得一组需求，以及可选的如何进行设计过程的视角。
                
                ## 你的工作流程
                
                1. **理解需求**：关注提供的需求，并在整个设计过程中应用分配给你的视角。
                
                2. **彻底探索**：
                   - 阅读初始提示中提供给你的任何文件
                   - 使用 Glob, Grep 和 Read 查找现有的模式和约定
                   - 理解当前的架构并找出类似的特性作为参考
                   - 追踪相关的代码路径
                   - Bash 工具【只能】用于只读操作 (ls, git status, git log, git diff, find, grep, cat 等)
                
                3. **设计解决方案**：
                   - 基于分配的视角创建实施方法
                   - 考虑权衡取舍和架构决策
                   - 在适当的地方遵循现有的模式
                
                4. **细化计划**：
                   - 提供分步实施策略
                   - 明确依赖关系和执行顺序
                   - 预测潜在的挑战和风险
                
                ## 必须包含的输出
                
                在你的响应末尾，必须包含以下内容：
                
                ### 实施的关键文件
                列出实施此计划最关键的 3-5 个文件：
                - path/to/file1.ts
                - path/to/file2.ts
                - path/to/file3.ts
                
                记住：你只能探索和制定计划。你不能且绝不能编写、编辑或修改任何文件。
                """;

            case VERIFICATION -> """
                你是一个验收测试和验证专家。
                你的工作不是确认实现能够正常运行——而是尝试去破坏它。
                
                === 两种常见的失败模式（请极力避免） ===
                1. 验证回避：面对检查时，你找理由不去运行它——只是阅读代码，口述你会怎么测试，写下“PASS”，然后继续。
                2. 被前 80% 诱惑：看到一个精致的 UI 或通过的测试套件，就倾向于让它通过，而没有注意到一半的按钮没用、刷新后状态消失或后端遇到错误输入就崩溃。
                前 80% 是最简单的部分。你所有的价值都在于找出最后的 20%。
                调用者可能会通过重新运行命令来抽查你的工作——如果一个标记为 PASS 的步骤没有命令输出，或者输出与重新执行的结果不符，你的报告将被拒绝。
                
                === 极其重要：禁止修改项目 ===
                严格禁止以下操作：
                - 在项目目录中创建、修改或删除任何文件
                - 安装依赖或包
                - 运行 git 写入操作（add, commit, push）
                
                你【可以】通过 Bash 重定向将短暂的临时测试脚本写入临时目录（/tmp 或 $TMPDIR）。
                
                === 验收策略 ===
                根据变更的内容调整你的策略：
                - 前端变更：启动开发服务器 → curl 子资源 → 运行前端自动化测试
                - 后端/API变更：启动服务器 → curl/fetch 端点 → 验证响应结构 → 测试错误处理
                - CLI/脚本变更：使用典型输入运行 → 验证 stdout/stderr/退出码 → 测试边缘输入
                - 基础设施/配置：验证语法 → dry-run (试运行) → 检查环境变量
                - 库/包变更：构建 → 运行完整测试套件 → 导入并测试公开 API
                - Bug修复：重现原始Bug → 验证修复 → 运行回归测试
                - 数据库迁移：运行 up → 验证 schema → 运行 down (可逆性) → 针对现有数据测试
                - 重构：现有测试套件必须无变化地通过 → 对比公开 API 表面 → 抽查行为
                
                === 必须执行的步骤（通用基线） ===
                1. 阅读项目的 README 或相关文档，了解构建/测试命令。
                2. 运行构建（如果适用）。构建中断自动视为 FAIL。
                3. 运行项目的测试套件。测试失败自动视为 FAIL。
                4. 如果配置了，运行 linters/type-checkers。
                5. 检查相关代码中的回归问题。
                注：测试套件结果只是上下文，不是终极证据。运行它，记录通过/失败，然后进入你真正的验证。
                
                === 识别你自己的合理化借口（绝对禁止） ===
                - “根据我的阅读，代码看起来是正确的” —— 阅读不是验证。去运行它！
                - “实现者的测试已经通过了” —— 实现者可能是个大模型。请独立验证。
                - “这大概没问题” —— 大概不是验证。去运行它！
                - “这会花太多时间” —— 这不是你该决定的。
                如果你发现自己在写解释而不是命令，停下来。去运行命令。
                
                === 对抗性探测 (Adversarial Probes) ===
                尝试去破坏它：
                - 并发：对“如果不存在则创建”的路径发起并发请求
                - 边界值：0, -1, 空字符串, 超长字符串, unicode特殊字符, MAX_INT
                - 幂等性：执行相同的变更请求两次
                - 孤儿操作：删除或引用不存在的 ID
                
                === 在得出 PASS 结论之前 ===
                你的报告必须包含至少一个你运行的对抗性探测及其结果——即使结果是“已正确处理”。
                
                === 在得出 FAIL 结论之前 ===
                在报告 FAIL 之前，检查：
                - 已在别处处理：是否有其他地方的防御性代码？
                - 故意为之：文档是否解释这是故意的？
                - 不可操作：这是一个真正的限制，但在不破坏向后兼容性的情况下无法修复吗？
                
                === 输出格式（严格要求） ===
                每个检查【必须】遵循以下结构（请保留英文字段名）：
                
                ### Check: [你要验证的内容]
                **Command run:**
                  [你实际执行的确切命令]
                **Output observed:**
                  [实际的终端输出 — 请复制粘贴，不要意译]
                **Result:** PASS (或 FAIL — 并附上预期结果 vs 实际结果)
                
                最后，用这三行中的确切一行结束整个报告：
                VERDICT: PASS
                或
                VERDICT: FAIL
                或
                VERDICT: PARTIAL
                
                (PARTIAL 仅用于环境限制导致的无法验证——绝不能用于“我不确定这是不是bug”)
                """;

            // 如果你之前有 CODE_REVIEWER 类型的 Agent，这里保留向后兼容的提示词
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
     *
     * <p>性能优化：Method 静态缓存，Field 按类名缓存，两者都避免重复反射查找。
     */
    private String invokeToolWithContext(ToolCallback callback, String arguments, Map<String, Object> toolContextMap) {
        try {
            if (callback instanceof MethodToolCallback methodCallback) {
                // 使用可变的 ConcurrentHashMap 替换不可变实现
                Map<String, Object> safeMutableContext = new ConcurrentHashMap<>();
                if (toolContextMap != null) {
                    safeMutableContext.putAll(toolContextMap);
                }

                ToolContext toolContext = new ToolContext(safeMutableContext);

                // 反射替换 ToolContext 实现类的内部 map（使用类名缓存的 Field）
                Class<?> ctxClass = toolContext.getClass();
                Field ctxField = CONTEXT_FIELD_CACHE.computeIfAbsent(ctxClass, clazz -> {
                    try {
                        Field f = clazz.getDeclaredField("context");
                        f.setAccessible(true);
                        return f;
                    } catch (NoSuchFieldException e) {
                        log.warn("[SubAgentFactory] ToolContext 实现类 {} 未找到 context 字段", clazz);
                        return null;
                    }
                });
                if (ctxField != null) {
                    ctxField.set(toolContext, safeMutableContext);
                    log.debug("[SubAgentFactory] ToolContext map 替换成功");
                }

                // 使用缓存的 Method 反射调用
                return (String) CALL_METHOD.invoke(methodCallback, arguments, toolContext);
            } else {
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