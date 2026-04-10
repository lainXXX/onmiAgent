# Agent Tool 实现方案

> 本文档详细描述 Claude Code 源码中 Agent Tool 的核心实现逻辑，包括架构设计、生命周期管理、上下文传递、异步任务处理等核心机制。

---

## 一、整体架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                          AgentTool                                   │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────────┐  │
│  │ AgentTool.tsx   │  │ runAgent.ts       │  │ forkSubagent.ts    │  │
│  │ - Tool 定义      │  │ - Agent 核心运行  │  │ - Fork 子 Agent    │  │
│  │ - Schema 定义   │  │ - 上下文构建       │  │ - 消息重构          │  │
│  │ - 调用入口       │  │ - 生命周期管理     │  │                    │  │
│  └────────┬────────┘  └────────┬─────────┘  └────────────────────┘  │
│           │                    │                                    │
│  ┌────────▼────────────────────▼────────────────────────────────┐   │
│  │                      loadAgentsDir.ts                          │   │
│  │  - Agent 定义加载（Built-in / Plugin / User / Project）        │   │
│  │  - Frontmatter 解析                                            │   │
│  │  - Zod Schema 验证                                             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │                    built-in/ (内置 Agent)                       │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌──────────┐ │  │
│  │  │ verification │ │  explore    │ │   plan      │ │ general  │ │  │
│  │  │   Agent     │ │   Agent     │ │   Agent     │ │ purpose  │ │  │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └──────────┘ │  │
│  └─────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 二、核心类型定义

### 2.1 Agent 定义类型层次

```
BaseAgentDefinition (基础字段)
    │
    ├── BuiltInAgentDefinition (内置 Agent)
    │       - source: 'built-in'
    │       - getSystemPrompt: (params) => string (动态)
    │       - callback?: () => void
    │
    ├── CustomAgentDefinition (用户/项目 Agent)
    │       - source: 'userSettings' | 'projectSettings' | 'policySettings' | 'flagSettings'
    │       - getSystemPrompt: () => string (闭包)
    │       - filename?: string
    │
    └── PluginAgentDefinition (插件 Agent)
            - source: 'plugin'
            - plugin: string
```

### 2.2 AgentDefinition 核心字段

```typescript
interface BaseAgentDefinition {
  agentType: string              // Agent 唯一标识符
  whenToUse: string              // 描述何时使用（用于 UI 显示）
  tools?: string[]               // 允许使用的工具列表（白名单）
  disallowedTools?: string[]    // 禁止使用的工具列表（黑名单）
  skills?: string[]             // 预加载的 Skills
  mcpServers?: AgentMcpServerSpec[]  // Agent 专用的 MCP 服务器
  hooks?: HooksSettings         // 生命周期钩子
  color?: AgentColorName        // UI 颜色
  model?: string                // 模型（'inherit' 表示继承父 Agent）
  effort?: EffortValue         // 努力程度
  permissionMode?: PermissionMode  // 权限模式
  maxTurns?: number             // 最大轮次限制
  background?: boolean          // 是否后台运行
  initialPrompt?: string        // 首个用户消息前追加的提示
  memory?: AgentMemoryScope     // 持久化记忆范围
  isolation?: 'worktree' | 'remote'  // 隔离模式
  omitClaudeMd?: boolean        // 是否省略 CLAUDE.md
  criticalSystemReminder_EXPERIMENTAL?: string  // 每次用户消息注入的提醒
}
```

---

## 三、Agent 生命周期

### 3.1 生命周期流程图

