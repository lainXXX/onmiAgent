# 上下文压缩四层架构 Specification

## 1. Overview

本规范定义了 AI Agent 上下文压缩的标准化四层架构，参考 Claude Code 实现。

### 1.1 架构目标

- **完整性**: 覆盖所有压缩场景
- **可扩展性**: 每层独立，可插拔
- **可观测性**: 完整日志和指标
- **容错性**: 电路断路器保护

---

## 2. 核心架构

### 2.1 包结构

```
omni/
├── advisor/
│   ├── LifecycleToolCallAdvisor.java    # 入口：继承 ToolCallAdvisor
│   │
│   └── hook/
│       ├── HookRegistry.java            # Hook 注册与组合中心
│       ├── PreToolUseHook.java         # 接口：工具执行前
│       ├── PostToolUseHook.java        # 接口：工具执行后
│       ├── CompressionHook.java        # 接口：压缩 Hook
│       ├── StopHook.java               # 接口：工具链结束
│       └── SessionLifecycleHook.java   # 接口：会话生命周期
│
├── service/
│   └── compression/
│       ├── CompressionPipeline.java     # 四层流水线编排
│       ├── layer/
│       │   ├── CompressionLayer.java    # 抽象层接口
│       │   ├── SnipCompactLayer.java    # Layer 1
│       │   ├── MicroCompactLayer.java   # Layer 2
│       │   ├── ContextCollapseLayer.java # Layer 3
│       │   └── AutoCompactLayer.java   # Layer 4
│       └── context/
│           └── CompactionContext.java   # 压缩执行上下文
│
└── model/
    └── compression/                     # DTO/VO
        ├── LayerResult.java
        ├── PipelineResult.java
        ├── CollapseState.java
        └── CompressionConfig.java
```

**原则：**
- `advisor/` 放 Advisor 组件
- `service/` 放业务执行逻辑
- `model/` 放数据对象

---

## 3. ToolCallAdvisor Hook 体系

### 3.1 Spring AI ToolCallAdvisor 的 Hook 点

`LifecycleToolCallAdvisor` 继承 `org.springframework.ai.chat.client.advisor.ToolCallAdvisor`，覆盖以下 Hook 方法：

```
┌─────────────────────────────────────────────────────────────────┐
│              ToolCallAdvisor.adviseCall() 循环                   │
│              (do-while isToolCall)                               │
└─────────────────────────────────────────────────────────────────┘

    ┌─────────────────────────────────────────────────────────────┐
    │  doInitializeLoop()  ← SessionStart Hook 挂载点           │
    │  • 循环初始化                                              │
    │  • 保存用户消息                                            │
    └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
    ┌─────────────────────────────────────────────────────────────┐
    │  doBeforeCall()  ← PreToolUse Hook 挂载点                │
    │  • 工具调用前执行                                          │
    │  • 可取消、可修改                                          │
    └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
    ┌─────────────────────────────────────────────────────────────┐
    │  callAdvisorChain.nextCall()                                │
    │  • 调用 LLM                                               │
    │  • 返回 AssistantMessage                                   │
    └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
    ┌─────────────────────────────────────────────────────────────┐
    │  doAfterCall()  ← PostToolUse Hook 挂载点                 │
    │  • 工具调用后执行                                          │
    │  • 可记录、可修改响应                                       │
    └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
    ┌─────────────────────────────────────────────────────────────┐
    │  toolCallingManager.executeToolCalls()                      │
    │  • 执行工具                                                │
    │  • 返回 ToolExecutionResult                               │
    └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
    ┌─────────────────────────────────────────────────────────────┐
    │  doGetNextInstructionsForToolCall()  ← Compression Hook 挂载点│
    │  • 获取下一轮指令                                          │
    │  • 工具结果已准备好，是压缩的最佳时机                        │
    └─────────────────────────────────────────────────────────────┘
                              │
    } while (isToolCall)       │
                              │
                              ▼
    ┌─────────────────────────────────────────────────────────────┐
    │  doFinalizeLoop()  ← StopHook / SessionEnd 挂载点          │
    │  • 循环结束                                                │
    └─────────────────────────────────────────────────────────────┘
```

### 3.2 Hook 清单

