# OmniAgent 智能体系统 — 简历项目亮点完整版

> 本文档从六大维度 + 附加亮点，系统梳理 OmniAgent 项目在 AI Agent 系统架构层面的全部核心技术亮点与创新，适合作为简历项目描述的技术支撑文档。
>
> 项目基于 **Spring Boot 3.5.10 + Spring AI 1.1.3 + Java 21**，采用 MySQL（对话历史）+ PostgreSQL/pgvector（向量检索）双存储架构。

---

## 一、Prompt Engineering（提示词工程）

### 1.1 六层动态 Prompt 管道

`MessageFormatAdvisor` 在请求时**运行时动态组装**六层 Prompt 结构，无需重启即可热更新工具/技能描述：

```
[1] SystemMessage          ← agent_system_prompt.md（ReAct 自主执行者 Persona）
[2] UserMessage (TOOLS)   ← 运行时从 ToolsManager 动态加载，无侵入
[3] UserMessage (SKILLS)  ← LLM 自动生成，含 IF/THEN 强制触发规则
[4] HistoryMessages       ← MySQL 加载，parentId 树状链式结构
[5] UserMessage           ← 当前输入
```

**核心价值**：工具和技能的描述是运行时动态注入的，新增工具或技能无需修改任何模板，实现"工具/技能对模型透明"。

### 1.2 LLM 驱动的技能编排编译器

传统靠 few-shot 示例"猜测"何时调用技能，准确率低。OmniAgent 在启动时调用 LLM 将所有技能描述编译为可执行编排 DSL：

```java
ChatClient.builder(chatModel)
    .system("You are an Agent Logic Compiler...")
    .user(skillList)  // 所有 skill 的 name + description
    .options(OpenAiChatOptions.builder().temperature(0.2).build())
    .call();
// 输出：<EXTREMELY-IMPORTANT> 块，含 IF/THEN、GOLDEN-CHAINS、FALLBACK-STRATEGY
```

| 编排原语 | 作用 |
|---------|------|
| `IF/THEN` | 场景强制触发，如"IF 用户要求创建页面 THEN 强制调用 frontend-design" |
| `GOLDEN-CHAINS` | 跨技能编排链，如"创建页面 → frontend-design → simplify 优化" |
| `FALLBACK-STRATEGY` | 技能失效时的降级兜底策略 |

### 1.3 五类 Agent 专属动态 Prompt

`SubAgentChatClientFactory` 为 EXPLORE / PLAN / CODE_REVIEWER / GENERAL / CLAUDE_CODE_GUIDE 五类 Agent 动态生成专属 system prompt（15+ 行），包含角色定义、工作流步骤、输出格式要求、工具权限声明。

---

## 二、Context Engineering（上下文工程）

### 2.1 Advisor Chain 三链合一管道

严格按优先级划分的 Advisor 执行阶段（解决 AOP 交叉关注混乱问题）：

| Advisor | 优先级 | 职责 |
|---------|--------|------|
| `LifecycleToolCallAdvisor` | `Integer.MAX_VALUE-1000`（近最高） | 工具生命周期、消息持久化 |
| `ContextCompressionAdvisor` | 4000 | 长对话 LLM 摘要压缩 |
| `MessageFormatAdvisor` | 10000（最低） | Prompt 组装、技能/工具注入、ThreadLocal 清理 |

执行顺序：`MessageFormatAdvisor.before()` → `LifecycleToolCallAdvisor.doInitializeLoop()` → 工具循环 → `LifecycleToolCallAdvisor.doFinalizeLoop()` → `MessageFormatAdvisor.after()`

### 2.2 双库持久化架构

- **MySQL**（`chat_memory` 表）：对话历史 + parentId 树状消息链，支持 USER/ASSISTANT/SYSTEM/TOOL 四类消息 JSON 序列化
- **PostgreSQL/pgvector**：存储 LLM 摘要（`type=summary`）、Skill 向量（`type=skill`），支持语义检索恢复历史上下文

