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

    @Override
    public String getName() {
        return "AskUserQuestion";
    }

    private static final long DEFAULT_TIMEOUT_SECONDS = 300; // 5 minutes

    private final AskUserQuestionService service;

    public AskUserQuestionTool(AskUserQuestionService service) {
        this.service = service;
    }

    /**
     * 向用户提问并等待回答
     *
     * @return 用户的回答，超时或跳过有特殊标记
     */
    @Tool(name = "AskUserQuestion", description = """
            Use this tool when you need to ask the user questions during execution. This allows you to:

            1. Gather user preferences or requirements.
            2. Clarify ambiguous instructions.
            3. Get decisions on implementation choices as you work.
            4. Offer choices to the user about what direction to take.

            Usage Notes:
            - Custom Input: Users will always be able to select "Other" to provide custom text input.
            - Multiple Selections: Use multiSelect: true to allow multiple answers to be selected for a question.
            - Recommendations: If you recommend a specific option, make that the first option in the list and add "(Recommended)" at the end of the label.

            Plan Mode Note:
            In plan mode, use this tool to clarify requirements or choose between approaches BEFORE finalizing your plan.
            - DO NOT use this tool to ask "Is my plan ready?" or "Should I proceed?" — use ExitPlanMode for plan approval.
            - IMPORTANT: Do not reference "the plan" in your questions (e.g., "Do you have feedback about the plan?", "Does the plan look good?") because the user cannot see the plan in the UI until you call ExitPlanMode. If you need plan approval, use ExitPlanMode instead.

            Preview Feature:
            Use the optional preview field on options when presenting concrete artifacts that users need to visually compare:
            - ASCII mockups of UI layouts or components
            - Code snippets showing different implementations
            - Diagram variations
            - Configuration examples

            Formatting & UI:
            - Preview content is rendered as markdown in a monospace box. Multi-line text with newlines is supported.
            - When any option has a preview, the UI switches to a side-by-side layout with a vertical option list on the left and preview on the right.
            - Constraint: Do not use previews for simple preference questions where labels and descriptions suffice.
            - Constraint: Previews are only supported for single-select questions (not multiSelect).

            Response:
            You will receive a response containing:
            - answers: Map of question text to selected option label
            - annotations: Optional notes and preview content selected by user
            - timeout: true if user did not respond in time
            - skipReason: reason if user chose to skip
            """)
    public CompletableFuture<AskUserResponse> askUserQuestion(
            @ToolParam(description = "Question request in JSON format containing 1~4 questions")
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
