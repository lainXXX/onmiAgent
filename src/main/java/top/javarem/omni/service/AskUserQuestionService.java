package top.javarem.omni.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.javarem.omni.model.*;

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
    private static final long POLL_TIMEOUT_SECONDS = 30; // 轮询超时

    private final ConcurrentHashMap<String, CompletableFuture<AskUserResponse>> pendingFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingQuestion> pendingQuestions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    /**
     * 待处理问题的数据结构
     */
    public record PendingQuestion(
            String questionId,
            AskUserQuestionRequest request,
            long createdAt
    ) {}

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

        // 注册 Future 和问题数据
        pendingFutures.put(questionId, future);
        PendingQuestion pendingQuestion = new PendingQuestion(questionId, request, System.currentTimeMillis());
        pendingQuestions.put(questionId, pendingQuestion);

        // 推送 SSE 到前端（如果已订阅）
        pushSseEvent(questionId, request);

        // 设置超时
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException) {
                        log.info("[AskUserQuestion] 问题 {} 已超时", questionId);
                        return AskUserResponse.timeoutResponse();
                    }
                    log.error("[AskUserQuestion] 问题 {} 执行异常", questionId, ex);
                    throw new RuntimeException(ex);
                });

        // 清理注册
        future.whenComplete((result, ex) -> {
            pendingFutures.remove(questionId);
            pendingQuestions.remove(questionId);
            sseEmitters.remove(questionId);
        });

        log.info("[AskUserQuestion] 创建问题 {}，共 {} 个问题，超时时间={}秒",
                questionId, request.questions().size(), timeoutSeconds);

        return future;
    }

    /**
     * 提交用户答案
     */
    public void submitAnswer(
            String questionId,
            Map<String, String> answers,
            Map<String, UserAnnotation> annotations) {

        CompletableFuture<AskUserResponse> future = pendingFutures.get(questionId);
        if (future == null) {
            log.warn("[AskUserQuestion] 问题 {} 不存在或已回答", questionId);
            return;
        }

        AskUserResponse response = AskUserResponse.of(answers, annotations);
        future.complete(response);

        log.info("[AskUserQuestion] 问题 {} 已回答: {}", questionId, answers);
    }

    /**
     * 用户跳过
     */
    public void skipQuestion(String questionId, String reason) {
        CompletableFuture<AskUserResponse> future = pendingFutures.get(questionId);
        if (future == null) {
            log.warn("[AskUserQuestion] 问题 {} 不存在或已回答", questionId);
            return;
        }

        future.complete(AskUserResponse.skipped(reason));

        log.info("[AskUserQuestion] 问题 {} 已跳过: {}", questionId, reason);
    }

    /**
     * 获取问题的 SSE emitter（前端订阅用）
     */
    public SseEmitter createEmitter(String questionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sseEmitters.put(questionId, emitter);

        emitter.onCompletion(() -> {
            log.debug("[AskUserQuestion] SSE 连接完成，问题 {}", questionId);
            sseEmitters.remove(questionId);
        });

        emitter.onTimeout(() -> {
            log.debug("[AskUserQuestion] SSE 超时，问题 {}", questionId);
            sseEmitters.remove(questionId);
        });

        emitter.onError(e -> {
            log.debug("[AskUserQuestion] SSE 错误，问题 {}: {}", questionId, e.getMessage());
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
            log.debug("[AskUserQuestion] 无 SSE emitter，问题 {} 已存入轮询队列", questionId);
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
            log.debug("[AskUserQuestion] SSE 事件已发送，问题 {}", questionId);
        } catch (IOException e) {
            log.error("[AskUserQuestion] SSE 事件发送失败，问题 {}", questionId, e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 获取待回答的问题（轮询用）
     * 如果有待回答问题，立即返回
     * 否则等待直到有问题或超时
     */
    public PendingQuestion pollForQuestion() throws InterruptedException {
        // 如果有待回答问题，立即返回
        if (!pendingQuestions.isEmpty()) {
            return pendingQuestions.values().iterator().next();
        }

        // 等待问题出现（最多 POLL_TIMEOUT_SECONDS）
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < POLL_TIMEOUT_SECONDS * 1000) {
            if (!pendingQuestions.isEmpty()) {
                return pendingQuestions.values().iterator().next();
            }
            Thread.sleep(500); // 每 500ms 检查一次
        }

        return null; // 超时返回 null
    }

    /**
     * 根据 questionId 获取问题详情
     */
    public PendingQuestion getQuestion(String questionId) {
        return pendingQuestions.get(questionId);
    }

    /**
     * 获取所有待回答的问题
     */
    public java.util.List<PendingQuestion> getAllPendingQuestions() {
        return new java.util.ArrayList<>(pendingQuestions.values());
    }

    /**
     * 清理过期的 Future（防止内存泄漏）
     */
    private void cleanupExpiredFutures() {
        pendingFutures.entrySet().removeIf(entry -> {
            CompletableFuture<AskUserResponse> future = entry.getValue();
            if (future.isDone() || future.isCancelled()) {
                log.debug("[AskUserQuestion] 清理过期 Future: {}", entry.getKey());
                return true;
            }
            return false;
        });

        // 清理过旧的问题（超过 10 分钟）
        long cutoff = System.currentTimeMillis() - (10 * 60 * 1000);
        pendingQuestions.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);
    }

    public boolean hasPendingQuestions() {
        return !pendingFutures.isEmpty();
    }

    public int getPendingCount() {
        return pendingFutures.size();
    }
}