**全链路消息持久化时序**：
- 用户消息：工具循环**开始前**持久化（`doInitializeLoop`）
- Tool 调用记录：循环**中途**持久化（Assistant ToolCall + ToolResultMessage 配对）
- Assistant 消息：循环**结束后**持久化（`doFinalizeLoop`，需校验 `AgentFinishStatus.STOP` 防截断）

### 2.3 异步上下文压缩（零延迟影响）

```
触发：tokenCount > threshold × contextWindow
     ↓
保留 head N + tail M 条
     ↓
中间段 → LLM 异步生成摘要（temperature=0.1）
     ↓
摘要存入 pgvector（type=summary）
     ↓
中间原始记录从 MySQL 删除
```

压缩任务提交至独立 `ThreadPoolExecutor`，在流式响应返回**之后**异步执行，对首响延迟零影响。

### 2.4 流式聚合守卫 + ThreadLocal 防治

- `ChatClientMessageAggregator`：收集所有 streaming chunk 拼装完整响应后再触发 `after()`，解决"流式下 advisor 无法获取完整输出"的经典问题
- `ThreadLocalUtil.clear()` 在 `after()` 中清理线程变量，防止 Tomcat 线程池复用导致跨请求数据泄漏
- `TaskProgressAdvisor`：每 3 轮无进展自动注入进度提醒（`Nag threshold` 机制）

---

## 三、工具系统（Tool System）

### 3.1 BeanPostProcessor 零侵入自动注册

`ToolsManager` 实现 `BeanPostProcessor`，启动时自动扫描并注册所有工具，无需维护注册表：

```java
// 路径1：AgentTool 接口实现 → ToolCallbacks.from(bean) 自动检测 @Tool 方法
if (bean instanceof AgentTool agentTool) {
    toolCallbackResolver.addTools(agentTool.tools());
}
// 路径2：@Bean + @Description → 自动注册为 Function 风格工具
```

新增工具 = 实现 `AgentTool` 接口或提供带 `@Description` 的 `@Bean`，框架自动扫描注册，**零配置**。

### 3.2 工具分类矩阵

| 类别 | 工具 | 亮点 |
|------|------|------|
| File | Read/Write/Edit/Grep/Glob | 带行号、分页；先 Glob 再 Read 强制规范；二进制文件过滤（24种）|
| Web | WebSearch/WebFetch | 自研 HTML→Markdown 转换（无外部库）；内容截断 50K 字符 |
| Bash | BashExecutor | 多层安全沙箱（见 3.3） |
| RAG | semantic_search/read_chunks/list_files/get_file_metadata | Agent 实时调用 RAG |
| Agent | TaskCreate/Update/List, launchAgent | 子 Agent 编排 + resume |
| System | AskUserQuestion/SkillTool | SSE 推送 + 轮询双通道 |

### 3.3 Bash 五层安全沙箱

```
命令输入
    ↓
[Layer 1] DangerousPatternValidator   正则匹配高危模式（; & | > < $() `` 等）
    ↓
[Layer 2] SuicideCommandDetector     语义检测：rm -rf /, :(){:|:&};:, mkfs, dd if=/dev/zero
    ↓
[Layer 3] CommandApprover            中央审批门，支持 dangerouslyDisableSandbox 紧急覆盖
    ↓
[Layer 4] PathApprovalService        路径白名单，执行前验证所有涉及路径
    ↓
[Layer 5] ProcessTreeKiller          超时/终止时遍历 kill 完整进程树（Unix pkill -P / Windows taskkill /T）
```

### 3.4 文件操作安全三连

- **Write**：覆盖前自动创建 `.bak`，异常时自动回滚
- **Edit**：多位置匹配拒绝（防止误改）+ `\r\n`/`\n` 自动规范化
- **Read**：编码自动检测（UTF-8→GBK fallback）+ 文件不存在时列出父目录内容辅助纠错

### 3.5 自研 HTML→Markdown 转换（无外部库依赖）

`WebFetchToolConfig` 使用纯 Regex 实现 HTML 到 Markdown 转换：脚本/样式移除、HTML 实体解码（`&nbsp;`→` `等）、标题/段落/列表/代码块/链接/图片/强调转换，不引入 Jsoup 等外部依赖。