```
┌─────────────────────────────────────────────────────────────────┐
│                     Agent 生命周期                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 创建 (AgentTool.call())                                      │
│     ├── 解析输入参数                                              │
│     ├── 选择 Agent 类型                                           │
│     ├── 权限检查 (filterDeniedAgents)                             │
│     ├── MCP 需求检查 (hasRequiredMcpServers)                       │
│     └── 决定同步/异步模式                                          │
│            │                                                     │
│  2. 初始化 (runAgent)                                             │
│     ├── 创建 AgentId                                             │
│     ├── 克隆/创建文件状态缓存                                      │
│     ├── 解析用户/系统上下文                                        │
│     ├── 初始化 Agent 专用 MCP 服务器                               │
│     ├── 预加载 Skills                                             │
│     ├── 注册 Frontmatter Hooks                                    │
│     └── 创建 Subagent Context                                     │
│            │                                                     │
│  3. 执行 (query 循环)                                             │
│     ├── yield message                                            │
│     ├── 记录 Sidechain Transcript                                │
│     ├── 检查 maxTurns                                            │
│     └── 处理流式响应                                              │
│            │                                                     │
│  4. 清理 (finally)                                               │
│     ├── 清理 Agent MCP 服务器                                     │
│     ├── 清理 Session Hooks                                       │
│     ├── 清理 Prompt Cache 追踪                                    │
│     ├── 释放文件状态缓存                                          │
│     ├── 注销 Perfetto 注册                                        │
│     ├── 清理 Todos 条目                                          │
│     └── Kill 背景 Bash 任务                                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 同步 vs 异步 Agent

| 特性 | 同步 (Sync) | 异步 (Async) |
|------|-------------|--------------|
| 运行模式 | 与父 Agent 共享上下文 | 独立 AbortController |
| setAppState | 共享父的 AppState | 独立隔离 |
| 响应方式 | 直接返回结果 | 返回 agentId，通过文件轮询 |
| 权限模式 | 继承父的权限 | 可独立配置 |
| 使用场景 | 短时任务 | 长时间运行任务 |

---

## 四、上下文传递机制

### 4.1 forkSubagent 的消息重构

当使用 `AgentTool` 不指定 `subagent_type` 时，触发 Fork 模式：

```typescript
// forkSubagent.ts: buildForkedMessages

// 输入：
//   - directive: 子 Agent 的任务指令
//   - assistantMessage: 父 Agent 最后的助手消息（包含 tool_use 块）

// 输出：
//   [fullAssistantMessage, toolResultMessage]

// 消息结构：
// [
//   {
//     type: 'assistant',
//     content: [...all_tool_use_blocks...]  // 保留父的所有 tool_use
//   },
//   {
//     type: 'user',
//     content: [
//       { type: 'tool_result', tool_use_id: 'xxx', content: 'Fork started...' },  // 占位
//       { type: 'tool_result', tool_use_id: 'yyy', content: 'Fork started...' },  // 占位
//       ...
//       { type: 'text', text: '<FORK_BOILERPLATE_TAG>...指令...</FORK_BOILERPLATE_TAG>' }
//     ]
//   }
// ]
```

**核心设计**：所有 `tool_result` 使用相同的占位文本（`FORK_PLACEHOLDER_RESULT`），确保 API 请求前缀字节一致，最大化 Prompt Cache 命中率。

### 4.2 上下文继承策略

| 上下文类型 | 继承方式 | 说明 |
|-----------|---------|------|
| System Prompt | Override/继承 | Fork 模式传递父的 renderedSystemPrompt |
| Tools | 继承/过滤 | Fork: `useExactTools` 直接继承父工具池 |
| File State | 克隆 | 从父的缓存克隆，独立演进 |
| MCP Servers | 合并 | 父的服务器 + Agent 专用服务器 |
| Hooks | 隔离注册 | 独立注册，Agent 销毁时清理 |
| Skills | 预加载 | 通过 `skills` 字段预加载 |
| Thinking | Fork 继承/其他禁用 | Fork 继承父的 thinkingConfig |

---

## 五、Agent 类型注册与加载

### 5.1 加载优先级

```
1. built-in agents (内置)
2. plugin agents (插件)
3. user settings agents (用户配置)
4. project settings agents (项目配置)
5. flag settings agents (功能开关)
6. policy settings agents (策略配置)
```

同类型 `agentType` 后注册的覆盖先注册的。

### 5.2 Frontmatter Schema

```yaml
---
name: my-agent              # required, Agent 唯一标识
description: Use when...   # required, 何时使用此 Agent
tools: [Read, Write, Bash] # optional, 工具白名单
disallowedTools: [Edit]    # optional, 工具黑名单
model: sonnet              # optional, 'inherit' 继承父
permissionMode: plan       # optional, 权限模式
maxTurns: 50               # optional, 最大轮次
background: true           # optional, 默认后台运行
skills: [tdd, code-review] # optional, 预加载 skill
initialPrompt: "You are..." # optional, 首个消息前追加
mcpServers:                # optional, 专用 MCP
  - slack
  - github
hooks:                    # optional, 生命周期钩子
  onAgentStart: ...
  onAgentStop: ...
