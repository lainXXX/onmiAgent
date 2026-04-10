# Bash 工具 Phase 1 重构设计方案

> 修订版本：v4（Spec Review 全部通过）
> 日期：2026-03-30

## 1. 背景与目标

当前 `BashExecutor` 存在以下严重问题：
- 安全架构名存实亡（CLAUDE.md 描述的 `DangerousPatternValidator` 等组件不存在）
- `PathApprovalService` 存在但未集成，且依赖 `System.in` 无法在 Web 场景使用
- 资源泄漏（`ExecutorService` 从不关闭）
- 编码硬编码 GBK
- 后台进程无追踪机制

**Phase 1 目标**：建立安全的命令执行基础设施，实现"警告+审批"双层防护，进程可观测可管理。

---

## 2. 架构总览

```
BashToolConfig (Tool Entry)
       │
       ▼
SecurityInterceptor (安全拦截层)
   ├── PathNormalizer        (路径归一化)
   ├── DangerousPatternValidator (正则扫描)
   └── ApprovalService       (审批缓存)
       │
       ▼
BashExecutor (执行核心)
   ├── ProcessRegistry       (ProcessHandle 管理)
   ├── StreamHandler         (异步流 + stderr 合并)
   └── ProcessTreeKiller     (跨平台进程销毁)
       │
       ▼
ResponseFormatter
```

---

## 3. 第一层：基础设施

### 3.1 ManagedProcess 实体

```java
public enum ProcessState { RUNNING, TERMINATED, KILLED }

public record ManagedProcess(
    String pid,
    ProcessHandle handle,       // Java 9+，用于非阻塞等待和进程控制
    String command,
    String description,
    Instant startTime,
    ProcessState state,
    boolean isBackground
) {}
```

**关键**：每个 `ManagedProcess` 必须持有对应的 `ProcessHandle` 引用，否则 `kill(pid)` 和 `onExit()` 回调无法工作。

### 3.2 ProcessRegistry

| 方法 | 职责 |
|-----|------|
| `register(ManagedProcess)` | 启动时注册（含 ProcessHandle） |
| `unregister(pid)` | 进程结束时注销（由 onExit 回调触发） |
| `listAll()` | 返回所有注册进程 |
| `get(pid)` | 查询单个进程状态 |
| `kill(pid)` | 通过 ProcessHandle.destroyForcibly() 强制终止 |
| `forceKillAll()` | Spring @PreDestroy / shutdown hook，清理所有残留进程 |

**技术细节**：
- 使用 `ProcessHandle.onExit().thenAccept(p -> registry.unregister(pid))` 实现非阻塞进程结束自动注销
- 后台进程 `executeBackground()` 启动后**立即注册**，前台进程 `execute()` 执行完毕后**立即注销**
- 已终止进程元数据最多保留 50 条，超出后 FIFO 淘汰
- `forceKillAll()` 注册为 Spring `@PreDestroy` 生命周期回调

---

## 4. 第二层：安全拦截

### 4.1 危险命令分类

| 级别 | 模式 | 行为 |
|-----|------|------|
| 直接拒绝 | `rm -rf /`, `mkfs`, `:(){:|:&};:`, `fork bomb` | 立即拒绝，返回错误 |
| 需审批 | `rm -rf`, `chmod 777`, `dd`, `>|file` | 触发审批流程 |
| 放行 | `ls`, `cat`, `grep`, `git`, `mvn`, `npm` | 直接执行 |

**注意**：`$()`、反引号 `` ` ``、环境变量 `$VAR`、`%VAR%` **直接拒绝**，因为审批时无法预知其运行时展开结果，等效于未知命令执行。

### 4.2 命令注入检测（引用外检测）

以下符号仅在**引号外部**出现时触发拒绝：

- **命令链**：`;`, `|`, `&&`, `||`
- **子壳执行**：`` ` ``（反引号），`$()`
- **环境变量**：`$` 开头（Shell 变量），`%VAR%`（CMD 变量）
- **重定向**：`>`，`>>`
- **CMD 转义符**：`^`（CMD 的转义字符）