| Hook | 方法 | 时机 | 可取消 |
|------|------|------|--------|
| `SessionStart` | `doInitializeLoop` | 循环开始前 | ❌ |
| `PreToolUse` | `doBeforeCall` | 每次工具调用前 | ✅ |
| `PostToolUse` | `doAfterCall` | 每次工具调用后 | ❌ |
| `Compression` | `doGetNextInstructionsForToolCall` | 工具执行后，下轮前 | ✅ |
| `StopHook` | `doFinalizeLoop` | 循环结束后 | ❌ |
| `SessionEnd` | `doFinalizeLoop` | 会话结束时 | ❌ |

---

## 4. Hook 接口定义

### 4.1 PreToolUseHook

```java
/**
 * 工具执行前 Hook
 * 可取消工具调用、可修改输入
 */
public interface PreToolUseHook {
    /**
     * @param request 当前请求
     * @return 修改后的请求（返回 null 表示取消）
     */
    ChatClientRequest before(ChatClientRequest request);
}
```

### 4.2 PostToolUseHook

```java
/**
 * 工具执行后 Hook
 * 可记录日志、可修改响应
 */
public interface PostToolUseHook {
    /**
     * @param response 当前响应
     * @return 修改后的响应
     */
    ChatClientResponse after(ChatClientResponse response);
}
```

### 4.3 CompressionHook

```java
/**
 * 压缩 Hook
 * 在 doGetNextInstructionsForToolCall 中执行
 */
public interface CompressionHook {
    /**
     * @param request 请求上下文
     * @param response LLM 响应
     * @param result 工具执行结果
     * @return 压缩后的消息列表
     */
    List<Message> execute(ChatClientRequest request,
                         ChatClientResponse response,
                         ToolExecutionResult result);
}
```

### 4.4 StopHook

```java
/**
 * 工具链结束 Hook
 */
public interface StopHook {
    void afterLoop(ChatClientResponse response);
}
```

### 4.5 SessionLifecycleHook

```java
/**
 * 会话生命周期 Hook
 */
public interface SessionLifecycleHook {
    void onSessionStart(ChatClientRequest request);
    void onSessionEnd(ChatClientResponse response);
}
```

---

## 5. HookRegistry

### 5.1 设计

`HookRegistry` 是 Hook 的**注册与组合中心**，负责：
- 收集所有注册的 Hook
- 按顺序执行链式调用
- 处理取消逻辑

### 5.2 实现

```java
@Component
public class HookRegistry {

    private final List<PreToolUseHook> preHooks;
    private final List<PostToolUseHook> postHooks;
    private final CompressionHook compressionHook;
    private final StopHook stopHook;
    private final SessionLifecycleHook sessionHook;

    // ========== Session Lifecycle ==========

    public void onSessionStart(ChatClientRequest request) {
        sessionHook.onSessionStart(request);
    }

    public void onSessionEnd(ChatClientResponse response) {
        sessionHook.onSessionEnd(response);
    }

    // ========== PreToolUse Chain ==========

    public ChatClientRequest doPreToolUse(ChatClientRequest request) {
        for (PreToolUseHook hook : preHooks) {
            ChatClientRequest result = hook.before(request);
            if (result == null) {
                // 取消
                return null;
            }
            request = result;
        }
        return request;
    }

    // ========== PostToolUse Chain ==========

    public ChatClientResponse doPostToolUse(ChatClientResponse response) {
        for (PostToolUseHook hook : postHooks) {
            response = hook.after(response);
        }
        return response;
    }

    // ========== Compression ==========

    public List<Message> doCompression(ChatClientRequest request,
                                       ChatClientResponse response,
                                       ToolExecutionResult result) {
        return compressionHook.execute(request, response, result);
    }

    // ========== StopHook ==========

    public void doStopHook(ChatClientResponse response) {
        stopHook.afterLoop(response);
    }
}
```

---

## 6. LifecycleToolCallAdvisor（精简版）

### 6.1 核心逻辑