memory: project           # optional, 持久化记忆
isolation: worktree       # optional, 隔离模式
---
# Agent 的系统提示（不含 frontmatter 的其余部分）
You are a specialized agent that...
```

---

## 六、Built-in Agent 详解

### 6.1 内置 Agent 类型

| Agent | 用途 | 类型 | One-Shot |
|-------|------|------|----------|
| `general-purpose` | 默认通用 Agent | Built-in | ❌ |
| `Explore` | 代码探索 | Built-in | ✅ |
| `Plan` | 计划制定 | Built-in | ✅ |
| `verification` | 验收测试 | Built-in | ❌ |
| `statusline-setup` | 状态栏设置 | Built-in | ❌ |
| `claude-code-guide` | Claude Code 使用指南 | Built-in | ❌ |

### 6.2 Verification Agent 设计

```typescript
// verificationAgent.ts

const VERIFICATION_SYSTEM_PROMPT = `You are a verification specialist.
Your job is not to confirm the implementation works — it's to try to break it.

Two documented failure patterns:
1. verification avoidance: you find reasons not to run checks
2. being seduced by the first 80%: you see a polished UI and pass it

=== CRITICAL: DO NOT MODIFY THE PROJECT ===
- STRICTLY PROHIBITED from: creating, modifying, deleting files
- MAY write ephemeral test scripts to /tmp

=== VERIFICATION STRATEGY ===
Adapt based on what was changed:
- Frontend: Start dev server → browser automation → check console
- Backend/API: Start server → curl endpoints → verify response shapes
- CLI/script: Run with inputs → verify stdout/stderr/exit codes
- Infrastructure: Validate syntax → dry-run → check env vars

=== REQUIRED STEPS ===
1. Read project's CLAUDE.md / README
2. Run the build
3. Run the test suite
4. Run linters/type-checkers
5. Check for regressions

=== OUTPUT FORMAT ===
Every check MUST follow this structure:
### Check: [what you're verifying]
**Command run:** [exact command]
**Output observed:** [actual output]
**Result:** PASS | FAIL

End with: VERDICT: PASS | FAIL | PARTIAL`
```

---

## 七、异步任务管理

### 7.1 LocalAgentTask 架构

```
┌─────────────────────────────────────────────────────────────┐
│                    LocalAgentTask                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  registerAsyncAgent()                                        │
│      │                                                       │
│      ├── 创建 agentId + AbortController                      │
│      ├── 创建 ActivityDescriptionResolver                    │
│      ├── 创建 ProgressTracker                                │
│      ├── 注册到 AppState.tasks                               │
│      └── 返回 { agentId, abortController, ... }              │
│              │                                               │
│  runAsyncAgentLifecycle()                                    │
│      │                                                       │
│      ├── 运行 runAgent (Generator)                           │
│      ├── 捕获 yield 的 message                               │
│      ├── 更新 ProgressTracker                                │
│      ├── 处理 maxTurns                                       │
│      └── 处理 AbortError                                     │
│              │                                               │
│  通知机制                                                     │
│      ├── enqueueAgentNotification() → SDK event              │
│      └── AppState.todos[agentId] 更新 → UI 通知               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 7.2 进度追踪

```typescript
// ProgressTracker 结构
interface ProgressTracker {
  agentId: string
  description: string
  startedAt: number
  lastProgressAt: number
  progressMessage?: string
  tokenCount?: number
  status: 'running' | 'completed' | 'failed' | 'killed'
}
```

---

## 八、Context 隔离与共享

### 8.1 Sync Agent 共享策略

```typescript
// runAgent.ts: createSubagentContext

shareSetAppState: !isAsync      // Sync 共享，Async 隔离
shareSetResponseLength: true     // 两者都贡献响应指标

// Sync Agent 的特性：
// - 共享 abortController（父终止子也终止）
// - 共享 AppState（todos, agentId 等）
// - 直接 yield 消息给父的 query 循环
```

### 8.2 Async Agent 隔离策略

```typescript
// Async Agent 创建独立的：
// - abortController（可独立终止）
// - AppState 副本（通过 setAppStateForTasks 写回根 store）
// - 文件状态缓存（克隆后独立演进）
// - Session Hooks（独立注册/清理）
```

---

## 九、MCP 服务器集成

### 9.1 Agent 专用 MCP