---

## 四、Skill 系统

### 4.1 启动时自动扫描 + pgvector 语义存储

```java
@PostConstruct
public void loadSkills() {
    // 扫描 classpath:/skills/**/SKILL.md
    // 解析 YAML front matter (name, description, path)
    // 存入 pgvector (type=skill)
}
```

### 4.2 双哈希增量同步

| 哈希 | 计算方式 | 作用 |
|------|---------|------|
| `semanticHash` | MD5(description) | 检测内容变更 |
| `metadataHash` | MD5(name + path) | 检测路径/名称变更 |

启动时比对本地文件 vs DB 快照，**仅推送增量**，防止僵尸数据。

### 4.3 技能语义激活

用户请求涉及某技能领域时，通过 pgvector 语义检索找到最相关的 Skill 描述，注入 Prompt SkillsGuide 层，触发 IF/THEN 规则，**自动路由而非硬编码匹配**。

---

## 五、Agent Advisor（智能体编排）

### 5.1 生命周期四阶段钩子

`LifecycleToolCallAdvisor` 继承 Spring AI `ToolCallAdvisor`，提供：

| 阶段 | 方法 | 职责 |
|------|------|------|
| 初始化 | `doInitializeLoop()` | 持久化用户消息，初始化对话状态 |
| 同步工具调用 | `doGetNextInstructionsForToolCall()` | 单轮工具决策路由 |
| 流式工具调用 | `doGetNextInstructionsForToolCallStream()` | 流式模式工具决策路由 |
| 终结 | `doFinalizeLoop()` | 持久化 Assistant 消息，校验 AgentFinishStatus.STOP |

**双模式支持**：`adviseCall`（同步）+ `adviseStream`（响应式流），共用水循环逻辑。

### 5.2 异步任务管理与并行协作

- `AgentTaskRegistry`（`ConcurrentHashMap`）：追踪全量异步任务 Future，支持 user/session 所有权校验
- `agentExecutor` 线程池：4 核 8 最大，60s 超时，队列容量 50
- `runInBackground=true`：多子 Agent **真正并行独立执行**
- `agentOutput(taskId, block=false)`：非阻塞查询

### 5.3 Git Worktree 沙箱隔离

```
WorktreeManager.createWorktree(taskId, branchName)
    ↓
git worktree add worktrees/agent-{taskId}-{branchName} -b {branch}
    ↓
子 Agent 在隔离目录中执行（修改/删文件不影响主仓库）
    ↓
完成后自动删除 worktree + branch，资源自动回收
```

### 5.4 会话恢复链（resumeChain）

`AgentSessionManager.buildResumePrompt()` 重构上下文，自动插入 `[上下文恢复] 你正在继续一个未完成的任务`，串联 plan→coder→reviewer 等递进式工作流，无需用户手动复制上下文。

### 5.5 终端输出智能检测

```java
// 中英文双检，精准判断任务终结
"任务完成", "done", "【总结】", "final result", "已完成", "completed", "summary"
```

---

## 六、Agentic RAG（检索增强生成）

### 6.1 Parent-Child 双层分块架构

```
原始文档
    ↓
[Parent] ~800 tokens → MySQL（rag_parent_chunks）完整语义
    ↓
[Child] ~200 tokens, ~20 overlap → pgvector（parentId 引用）精准检索
```

**设计原理**：纯小片段检索精度高但丢上下文，纯大片段保语义但检索粒度粗。Parent-Child 架构 = child 检索（精准）+ parent 输出（完整）。

### 6.2 检索→重排→Parent 追溯链路

```
向量搜索(child, topK)
    ↓
Rerank 重排（更强大模型）
    ↓
按 child 顺序提取 parentId（确定优先级）
    ↓
MySQL tuple IN 查询批量获取 parent chunks
    ↓
有序 parent 文档列表（完整上下文）
```

### 6.3 RecursiveTextSplitter 智能分割算法

