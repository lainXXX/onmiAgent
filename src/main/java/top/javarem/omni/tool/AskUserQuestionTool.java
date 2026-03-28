package top.javarem.omni.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.AskUserQuestionRequest;
import top.javarem.omni.model.AskUserResponse;
import top.javarem.omni.service.AskUserQuestionService;

import java.util.concurrent.CompletableFuture;

/**
 * AskUserQuestion 工具
 * 使 Agent 能够在执行任务期间中断并向用户提问
 */
@Component
@Slf4j
public class AskUserQuestionTool implements AgentTool {

    private static final long DEFAULT_TIMEOUT_SECONDS = 300; // 5 minutes

    private final AskUserQuestionService service;

    public AskUserQuestionTool(AskUserQuestionService service) {
        this.service = service;
    }

    /**
     * 向用户提问并等待回答
     *
     * @param request 问题请求，包含 1~4 个问题
     * @return 用户的回答，超时或跳过有特殊标记
     */
    @Tool(name = "AskUserQuestion", description = """
            Ask the user a question and wait for their response.

            Use this tool when you need to:
            - Collect user preferences or requirements
            - Clarify ambiguous instructions
            - Confirm a technical approach before proceeding
            - Present multiple options for the user to choose from

            The user will see your question(s) with options and can:
            - Select one or more options (depending on multiSelect setting)
            - Choose "Other" to provide custom text input
            - Skip the question (which will terminate the agent)

            You will receive a response containing:
            - answers: Map of question text to selected option label
            - annotations: Optional notes and preview content selected by user
            - timeout: true if user did not respond in time
            - skipReason: reason if user chose to skip

            Important constraints:
            - Ask only 1-4 questions at a time
            - For single-select questions, you can include preview content (code snippets, ASCII art)
            - multiSelect=true questions do not support preview
            - In Plan mode, this tool can only clarify requirements, not ask for plan approval
            """)
    public CompletableFuture<AskUserResponse> askUserQuestion(
            @ToolParam(description = "问题请求，包含 1~4 个问题")
            AskUserQuestionRequest request) {

        log.info("[AskUserQuestion] Tool called with {} question(s)", request.questions().size());

        // 验证问题数量
        if (request.questions().size() < 1 || request.questions().size() > 4) {
            log.error("[AskUserQuestion] Invalid question count: {}", request.questions().size());
            return CompletableFuture.completedFuture(
                    AskUserResponse.skipped("Invalid question count: must be 1-4"));
        }

        // 调用服务获取 Future（会被 Spring AI 阻塞等待）
        return service.askQuestion(request, DEFAULT_TIMEOUT_SECONDS);
    }
}