```java
@Component
@Slf4j
public class LifecycleToolCallAdvisor extends ToolCallAdvisor {

    private final HookRegistry hookRegistry;
    private final MemoryRepository memoryRepository;

    private static final int ORDER = Integer.MAX_VALUE - 1000;

    protected LifecycleToolCallAdvisor(
            ToolCallingManager toolCallingManager,
            HookRegistry hookRegistry,
            MemoryRepository memoryRepository,
            ChatHistoryRepository chatHistoryRepository,
            ThreadPoolExecutor threadPoolExecutor) {
        super(toolCallingManager, ORDER, true);
        this.hookRegistry = hookRegistry;
        this.memoryRepository = memoryRepository;
        this.chatHistoryRepository = chatHistoryRepository;
        this.threadPoolExecutor = threadPoolExecutor;
    }

    // ==================== Session Lifecycle ====================

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        hookRegistry.onSessionStart(request);
        return super.doInitializeLoop(request, chain);
    }

    // ==================== PreToolUse ====================

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest modified = hookRegistry.doPreToolUse(request);
        if (modified == null) {
            // Hook 取消，直接返回（应抛出异常或特殊处理）
            return request;
        }
        return super.doBeforeCall(modified, chain);
    }

    // ==================== PostToolUse ====================

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        ChatClientResponse modified = hookRegistry.doPostToolUse(response);
        return super.doAfterCall(modified, chain);
    }

    // ==================== Compression (关键点) ====================

    @Override
    protected List<Message> doGetNextInstructionsForToolCall(
            ChatClientRequest request,
            ChatClientResponse response,
            ToolExecutionResult result) {

        // 1. 执行压缩 Hook
        List<Message> messages = hookRegistry.doCompression(request, response, result);

        // 2. 保存工具历史
        saveToolHistory(request, result);

        // 3. 返回给父类继续循环
        return messages;
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(
            ChatClientRequest request,
            ChatClientResponse response,
            ToolExecutionResult result) {

        List<Message> messages = hookRegistry.doCompression(request, response, result);
        saveToolHistory(request, result);
        return messages;
    }

    // ==================== StopHook ====================

    @Override
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse response, CallAdvisorChain chain) {
        hookRegistry.doStopHook(response);
        return super.doFinalizeLoop(response, chain);
    }

    // ==================== 保存历史 ====================

    private void saveToolHistory(ChatClientRequest request, ToolExecutionResult result) {
        // ... 保存逻辑
    }
}
```

### 6.2 职责划分

| 组件 | 职责 |
|------|------|
| `LifecycleToolCallAdvisor` | 只负责循环组合，调用 HookRegistry |
| `HookRegistry` | 管理所有 Hook，按序执行 |
| `CompressionHook` | 实现压缩逻辑 |
| 其他 Hook 实现 | 各自分离，互不影响 |

---

## 7. 四层压缩流水线

### 7.1 执行时机总览

| 层级 | 名称 | 执行时机 | 触发条件 |
|------|------|----------|----------|
| Layer 1 | SnipCompact | **每轮必然** | 无条件 |
| Layer 2 | MicroCompact | **每轮必然** | 检查时间衰减/缓存编辑 |
| Layer 3 | Context Collapse | 按需 | 使用量 > 90% 或 > 95% |
| Layer 4 | AutoCompact | 按需 | tokens > contextWindow - 13,000 |

### 7.2 各层详细规范

#### Layer 1: SnipCompact

**职责**: 快速本地压缩，无 API 调用

**规则**:
1. 去除连续重复的 User/Assistant 消息
2. 删除空内容消息
3. 截断超长工具输出（非 JSON）

```java
@Component
@Layer(order = 1, name = "SnipCompact")
public class SnipCompactLayer implements CompressionLayer {

    @Override
    public LayerResult compress(CompactionContext ctx, List<Message> messages) {
        // 去重、截断、删空
    }
}
```

#### Layer 2: MicroCompact

**职责**: 消息分组/时间衰减清理

```java
@Component
@Layer(order = 2, name = "MicroCompact")
public class MicroCompactLayer implements CompressionLayer {

    @Override
    public boolean shouldSkip(CompactionContext ctx, List<Message> messages) {
        // 检查时间衰减：不到60分钟不触发
        return !timeDecayTriggered(ctx);
    }
}
```

#### Layer 3: Context Collapse

**职责**: 上下文折叠，提供可视化折叠视图

