package top.javarem.omni.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.javarem.omni.model.AskUserResponse;
import top.javarem.omni.model.UserAnnotation;
import top.javarem.omni.service.AskUserQuestionService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AskUserQuestion 回调接口
 * 供前端提交答案和订阅问题事件
 */
@RestController
@RequestMapping("/api/questions")
@Slf4j
public class AskUserQuestionController {

    private final AskUserQuestionService service;

    public AskUserQuestionController(AskUserQuestionService service) {
        this.service = service;
    }

    /**
     * 提交用户答案
     * POST /api/questions/{questionId}/answer
     */
    @PostMapping("/{questionId}/answer")
    public void submitAnswer(
            @PathVariable String questionId,
            @RequestBody AnswerRequest request) {

        log.info("[AskUserController] Answer received for question {}: {}", questionId, request.answers());

        if (request.skip() != null && request.skip()) {
            service.skipQuestion(questionId, request.skipReason());
        } else {
            Map<String, UserAnnotation> annotations = request.annotations() != null
                    ? request.annotations()
                    : Map.of();
            service.submitAnswer(questionId, request.answers(), annotations);
        }
    }

    /**
     * 轮询获取问题（长轮询）
     * GET /api/questions/poll
     *
     * 如果有待回答问题，立即返回
     * 否则阻塞等待直到有问题或超时（30秒）
     */
    @GetMapping(value = "/poll", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionPollResponse poll() {
        try {
            var pendingQuestion = service.pollForQuestion();
            if (pendingQuestion != null) {
                return new QuestionPollResponse(
                        true,
                        pendingQuestion.questionId(),
                        pendingQuestion.request().questions(),
                        pendingQuestion.request().metadata()
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AskUserController] 轮询被中断");
        }
        return new QuestionPollResponse(false, null, List.of(), null);
    }

    /**
     * 获取所有待回答的问题（立即返回）
     * GET /api/questions/pending
     */
    @GetMapping(value = "/pending", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionPollResponse getPending() {
        var allPending = service.getAllPendingQuestions();
        if (!allPending.isEmpty()) {
            var first = allPending.get(0);
            return new QuestionPollResponse(
                    true,
                    first.questionId(),
                    first.request().questions(),
                    first.request().metadata()
            );
        }
        return new QuestionPollResponse(false, null, List.of(), null);
    }

    /**
     * SSE 订阅（需要 questionId）
     * GET /api/questions/subscribe?questionId={questionId}
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam String questionId) {
        log.info("[AskUserController] SSE subscription for question {}", questionId);
        return service.createEmitter(questionId);
    }

    /**
     * 获取当前是否有待回答的问题
     * GET /api/questions/status
     */
    @GetMapping("/status")
    public QuestionStatus getStatus() {
        return new QuestionStatus(service.hasPendingQuestions(), service.getPendingCount());
    }

    // 请求/响应 DTO
    public record AnswerRequest(
            Map<String, String> answers,
            Map<String, UserAnnotation> annotations,
            Boolean skip,
            String skipReason
    ) {}

    public record QuestionStatus(boolean hasPending, int pendingCount) {}

    public record QuestionPollResponse(
            boolean hasQuestion,
            String questionId,
            List<?> questions,
            Map<String, Object> metadata
    ) {}
}