**引用内放行**：出现在单引号 `''` 或双引号 `""` 内部的上述符号不检测（因为 shell 不会在引用内展开）。
**转义放行**：前导反斜杠 `\` 或 CMD 的 `^` 后的字符不检测。

**归一化流程**：
1. 去除所有转义前缀（`\` 开头，CMD 的 `^` 结尾）
2. 标记所有引号范围（`"..."`, `'...'`）
3. 在引号范围外扫描上述危险符号
4. 任何匹配直接拒绝

### 4.3 路径归一化

```java
// 1. 折叠多斜杠
command.replaceAll("/+", "/")
// 2. Java 11+ Path 统一处理 Unix/Windows 的 /./ 和 /../ 跨越
.normalizePathSegments()
// 3. Windows 多反斜杠折叠
.replaceAll("\\\\+", "\\\\");

// 4. 拒绝 WORKSPACE 之外的路径（使用 Path.realPath() 解析符号链接）
Path resolved = Paths.get(WORKSPACE).resolve(command).toAbsolutePath().normalize();
if (!resolved.startsWith(WORKSPACE)) {
    throw new SecurityException("禁止访问 WORKSPACE 之外的路径");
}
```

### 4.4 命令预处理（Whitespace 归一化）

审批比对前，对命令进行空白符归一化：

```java
// 归一化空白：将连续空白压缩为单个空格（供审批比对用）
command.replaceAll("\\s+", " ").trim();
```

**严格相等校验**：审批时，传入命令的归一化结果必须与票根中存储的归一化结果完全相等。

### 4.5 审批流程（Web 场景）

**Agent 端审批等待策略**：

```java
// checkAndConsume() 返回值含义：
// - APPROVED:  票根存在、approved=true、command 匹配 → 放行执行
// - PENDING:   票根存在但 approved=null（用户尚未审批）→ 返回前端 "⏸️ 待审批"
// - EXPIRED:   票根不存在或已超时 → 返回前端 "审批超时，请重新发起"
// - REJECTED:  票根存在但 approved=false → 返回前端 "命令已被拒绝"
```

**流程**：
1. Agent 调用 `SecurityInterceptor.check(command)`
2. 若返回 `PENDING`：Agent **暂停执行**，返回 "⏸️ 待审批 [ticketId]" 给前端，**不重试**
3. 前端用户审批后，Agent 收到新一轮用户输入（如 "继续执行"）
4. Agent 携带 `ticketId` 调用 `checkAndConsume()`
5. 若返回 `APPROVED`：执行命令；若返回其他：向用户报告状态

**禁止**：Agent 不得自行轮询审批结果，必须由用户在 UI 触发下一次对话。

**完整流程图**：

```
Agent 执行危险命令
       │
       ▼
SecurityInterceptor.check(command)
   ├── 生成 TicketID (UUIDv4)
   ├── 归一化命令 whitespace
   ├── 缓存 [TicketID → (normalizedCommand, null, timestamp)]
   │
       ▼
返回前端: "⏸️ 待审批 [ticketId] command=rm -rf ./src"
       │
       ▼
前端显示审批弹窗
       │
  用户点 Allow / Deny
       │
       ▼
POST /approval { ticketId, approved, command }
       │
       ▼
ApprovalService
   ├── 强绑定校验：归一化后的 command 必须与 TicketID 关联的原命令完全一致
   ├── 写入 [TicketID → (command, approved, timestamp)]
   ├── TTL: 5 分钟（由 ScheduledExecutorService 定时清理，使用 map.compute() 原子判断）
   └── 使用后立即失效（remove() 而非查询）
       │
       ▼
Agent 携带 ticketId 重试
       │
       ▼
ApprovalService.checkAndConsume(ticketId, normalizedCommand)
   ├── 原子操作：ConcurrentHashMap.remove(key) 返回旧值，无则 null
   ├── 值存在且 approved=true → 放行
   ├── 值存在且 approved=false → 拒绝
   ├── 值不存在或 command 不匹配 → 拒绝（票根已失效/被篡改）
   └── TTL 超时 → 自动失效 → 拒绝