**状态机**:
```
NORMAL → (90%) → COMMIT → (95%) → BLOCK
```

```java
@Component
@Layer(order = 3, name = "ContextCollapse")
public class ContextCollapseLayer implements CompressionLayer {

    @Override
    public LayerResult compress(CompactionContext ctx, List<Message> messages) {
        double usage = ctx.getTokenUsage();
        if (usage >= 0.95) {
            ctx.setCollapseState(CollapseState.BLOCKING);
        } else if (usage >= 0.90) {
            ctx.setCollapseState(CollapseState.COMMIT);
        }
    }
}
```

#### Layer 4: AutoCompact

**职责**: LLM 驱动的语义压缩

```java
@Component
@Layer(order = 4, name = "AutoCompact")
public class AutoCompactLayer implements CompressionLayer {

    @Override
    public boolean shouldSkip(CompactionContext ctx, List<Message> messages) {
        // 阈值检查：contextWindow - 13,000
        return ctx.getTokenCount() <= ctx.getContextWindow() - 13_000;
    }
}
```

---

## 8. 压缩配置

### 8.1 ContextCompressionProperties

```java
@Data
@ConfigurationProperties(prefix = "omni.compression")
public class ContextCompressionProperties {

    private boolean enabled = true;

    // Layer 1
    private SnipConfig snip = new SnipConfig();

    // Layer 2
    private MicroConfig micro = new MicroConfig();

    // Layer 3
    private CollapseConfig collapse = new CollapseConfig();

    // Layer 4
    private AutoConfig auto = new AutoConfig();

    // 全局
    private int contextWindow = 200_000;
    private int keepEarliest = 10;
    private int keepRecent = 10;
}

@Data
public static class SnipConfig {
    private boolean enabled = true;
    private int maxToolOutputLength = 100;
}

@Data
public static class MicroConfig {
    private boolean enabled = true;
    private TimeDecayConfig timeDecay = new TimeDecayConfig();
    private CacheEditConfig cacheEdit = new CacheEditConfig();
}

@Data
public static class TimeDecayConfig {
    private boolean enabled = false;
    private int gapThresholdMinutes = 60;
    private int keepRecent = 5;
}

@Data
public static class CacheEditConfig {
    private boolean enabled = true;
    private int triggerThreshold = 5;
    private int deletionBatchSize = 10;
}

@Data
public static class CollapseConfig {
    private boolean enabled = true;
    private double commitThreshold = 0.90;
    private double blockThreshold = 0.95;
}

@Data
public static class AutoConfig {
    private boolean enabled = true;
    private int bufferTokens = 13_000;
    private int warningTokens = 20_000;
    private int maxSummaryTokens = 4_000;
    private int maxRetries = 3;
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
}
```

---

## 9. 电路断路器

```java
@Component
public class CircuitBreaker {

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean open = false;
    private final int maxFailures;

    public void onSuccess() {
        consecutiveFailures.set(0);
    }

    public void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= maxFailures) {
            open = true;
            log.warn("电路断路器触发！连续失败 {} 次", maxFailures);
        }
    }

    public boolean isOpen() {
        return open;
    }
}
```

---

