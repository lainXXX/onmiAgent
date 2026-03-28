package top.javarem.omni.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.javarem.omni.model.AskUserResponse;
import top.javarem.omni.model.UserAnnotation;
import top.javarem.omni.service.AskUserQuestionService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AskUserQuestion 回调接口
 * 供前端提交答案和订阅 SSE 事件
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
     *
     * POST /api/questions/{questionId}/answer
     * Body: { "answers": {...}, "annotations": {...} }
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
     * SSE 流：前端订阅以接收问题事件
     *
     * GET /api/questions/pending?questionId={questionId}
     *
     * 前端先创建 emitter，后续问题会通过此 emitter 推送
     */
    @GetMapping(value = "/pending", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam String questionId) {
        log.info("[AskUserController] SSE subscription for question {}", questionId);
        return service.createEmitter(questionId);
    }

    /**
     * 查询当前是否有待回答的问题
     */
    @GetMapping("/status")
    public QuestionStatus getStatus() {
        return new QuestionStatus(service.hasPendingQuestions(), service.getPendingCount());
    }

    /**
     * 答案提交请求体
     */
    public record AnswerRequest(
            Map<String, String> answers,
            Map<String, UserAnnotation> annotations,
            Boolean skip,
            String skipReason
    ) {}

    /**
     * 问题状态响应
     */
    public record QuestionStatus(boolean hasPending, int pendingCount) {}
}