```
分隔符优先级：["\n\n", "\n", "。", "；", "，", " ", ""]
    ↓
逐级尝试分割 → 超长则递归继续
    ↓
重叠窗口：超长 chunk 提交后回退 overlapSize 继续
    ↓
原子保护：表格(>50%行内容)、代码块不分割
    ↓
小碎片合并：<maxSize/4 → 合并至前一个 chunk
    ↓
死循环防护：maxRecursionDepth=20 + forceSplit() 强制兜底
    ↓
文本清洗：移除页码、控制字符、连续空行
```

### 6.4 Markdown 结构感知分割

`MarkdownHeaderSplitter` 检测 `# {1-6}` 标题，提取面包屑路径 `Chapter 1 > Section 1.2 > Subsection` 存入 metadata；短章节保持独立（`<parentChunkSize`），长章节触发递归子分割；无 Markdown 结构时回退到 `RecursiveTextSplitter`。

### 6.5 精准 Token 计数

```java
// JTokkit CL100K_BASE（GPT-4/3.5 同款编码器），非字符估算
TokenCountRequest.of(text, TokenizerType.CL100K_BASE);
```

### 6.6 ETL 并行处理流水线

```
文档解析（按扩展名路由）
    ├── .doc/.docx → CustomDocxReader（处理双格式，表格转Markdown）
    └── PDF/TXT/HTML → TikaDocumentReader（通用 fallback）
    ↓
并行处理（min(availableProcessors, 4) 线程）
    ↓
分块：父 → MySQL，子 → pgvector
    ↓
子 chunk 每 100 条批量提交向量库（控制内存）
```

---

## 七、其他亮点（未归类）

### 7.1 双 DataSource + HikariCP 连接池隔离

MySQL 和 PostgreSQL/pgvector 使用独立 DataSource 和 HikariCP 连接池，`@Qualifier` 注入隔离，`pgVectorDataSource` 设置 `initializationFailTimeout=1` 实现快速失败，`NamedParameterJdbcTemplate` 支持 PostgreSQL JSONB。

### 7.2 线程池策略模式 + 配置化

`ThreadPoolConfigProperties` 绑定配置（`corePoolSize`、`maxPoolSize`、`keepAliveTime`、`blockQueueSize`、`policy`），`ThreadPoolConfig` 按策略类型（AbortPolicy/DiscardPolicy/DiscardOldestPolicy/CallerRunsPolicy）动态创建，Agent 专属 `agentExecutor` 独立线程池隔离。

### 7.3 任务系统依赖管理 + 循环依赖检测

```
状态机：pending → in_progress → completed/deleted
    ↓
前置依赖检查：findUnfinishedDependencies() 阻塞 in_progress
    ↓
循环依赖检测：DFS 算法检测 blocks/blockedBy 环
    ↓
下游依赖保护：检查是否被其他任务依赖，防止级联删除
```

`blocks`/`blockedBy` 存为 JSON Array，通过 `TaskException.factory()` 方法（`NOT_FOUND`/`DEPENDENCY_BLOCKED`/`CIRCULAR_DEPENDENCY`）抛出明确错误。

### 7.4 AskUserQuestion：SSE + CompletableFuture 双通道

```
提问请求
    ↓
[主通道] SseEmitter 实时推送（前端 EventSource）
    ↓
[备通道] ConcurrentHashMap 轮询（超时 fallback）
    ↓
ScheduledCleanup 定时清理过期挂起问题（防内存泄漏）
    ↓
CompletableFuture<AskUserResponse> 超时控制
```

`AskUserQuestionYieldException` 暂停 Flux，等待用户回答后恢复。

### 7.5 Java 21 Records 全面应用

大量使用 Java 21 Record 替代 POJO：`AgentResult`、`ChunkReference`、`AgentSession`、`ChatHistoryRow`、`TaskEntity`、`PendingQuestion`、`EtlProcessReport`、`PathCheckResult` 等，简洁不可变性数据建模。

### 7.6 Micrometer 可观测性集成

`LlmCallMonitorObservationHandler` 实现 `ObservationHandler<ChatModelObservationContext>`，与 Spring Boot Actuator + Micrometer 集成，可观测 LLM 调用指标。