## 10. 完整流程图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    LifecycleToolCallAdvisor 完整流程                      │
└──────────────────────────────────────────────────────────────────────────┘

    用户消息
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  doInitializeLoop()                                                 │
│  hookRegistry.onSessionStart() → SessionLifecycleHook              │
└─────────────────────────────────────────────────────────────────────────┘
         │
         ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │                     do-while (isToolCall)                        │
    │  ┌───────────────────────────────────────────────────────────┐  │
    │  │  doBeforeCall()                                          │  │
    │  │  hookRegistry.doPreToolUse() → PreToolUseHook           │  │
    │  └───────────────────────────────────────────────────────────┘  │
    │                            │                                    │
    │                            ▼                                    │
    │  ┌───────────────────────────────────────────────────────────┐  │
    │  │  callAdvisorChain.nextCall()                                │  │
    │  │  → LLM API → AssistantMessage                             │  │
    │  └───────────────────────────────────────────────────────────┘  │
    │                            │                                    │
    │                            ▼                                    │
    │  ┌───────────────────────────────────────────────────────────┐  │
    │  │  doAfterCall()                                           │  │
    │  │  hookRegistry.doPostToolUse() → PostToolUseHook          │  │
    │  └───────────────────────────────────────────────────────────┘  │
    │                            │                                    │
    │                            ▼                                    │
    │  ┌───────────────────────────────────────────────────────────┐  │
    │  │  toolCallingManager.executeToolCalls()                      │  │
    │  │  → 执行工具 → ToolExecutionResult                         │  │
    │  └───────────────────────────────────────────────────────────┘  │
    │                            │                                    │
    │                            ▼                                    │
    │  ┌───────────────────────────────────────────────────────────┐  │
    │  │  doGetNextInstructionsForToolCall()                        │  │
    │  │                                                       │  │
    │  │  hookRegistry.doCompression()                            │  │
    │  │    ↓                                                    │  │
    │  │  ┌────────────────────────────────────────────────────┐  │  │
    │  │  │  Layer 1: SnipCompact  ──▶ Layer 2: MicroCompact │  │  │
    │  │  │  ──▶ Layer 3: ContextCollapse ──▶ Layer 4: Auto  │  │  │
    │  │  └────────────────────────────────────────────────────┘  │  │
    │  │                                                       │  │
    │  │  saveToolHistory()  ← 保存工具执行历史                  │  │
    │  └───────────────────────────────────────────────────────────┘  │
    │  } while (isToolCall)                                         │
    └─────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  doFinalizeLoop()                                                     │
│  hookRegistry.doStopHook() → StopHook                               │
└─────────────────────────────────────────────────────────────────────────┘
         │
         ▼
      返回响应
```

---

## 11. 文件清单

| 操作 | 文件路径 |
|------|---------|
| **新增** | `advisor/LifecycleToolCallAdvisor.java` (重构) |
| **新增** | `advisor/hook/HookRegistry.java` |
| **新增** | `advisor/hook/PreToolUseHook.java` |
| **新增** | `advisor/hook/PostToolUseHook.java` |
| **新增** | `advisor/hook/CompressionHook.java` |
| **新增** | `advisor/hook/StopHook.java` |
| **新增** | `advisor/hook/SessionLifecycleHook.java` |
| **新增** | `advisor/hook/impl/LoggingPreToolUseHook.java` |
| **新增** | `advisor/hook/impl/LoggingPostToolUseHook.java` |
| **新增** | `service/compression/CompressionPipeline.java` |
| **新增** | `service/compression/layer/CompressionLayer.java` |
| **新增** | `service/compression/layer/SnipCompactLayer.java` |
| **新增** | `service/compression/layer/MicroCompactLayer.java` |
| **新增** | `service/compression/layer/ContextCollapseLayer.java` |
| **新增** | `service/compression/layer/AutoCompactLayer.java` |
| **新增** | `service/compression/context/CompactionContext.java` |
| **新增** | `service/compression/CircuitBreaker.java` |
| **新增** | `model/compression/LayerResult.java` |
| **新增** | `model/compression/PipelineResult.java` |
| **新增** | `model/compression/CollapseState.java` |
| **新增** | `model/compression/CompressionConfig.java` |
| **废弃** | `advisor/ContextCompressionAdvisor.java` |
| **废弃** | `model/compression/SnipCompactor.java` |
| **废弃** | `model/compression/MicroCompactor.java` |

---

## 12. YAML 配置示例

```yaml
omni:
  compression:
    enabled: true
    context-window: 200000
    keep-earliest: 10
    keep-recent: 10

    snip:
      enabled: true
      max-tool-output-length: 100

    micro:
      enabled: true
      time-decay:
        enabled: false
        gap-threshold-minutes: 60
        keep-recent: 5
      cache-edit:
        enabled: true
        trigger-threshold: 5
        deletion-batch-size: 10

    collapse:
      enabled: true
      commit-threshold: 0.90
      block-threshold: 0.95

    auto:
      enabled: true
      buffer-tokens: 13000
      warning-tokens: 20000
      max-summary-tokens: 4000
      max-retries: 3
      circuit-breaker:
        enabled: true
        max-consecutive-failures: 3
```