```typescript
// runAgent.ts: initializeAgentMcpServers

interface AgentMcpServerSpec {
  // 引用方式
  | string                    // 引用已有配置
  // 或内联定义
  | { [name: string]: McpServerConfig }
}

// 执行流程：
// 1. 解析 spec（引用或内联）
// 2. 调用 connectToServer(name, config)
// 3. 获取 tools: fetchToolsForClient(client)
// 4. 合并到 Agent 的工具池
// 5. 在 finally 中清理：只清理新建的，引用共享的不清理
```

### 9.2 MCP 权限控制

```typescript
// 只有 admin-trusted 的 Agent 可以使用 frontmatter MCP
const agentIsAdminTrusted = isSourceAdminTrusted(agentDefinition.source)
if (isRestrictedToPluginOnly('mcp') && !agentIsAdminTrusted) {
  // 拒绝加载
}
```

---

## 十、Transcript 与恢复

### 10.1 Sidechain Transcript

每个 Agent 维护独立的 transcript 文件：

```
.session/
  └── subagents/
      └── <agentId>/
          ├── transcript.jsonl   # 完整消息历史
          └── metadata.json      # Agent 类型、描述、工作目录
```

### 10.2 恢复机制

```typescript
// resumeAgent.ts

// 1. 读取原有 transcript
const [transcript, meta] = await Promise.all([
  getAgentTranscript(asAgentId(agentId)),
  readAgentMetadata(asAgentId(agentId))
])

// 2. 过滤无效消息
const resumedMessages = filterWhitespaceOnlyAssistantMessages(
  filterOrphanedThinkingOnlyMessages(
    filterUnresolvedToolUses(transcript.messages)
  )
)

// 3. 重建 contentReplacementState
const resumedReplacementState = reconstructForSubagentResume(...)

// 4. 重建 Agent 上下文
// - 如果是 Fork Agent：使用父的 renderedSystemPrompt
// - 否则：重新计算
// - 工作目录：使用原 worktreePath（如果存在）
```

---

## 十一、关键设计决策

### 11.1 为什么使用 Sidechain Transcript？

| 设计 | 说明 |
|------|------|
| 独立文件 | 不污染主会话的 ChatMemory |
| 支持恢复 | Agent 失败后可从 transcript 恢复 |
| 隔离性 | 每个 Agent 的消息历史独立管理 |
| 清理 | Agent 结束时清理对应的 transcript |

### 11.2 为什么 Fork 使用占位符？

```
目的：最大化 Prompt Cache 命中率

所有 Fork 子 Agent 的 API 请求前缀（直到 directive text）完全一致：
- 相同的 System Prompt
- 相同的 Assistant Message（包含相同的 tool_use 块）
- 相同的 Tool Result 块（都是占位符）
- 只有最后的 directive text 不同

这样 Anthropic 的 Prompt Cache 可以缓存前缀，
不同的 directive text 作为增量发送。
```

### 11.3 为什么 Explore/Plan 是 One-Shot？

```typescript
// constants.ts
export const ONE_SHOT_BUILTIN_AGENT_TYPES: ReadonlySet<string> = new Set([
  'Explore',
  'Plan',
])

// 优点：
// - 节省 tokens（~135 chars × 34M runs/week）
// - 不需要追踪状态
// - 不需要恢复机制
```

---

## 十二、总结

### 12.1 核心价值

| 价值 | 实现 |
|------|------|
| 专业化 | 内置多种专用 Agent（Explore/Plan/Verification） |
| 隔离性 | 独立上下文、文件缓存、Hooks、MCP |
| 可恢复 | Sidechain Transcript 支持从中断处恢复 |
| 高效 | Fork 占位符设计最大化 Prompt Cache |
| 灵活 | 支持同步/异步、隔离模式、背景运行 |
| 安全 | 权限模式、Tool 过滤、MCP 访问控制 |

### 12.2 关键文件索引

| 文件 | 职责 |
|------|------|
| `AgentTool.tsx` | Tool 定义、Schema、调用入口 |
| `runAgent.ts` | Agent 核心执行逻辑、生命周期管理 |
| `forkSubagent.ts` | Fork 模式消息重构 |
| `loadAgentsDir.ts` | Agent 定义加载、解析、验证 |
| `builtInAgents.ts` | 内置 Agent 注册 |
| `resumeAgent.ts` | Agent 恢复逻辑 |
| `agentToolUtils.ts` | 工具函数、结果处理 |
| `built-in/verificationAgent.ts` | 验收 Agent 系统提示词 |
