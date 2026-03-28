package top.javarem.omni.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.javarem.omni.model.*;
import top.javarem.omni.model.Question;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * AskUserQuestion 核心服务
 * 管理 CompletableFuture 映射和 SSE 推送
 */
@Service
@Slf4j
public class AskUserQuestionService {

    private static final long SSE_TIMEOUT = 10L * 60 * 1000; // 10 minutes
    private static final long CLEANUP_INTERVAL = 5L * 60 * 1000; // 5 minutes

    private final ConcurrentHashMap<String, CompletableFuture<AskUserResponse>> pendingFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    public AskUserQuestionService() {
        // 定时清理过期的 Future
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredFutures,
                CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * 向用户提问，返回 Future
     *
     * @param request   问题请求
     * @param timeoutSeconds 超时时间（秒）
     * @return CompletableFuture，等待用户回答
     */
    public CompletableFuture<AskUserResponse> askQuestion(
            AskUserQuestionRequest request,
            long timeoutSeconds) {

        String questionId = UUID.randomUUID().toString();

        CompletableFuture<AskUserResponse> future = new CompletableFuture<>();

        // 注册 Future
        pendingFutures.put(questionId, future);

        // 推送 SSE 到前端
        pushSseEvent(questionId, request);

        // 设置超时
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException) {
                        log.info("[AskUserQuestion] Question {} timed out", questionId);
                        return AskUserResponse.timeoutResponse();
                    }
                    log.error("[AskUserQuestion] Question {} error", questionId, ex);
                    throw new RuntimeException(ex);
                });

        // 清理注册
        future.whenComplete((result, ex) -> {
            pendingFutures.remove(questionId);
            sseEmitters.remove(questionId);
        });

        log.info("[AskUserQuestion] Created question {}, {} questions, timeout={}s",
                questionId, request.questions().size(), timeoutSeconds);

        return future;
    }

    /**
     * 提交用户答案
     *
     * @param questionId 问题ID
     * @param answers    答案
     * @param annotations 批注
     */
    public void submitAnswer(
            String questionId,
            Map<String, String> answers,
            Map<String, UserAnnotation> annotations) {

        CompletableFuture<AskUserResponse> future = pendingFutures.get(questionId);
        if (future == null) {
            log.warn("[AskUserQuestion] Question {} not found or already answered", questionId);
            return;
        }

        AskUserResponse response = AskUserResponse.of(answers, annotations);
        future.complete(response);

        log.info("[AskUserQuestion] Question {} answered: {}", questionId, answers);
    }

    /**
     * 用户跳过
     *
     * @param questionId 问题ID
     * @param reason     跳过原因
     */
    public void skipQuestion(String questionId, String reason) {
        CompletableFuture<AskUserResponse> future = pendingFutures.get(questionId);
        if (future == null) {
            log.warn("[AskUserQuestion] Question {} not found or already answered", questionId);
            return;
        }

        future.complete(AskUserResponse.skipped(reason));

        log.info("[AskUserQuestion] Question {} skipped: {}", questionId, reason);
    }

    /**
     * 获取问题的 SSE emitter（前端订阅用）
     */
    public SseEmitter createEmitter(String questionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sseEmitters.put(questionId, emitter);

        emitter.onCompletion(() -> {
            log.debug("[AskUserQuestion] SSE completed for question {}", questionId);
            sseEmitters.remove(questionId);
        });

        emitter.onTimeout(() -> {
            log.debug("[AskUserQuestion] SSE timed out for question {}", questionId);
            sseEmitters.remove(questionId);
        });

        emitter.onError(e -> {
            log.debug("[AskUserQuestion] SSE error for question {}: {}", questionId, e.getMessage());
            sseEmitters.remove(questionId);
        });

        return emitter;
    }

    /**
     * 通过 SSE 推送问题事件到前端
     */
    private void pushSseEvent(String questionId, AskUserQuestionRequest request) {
        SseEmitter emitter = sseEmitters.get(questionId);
        if (emitter == null) {
            log.debug("[AskUserQuestion] No SSE emitter for question {}, will be created on poll", questionId);
            return;
        }

        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .name("ask-user-question")
                    .data(Map.of(
                            "type", "ASK_USER_QUESTION",
                            "questionId", questionId,
                            "questions", request.questions(),
                            "metadata", request.metadata() != null ? request.metadata() : Map.of()
                    ));

            emitter.send(event);
            log.debug("[AskUserQuestion] SSE event sent for question {}", questionId);
        } catch (IOException e) {
            log.error("[AskUserQuestion] Failed to send SSE event for question {}", questionId, e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 清理过期的 Future（防止内存泄漏）
     */
    private void cleanupExpiredFutures() {
        pendingFutures.entrySet().removeIf(entry -> {
            CompletableFuture<AskUserResponse> future = entry.getValue();
            if (future.isDone() || future.isCancelled()) {
                log.debug("[AskUserQuestion] Cleanup expired future: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 检查是否有待回答的问题
     */
    public boolean hasPendingQuestions() {
        return !pendingFutures.isEmpty();
    }

    /**
     * 获取待回答问题数量
     */
    public int getPendingCount() {
        return pendingFutures.size();
    }
}
