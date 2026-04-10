# AskUserQuestion Streaming 阻塞修复设计

## 1. 背景

当前 streaming 模式（SSE）中，`AskUserQuestionTool` 被调用时：

1. `ToolCallingManager` 执行工具 → 调用 `.get()` **阻塞等待** CompletableFuture
2. **整个 Flux pipeline 被卡住**，没有数据发送到 SSE
3. 前端在超时前收不到任何数据，可能误以为连接断开

## 2. 解决方案：混合方案

```
第一次 /stream:
  LLM 请求 AskUserQuestion → Advisor 检测到 → 抛异常中断 Flux
  → Aggregator 捕获 → 发射 WAITING 事件到 SSE → Stream 结束
  前端收到 WAITING → 显示问答 UI

用户回答 → POST /api/questions/{id}/answer
  → CompletableFuture.complete() → Agent 继续（后台线程）

第二次 /stream（新的请求）:
  → ChatMemory 包含上次的问答历史
  → Agent 正常继续生成
```

### 核心设计原则

- **最小侵入**：不修改 `AskUserQuestionService`、`AskUserQuestionTool`、`AskUserQuestionController`
- **可恢复**：第二次 /stream 通过 ChatMemory 加载历史，自动继续
- **事件驱动**：WAITING 事件让前端知道该显示问答 UI

## 3. 组件变更

### 3.1 新增 AskUserQuestionException

```java
public class AskUserQuestionException extends RuntimeException {
    private final String questionId;
    private final AskUserQuestionRequest request;

    public AskUserQuestionException(String questionId, AskUserQuestionRequest request) {
        super("AskUserQuestion: " + questionId);
        this.questionId = questionId;
        this.request = request;
    }

    public String questionId() { return questionId; }
    public AskUserQuestionRequest request() { return request; }
}
```

### 3.2 修改 LifecycleToolCallAdvisor

在 `doGetNextInstructionsForToolCall` 中，工具执行返回后检测是否为 AskUserQuestion 工具：

```java
@Override
protected List<Message> doGetNextInstructionsForToolCall(ChatClientRequest request,
                                                         ChatClientResponse response,
                                                         ToolExecutionResult result) {
    List<Message> messages = super.doGetNextInstructionsForToolCall(request, response, result);

    // 检测 AskUserQuestion 工具结果
    if (isAskUserQuestionToolResult(result)) {
        String questionId = extractQuestionId(result);
        AskUserQuestionRequest request = extractRequest(result);
        // 抛异常中断 Flux，触发 aggregator 发射 WAITING 事件
        throw new AskUserQuestionException(questionId, request);
    }

    saveToolExecutionHistory(request, result);
    return messages;
}
```

### 3.3 修改 ChatController.streamChat()

1. 在 `RequestContextHolder` 中设置当前 SseEmitter
2. Aggregator 的 `.onErrorResume` 捕获 `AskUserQuestionException` 并发射 WAITING 事件

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> streamChat(@RequestBody ChatRequest request) {
    // 设置 SSE emitter 到 RequestContextHolder
    SseEmitter emitter = new SseEmitter();
    RequestContextHolder.setEmitter(emitter);

    String[] allToolNames = toolsManager.getAllToolNames().toArray(new String[0]);

    Flux<ChatClientResponse> flux = openAiChatClient.prompt()
            .user(request.getQuestion())
            .toolNames(allToolNames)
            ...stream()...;

    return flux
            .onErrorResume(ex -> {
                SseEmitter emitter = RequestContextHolder.getEmitter();
                if (ex instanceof AskUserQuestionException aqj && emitter != null) {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("ask-user-question")
                                .data(Map.of(
                                        "type", "WAITING",
                                        "questionId", aqj.questionId(),
                                        "questions", aqj.request().questions(),
                                        "metadata", aqj.request().metadata() != null
                                                ? aqj.request().metadata() : Map.of()
                                )));
                    } catch (IOException ignored) {}
                    emitter.complete();
                }
                return Flux.empty();
            })
            .doFinally(signal -> RequestContextHolder.clear())
            .map(response -> {
                // ... 现有 map 逻辑
            });
}
```

### 3.4 新增 RequestContextHolder 工具类

用于在 SSE 阻塞期间传递 SseEmitter：

```java
public class RequestContextHolder {
    private static final ThreadLocal<SseEmitter> emitterHolder = new ThreadLocal<>();

    public static void setEmitter(SseEmitter emitter) { emitterHolder.set(emitter); }
    public static SseEmitter getEmitter() { return emitterHolder.get(); }
    public static void clear() { emitterHolder.remove(); }
}
```

## 4. 数据流时序

```
前端                         后端                            LLM
 │                            │                              │
 │──── POST /stream ────────>│                              │
 │                            │──── prompt() ───────────────>│
 │                            │<─── text delta (streaming) ───│
 │<─── text chunk ──────────│                              │
 │                            │<─── AskUserQuestion tool call│
 │                            │  (doGetNextInstructions 抛异常)
 │                            │                              │
 │<── WAITING 事件 (SseEmitter)                             │
 │   {type: "WAITING",        │                              │
 │    questionId: "xxx",       │                              │
 │    questions: [...]}         │                              │
 │                            │                              │
 │  [Stream 结束]              │                              │
 │                            │                              │
 │  [前端显示问答 UI]           │                              │
 │                            │                              │
 │──── POST /answer ─────────>│                              │
 │                            │  future.complete(answer)    │
 │                            │  (Agent 在后台线程继续)        │
 │                            │                              │
 │──── POST /stream (新请求) ─│                              │
 │  (带相同 sessionId)         │  → ChatMemory 包含历史       │
 │                            │──── prompt() ───────────────>│
 │                            │<─── 继续生成... ─────────────│
 │<─── text chunk ──────────│                              │
```

## 5. 无变更的组件

| 组件 | 说明 |
|------|------|
| `AskUserQuestionService` | CompletableFuture 管理机制不变 |
| `AskUserQuestionTool` | 返回 CompletableFuture 不变 |
| `AskUserQuestionController` | `/api/questions/{id}/answer` 已实现 complete |

## 6. 实现步骤

1. 创建 `AskUserQuestionException.java`
2. 创建 `RequestContextHolder.java`
3. 修改 `LifecycleToolCallAdvisor.doGetNextInstructionsForToolCall()`，检测 AskUserQuestion 抛异常
4. 修改 `ChatController.streamChat()`，添加 RequestContextHolder 设置和异常捕获
5. 测试验证：streaming 调用 AskUserQuestion 时 SSE 正确发送 WAITING 事件后结束

## 7. 风险与边界

- **SSE 超时**：SseEmitter 默认 10 分钟超时，AskUserQuestion 超时 5 分钟，安全
- **多问题并发**：同时多个 AskUserQuestion 场景暂不支持（单 session 单问题）
- **历史消息累积**：长期对话历史通过 ContextCompressionAdvisor 压缩，不膨胀
