# AskUserQuestion 工具设计

## 1. 概述

**目标**：实现 `AskUserQuestion` 工具，使 Agent 能够在执行任务期间中断并向用户提问，用于收集偏好、澄清模糊需求、确认技术方案或提供多个选项让用户抉择。

**核心机制**：采用 **Future/Promise 模式** + **SSE 实时推送**，实现逻辑阻塞而不浪费线程。

## 2. 核心架构

```
Agent 调用 AskUserQuestionTool
      ↓
AskUserQuestionService.askQuestion()
  - 生成 questionId (UUID)
  - 创建 CompletableFuture<AskUserResponse>
  - 通过 SSE 推送到前端
      ↓
工具方法返回 Future（Spring AI 自动阻塞等待）
      ↓
前端弹出交互界面，用户回答
      ↓
前端 POST /api/questions/{questionId}/answer
      ↓
Future.complete(answer) → Agent 恢复执行
```

## 3. 组件设计

| 组件 | 路径 | 职责 |
|------|------|------|
| `Option` | `model/Option.java` | 选项结构，含 label、description、preview |
| `Question` | `model/Question.java` | 问题结构，含 header、question、options、multiSelect |
| `AskUserQuestionRequest` | `model/AskUserQuestionRequest.java` | 工具入参包装 |
| `UserAnnotation` | `model/UserAnnotation.java` | 用户批注结构，含 preview、notes |
| `AskUserResponse` | `model/AskUserResponse.java` | 响应结构（联合类型） |
| `AskUserQuestionService` | `service/AskUserQuestionService.java` | Future 管理、SSE 推送 |
| `AskUserQuestionTool` | `tool/AskUserQuestionTool.java` | @Tool 暴露接口 |
| `AskUserController` | `controller/AskUserQuestionController.java` | REST 回调接口 |

## 4. 数据结构

### 4.1 Option（已有，需确认 preview）

```java
public record Option(
    String label,
    String description,
    String preview  // 仅单选可用，渲染代码/UI 预览
) {
    public Option(String label, String description) {
        this(label, description, null);
    }
}
```

### 4.2 Question

```java
public record Question(
    String header,              // 短标签，最多 12 字符
    String question,            // 完整问题，以问号结尾
    List<Option> options,       // 2~4 个选项
    boolean multiSelect         // true=多选，false=单选
) {}
```

### 4.3 AskUserQuestionRequest（工具入参包装）

```java
public record AskUserQuestionRequest(
    List<Question> questions,   // 1~4 个问题
    Map<String, Object> metadata // 如 source: "remember"
) {}
```

### 4.4 UserAnnotation（用户批注）

```java
public record UserAnnotation(
    String preview,  // 选中的 preview 内容
    String notes     // 用户手写备注
) {}
```

### 4.5 AskUserResponse（联合类型响应）

```java
public record AskUserResponse(
    Map<String, String> answers,                  // { "问题1": "选项A" }
    Map<String, UserAnnotation> annotations,    // { "问题1": { "notes": "附加说明" } }
    boolean timeout,                            // 超时标记
    String skipReason                           // 跳过原因（如果有）
) {
    // 工厂方法
    public static AskUserResponse timeout() {
        return new AskUserResponse(Map.of(), Map.of(), true, null);
    }

    public static AskUserResponse skipped(String reason) {
        return new AskUserResponse(Map.of(), Map.of(), false, reason);
    }
}
```

## 5. API 设计

| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/questions/{questionId}/answer` | POST | 用户提交答案 |
| `/api/questions/pending` | GET | SSE 流，推送问题到前端 |

### 5.1 提交答案接口

**Request Body**:
```json
{
  "answers": { "问题1": "选项A" },
  "annotations": { "问题1": { "preview": "...", "notes": "..." } }
}
```

**Response**: `200 OK` 或 `400 Bad Request`

### 5.2 SSE 流

推送格式：
```json
{
  "type": "ASK_USER_QUESTION",
  "questionId": "uuid-xxx",
  "questions": [...]
}
```

## 6. 超时与边界处理

### 6.1 超时（默认 5 分钟）

```java
future.orTimeout(5, TimeUnit.MINUTES)
      .exceptionally(ex -> {
          if (ex instanceof TimeoutException) {
              return AskUserResponse.timeout();
          }
          throw new RuntimeException(ex);
      });
```

### 6.2 跳过/拒绝

抛出 `UserSkippedException`，由全局异常处理器捕获并终止 Agent。

### 6.3 Other 选项

前端 UI 自动提供"Other"选项让用户手写输入，返回值直接作为答案文本。

## 7. 并发安全

- 使用 `ConcurrentHashMap<String, CompletableFuture<AskUserResponse>>` 存储 Future
- 定时任务清理超时不响应的 Future（防止内存泄漏）
- 每个 questionId 独立管理，避免串话

## 8. 前端协作流程

1. 前端建立 SSE 连接 `/api/questions/pending`
2. Agent 调用 `AskUserQuestionTool`
3. SSE 推送问题事件，前端弹出交互界面
4. 用户选择/输入后，POST 到 `/api/questions/{questionId}/answer`
5. Future 被完成，Agent 收到响应继续执行

## 9. 使用约束

- `multiSelect: true` 时不支持 `preview` 字段
- 推荐选项应放在第一位并标注 `(Recommended)`
- Plan 模式下只能用于澄清需求，不能用于询问计划审批