```

### 4.6 审批接口

```
POST /approval
Body: { "ticketId": "uuid", "approved": true/false, "command": "rm -rf ./src" }

Response: { "success": true/false, "message": "..." }
```

---

## 5. 第三层：执行核心

### 5.1 BashExecutor.execute() 重构

**异步流处理**：
```java
// 使用 CompletableFuture 并行读取输出
CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
    return new BufferedReader(new InputStreamReader(process.getInputStream(), charset))
        .lines()
        .collect(Collectors.joining("\n"));
}, executor);
```

**编码处理**：
```
1. 优先: Charset.defaultCharset()
2. 回退: UTF-8
3. 兜底: GBK (Windows 旧文件兼容)
```

**stderr 合并**：
```java
builder.redirectErrorStream(true);  // 合并 stderr 到 stdout
```

### 5.2 线程池管理

- 使用 Spring 托管的 `ThreadPoolTaskExecutor`
- `setWaitForTasksToCompleteOnShutdown(true)`
- `setAwaitTerminationSeconds(30)` — 有界等待，防止应用无限阻塞
- 核心线程数 4，最大 16，队列容量 100，拒绝策略 `CallerRunsPolicy`（满载时由调用线程直接执行，防止任务丢失）
- 禁止：裸 `new Thread()` / `Executors.newSingleThreadExecutor()` / `Executors.newCachedThreadPool()`

**Shutdown 序列**：
1. Spring 触发 `@PreDestroy`
2. `forceKillAll()` 强制终止所有注册进程
3. `executor.shutdown()`，等待最多 30 秒让任务完成
4. 30 秒后仍未结束的线程由 JVM 强制中断

### 5.3 进程销毁（Windows 僵尸进程防护）

```java
// Unix: pkill -P <pid> 杀子进程，kill -9 <pid> 杀主进程
ProcessBuilder("pkill", "-P", pid).start();
ProcessBuilder("kill", "-9", pid).start();

// Windows: taskkill /F /T /PID <pid> 强制杀进程树
ProcessBuilder("cmd", "/c", "taskkill /F /T /PID " + pid).start();
```

---

## 6. 文件变更清单

| 操作 | 文件 |
|-----|------|
| 新增 | `ManagedProcess.java` |
| 新增 | `ProcessRegistry.java` |
| 新增 | `SecurityInterceptor.java` |
| 新增 | `DangerousPatternValidator.java` |
| 新增 | `PathNormalizer.java` |
| 新增 | `ApprovalService.java` |
| 新增 | `ApprovalController.java` |
| 新增 | `SecurityException.java` |
| 新增 | `approved-commands.properties` |
| 修改 | `BashExecutor.java` |
| 修改 | `BashToolConfig.java` |
| 修改 | `ProcessTreeKiller.java` |
| 修改 | `ResponseFormatter.java` |

---

## 7. 验收标准

- [ ] `rm -rf /` 立即被拒绝
- [ ] `ls` 类只读命令无拦截
- [ ] `ls; rm -rf`（命令注入）被拒绝
- [ ] `ls $(whoami)`（子壳注入）被拒绝
- [ ] `echo evil > /etc/passwd`（重定向攻击）被拒绝
- [ ] 危险命令触发前端审批弹窗
- [ ] 白名单配置的命令直接放行
- [ ] 审批 TicketID 与 command 强绑定校验
- [ ] 后台进程可被 `bash(action=kill, pid=xxx)` 终止
- [ ] `mvn test` 输出不再乱码
- [ ] 应用关闭时所有后台进程被清理

---

## 8. 设计原则

1. **警告但不阻止**：危险命令不直接拒绝，通过审批机制由人决策
2. **审批与执行解耦**：HTTP 无状态，审批结果通过 TicketID 传递
3. **强绑定防重放**：TicketID 必须与 command 原文匹配
4. **一次性消费**：审批结果使用后立即失效
5. **可观测**：所有进程注册到 Registry，状态可查
