# Agent 工具使用指南

## 核心原则

1. **先探索后操作**：不熟悉代码结构时，先用 `glob`/`grep` 定位文件，再用 `read` 查看内容
2. **小步快跑**：复杂任务拆成多个 `Task`，每个任务专注于一件事
3. **原子化修改**：用 `edit` 做精确替换，用 `write`/`edit` 而非整体重写
4. **验证闭环**：每次修改后用 `bash` 运行测试，确保没有破坏现有功能

---

## 文件操作的正确顺序

### 场景：修改一个现有文件

```
1. glob   → 定位文件路径
2. read   → 查看文件内容
3. edit   → 局部修改（推荐）
   或
   write  → 整体重写（确认旧内容后再用）
```

### 场景：创建一个新文件

```
1. 检查父目录是否存在（glob 或直接尝试）
2. write → 创建文件
```

### 场景：搜索包含关键词的代码

```
1. grep → 找到文件和行号
2. read → 查看具体上下文
3. edit → 修改
```

---

## bash 的使用时机

| 用途 | 示例 |
|------|------|
| 编译构建 | `./mvnw compile` |
| 运行测试 | `./mvnw test` |
| 查看进程 | `ps aux \| grep java` |
| 环境探测 | `which java`, `node -v` |
| Git 操作 | `git status`, `git diff` |

**原则**：
- 交互式命令（vim、less）会被截断，避免使用
- 默认超时 2 分钟，耗时任务用 `runInBackground: true`
- 危险操作（rm -rf 等）需要 `dangerouslyDisableSandbox: true`，慎用

---

## 任务的正确使用方式

### 场景：开始一个复杂任务

```
1. TaskCreate → 创建任务，明确目标和验收标准
2. 逐个完成子任务
3. TaskUpdate → 标记进行中/完成
```

### 场景：处理需要并行的工作

```
1. TaskCreate → 创建主任务
2. Agent (runInBackground: true) → 并行启动多个子任务
3. TaskOutput → 获取子任务结果
4. 汇总结果，TaskUpdate → 完成主任务
```

### 场景：执行耗时操作

```
1. bash (runInBackground: true) → 后台启动
   或
   Agent (runInBackground: true) → 子 Agent 执行
2. TaskOutput (block: true) → 阻塞等待结果
```

---

## Agent 的使用场景

| 场景 | Agent 类型 | 说明 |
|------|-----------|------|
| 探索代码库 | `explore` | 快速了解项目结构 |
| 制定计划 | `plan` | 设计实现方案 |
| 代码审查 | `code-reviewer` | 检查代码质量 |
| 复杂任务 | `general` | 需要多步骤自主决策 |

### 使用模式

```
1. Agent → 启动子 Agent 处理独立任务
2. agentOutput → 获取执行结果
3. 主 Agent 汇总并继续
```

---

## 向用户提问的流程

### 何时使用 AskUserQuestion

- 用户需求模糊，需要澄清
- 需要用户确认技术方案
- 提供多个选项让用户选择

### 使用流程

```
1. AskUserQuestion → 发起提问
2. 等待用户响应
3. 根据 answers 继续执行
```

### 处理响应

```java
// answers 是 Map<String, String>
// key = 问题文本，value = 用户选择的选项 label

if (response.timeout) {
    // 用户未响应，按超时处理
} else if (response.skipReason != null) {
    // 用户跳过，终止当前任务
} else {
    // 用户已回答，解析 answers 继续执行
    String choice = answers.get("您的问题");
}
```

---

## RAG 知识库的使用

### 场景：回答基于知识库的问题

```
1. semantic_search → 先检索相关文档
2. read_chunks → 读取具体内容
3. 基于内容回答用户
```

**强制规则**：涉及知识库内容的问题，必须先 semantic_search，不能直接编造

---

## 典型工作流

### 工作流 1：修复 Bug

```
1. grep → 搜索错误关键词
2. read → 查看相关代码
3. edit → 修改代码
4. bash → 运行测试验证
5. TaskUpdate → 更新任务状态
```

### 工作流 2：实现新功能

```
1. TaskCreate → 创建任务
2. glob/grep → 了解现有代码结构
3. 编写新代码（write/edit）
4. bash → 编译测试
5. TaskUpdate → 完成
```

### 工作流 3：重构代码

```
1. TaskCreate → 创建任务
2. read → 理解旧代码
3. write → 用新实现覆盖
4. bash → 测试确保功能一致
5. git commit → 提交变更
```

### 工作流 4：需要用户确认

```
1. 分析需求，发现有多种实现方案
2. AskUserQuestion → 展示选项让用户选择
3. 根据用户选择执行对应方案
```

---

## 工具选择决策树

```
需要做什么？
├── 查看文件内容
│   └── read
├── 创建/覆盖文件
│   └── write
├── 修改部分代码
│   └── edit（需先 read）
├── 搜索文件路径
│   └── glob
├── 搜索文件内容
│   └── grep
├── 执行命令
│   └── bash
├── 管理任务
│   └── TaskCreate/List/Get/Update/Stop/Output
├── 启动子任务
│   └── Agent
├── 知识库检索
│   └── semantic_search → read_chunks
├── 执行技能
│   └── Skill
└── 向用户提问
    └── AskUserQuestion
```