### 7.7 自定义 Word 文档双格式读取

`CustomDocxReader` 使用 `FileMagic.valueOf()` 自动检测 OOXML（`.docx`，XWPF）vs OLE2（`.doc`，HWPF），支持 Apache POI 双格式读取，表格转 Markdown 结构保留。

### 7.8 消息序列化（LinkedHashMap 保序）

`MessageConvert` 使用 `LinkedHashMap` 保证 `thinking` 在 `tool_calls` 之前序列化顺序，`AssistantMessage` 包含 text 和 tool_calls 双字段 JSON，`ToolResponseMessage` 转为字符串表示，序列化失败时 graceful degradation。

### 7.9 Spring Boot 现代化配置模式

- `@EnableConfigurationProperties` 绑定 typed 配置类（`ChunkingProperties`、`RerankProperties`、`ContextCompressionProperties`、`ThreadPoolConfigProperties`）
- `@Qualifier` 多 DataSource 注入隔离
- `@EnableAsync` 异步执行支持
- `RestClient` Builder 模式调用外部 API（Rerank/WebSearch/WebFetch）

### 7.10 CORS 动态模式配置

支持 `http://localhost:*` / `http://127.0.0.1:*` 动态 origin，`CorsFilter` 注册全局，`allowCredentials(true)`，`preflight` 缓存 1 小时。

### 7.11 Rerank 服务优雅降级

Rerank 服务不可用时，使用原始顺序 + 递减伪分数（0.99, 0.98, ...）降级，保证系统可用性；支持 BGE/BCE/Qwen 多模型 API 差异处理。

---

## 架构全景图

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户请求                                   │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              MessageFormatAdvisor (优先级 10000)                    │
│  SystemPrompt → ToolsGuide → SkillsGuide → History → UserMsg    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│       LifecycleToolCallAdvisor (优先级 Integer.MAX_VALUE-1000)   │
│                                                                   │
│  doInitializeLoop ──► Tool Loop ──► doFinalizeLoop              │
│        ↑                      │                      │           │
│        │           ┌──────────┴──────────┐         │           │
│        │           ▼                      ▼         │           │
│        │    ┌──────────────┐    ┌────────────────┐ │           │
│        │    │ToolsManager  │    │  Agent / Bash /│ │           │
│        │    │(BeanPostProc)│    │  RAG / File /   │ │           │
│        │    └──────────────┘    │  Web / Skill   │ │           │
│        │                         └────────────────┘ │           │
│        │                                              │           │
│        └──────────────────────────────────────────────┘           │
│                                                                   │
│  MemoryRepository (MySQL)  ◄── 对话历史（User/Assistant/Tool）    │
│  VectorStore (pgvector)    ◄── 摘要(type=summary) / 技能(type=skill) │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              ContextCompressionAdvisor (优先级 4000)               │
│  异步：head+tail 保留，中间 LLM 摘要 → pgvector，MySQL 记录删除     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│               TaskProgressAdvisor (优先级 4000)                    │
│  每3轮无进展注入 "还有任务在进行中" 提醒                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 简历一句话描述（参考）

> 基于 Spring AI Advisor Chain 设计六层动态 Prompt 管道，通过 LLM 驱动的技能编排编译器实现 IF/THEN 强制路由；自研 **Parent-Child 双存储 RAG 架构**（pgvector 召回 + MySQL 原文）配合检索→重排→追溯链路；利用 **BeanPostProcessor 零侵入自动注册**工具系统，以 **Git Worktree 沙箱**隔离子 Agent 执行；通过 `LifecycleToolCallAdvisor` 四阶段钩子统一管理工具生命周期、消息持久化与异步上下文压缩；构建 **AskUserQuestion SSE+CompletableFuture 双通道**用户交互机制；实现带 **DFS 循环检测的任务依赖管理系统**；搭配 Bash 五层安全沙箱、文件操作原子写+自动备份、`Rerank` 优雅降级等企业级安全与稳定性设计。

---

*文档版本：2026-03-31 | 基于 omniAgent 项目全量源码生成*
