# Agent Tools Reference

## 概述

Agent 提供 16 个工具，分为 6 个类别：文件操作、Web 搜索、系统命令、任务管理、子 Agent 和知识库检索。

---

## 文件操作工具

### read - 读取文件

读取文件内容，支持分页。

```
read(filePath, startLine?, maxLines?)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `filePath` | String | 是 | 文件路径（相对/绝对均可） |
| `startLine` | Integer | 否 | 起始行号，默认 1 |
| `maxLines` | Integer | 否 | 读取行数，默认 500，最大 2000 |

**适用场景：** 查看代码/配置
**禁忌：** 探索结构（应先用 glob）

---

### write - 创建/覆盖文件

创建新文件或覆盖已有文件。

```
write(filePath, content)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `filePath` | String | 是 | 文件路径（父目录不存在自动创建） |
| `content` | String | 是 | 文件完整内容 |

**约束：** 会覆盖原内容，需先 read 确认

---

### edit - 局部替换

精确替换代码块。

```
edit(filePath, oldString, newString)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `filePath` | String | 是 | 目标文件路径 |
| `oldString` | String | 是 | 原代码块（必须精确匹配，含缩进） |
| `newString` | String | 是 | 新代码块 |

**约束：** oldString 必须精确匹配；匹配多处则拒绝

---

### glob - 文件路径搜索

根据 glob 模式搜索文件路径。

```
glob(pattern, path?)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `pattern` | String | 是 | Glob 模式，如 `**/*.java`、`src/**/*Controller.java` |
| `path` | String | 否 | 搜索起点，默认当前目录 |

**适用场景：** 了解结构、定位某类文件
**禁忌：** 搜索内容（应用 grep）

---

### grep - 代码内容搜索

在文件中搜索关键词或正则表达式。

```
grep(query, path?)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `query` | String | 是 | 关键词或正则，如 `'void main'`、`'public class.*'` |
| `path` | String | 否 | 搜索路径，默认当前目录 |

**返回格式：** `路径:行号:内容`
**禁忌：** 找路径（应用 glob）

---

## Web 工具

### webSearch - 互联网搜索

搜索实时信息、新闻、技术文档或事实。

```
webSearch(query, maxResults?, context)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `query` | String | 是 | 搜索关键词，建议具体而非完整句子 |
| `maxResults` | Integer | 否 | 最大返回条数，默认 5，最大 10 |

**适用场景：** 本地知识库无法回答、需要最新信息或实时数据时

---

### web_fetch - 获取网页正文

获取网页内容并转换为 Markdown 格式。

```
web_fetch(url)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `url` | String | 是 | 网页 URL，必须 https:// 或 http:// 开头 |

**适用场景：** 深入阅读搜索结果链接

---

## 系统工具

### bash - 执行 Shell 命令

执行编译构建、运行测试、查看进程、环境探测等命令。

```
bash(command, description?, timeout?, runInBackground?, dangerouslyDisableSandbox?)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `command` | String | 是 | 完整 Shell 命令（路径有空格需加双引号，建议用绝对路径） |
| `description` | String | 否 | 命令行为描述（5-10 字），便于审核日志 |
| `timeout` | Long | 否 | 超时毫秒数，默认 120000ms (2分钟)，最大 600000ms (10分钟) |
| `runInBackground` | Boolean | 否 | 是否后台运行（适用于耗时较长的任务） |
| `dangerouslyDisableSandbox` | Boolean | 否 | 危险选项：是否禁用沙箱 |

**约束：**
- 交互式命令（vim/less）会被截断
- 超时最大 600 秒

---

## 任务管理工具

### TaskCreate - 创建任务

创建结构化任务以跟踪进度。

```
taskCreate(subject, description, activeForm?, metadata?, toolContext)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `subject` | String | 是 | 简短具体的标题，建议祈使句 |
| `description` | String | 是 | 详细说明、上下文及验收标准 |
| `activeForm` | String | 否 | UI 加载动画中显示的进行时描述 |
| `metadata` | Map | 否 | 附加元数据（键值对） |

---

### TaskList - 列出任务

```
taskList(status?, page?, pageSize?, format?, toolContext)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `status` | String | 否 | 任务状态：pending/in_progress/completed |
| `page` | Integer | 否 | 页码，默认 1 |
| `pageSize` | Integer | 否 | 每页数量，默认 20 |
| `format` | String | 否 | 返回格式：markdown/json |

---

### TaskGet - 获取任务详情

```
taskGet(taskId, toolContext)
```

---

### TaskUpdate - 更新任务

```
taskUpdate(taskId, subject?, description?, status?, activeForm?, owner?, addBlocks?, addBlockedBy?, metadata?, toolContext)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `addBlocks` | List | 否 | 当前任务完成后解锁的任务 |
| `addBlockedBy` | List | 否 | 必须先完成的任务 |

---

### TaskOutput - 获取后台任务输出

```
taskOutput(taskId, block?, timeout?, toolContext)
```

---

### TaskStop - 停止后台任务

```
taskStop(taskId, toolContext)
```

---

## Agent 工具

### Agent - 启动子 Agent

启动新的代理，自主处理复杂多步骤任务。

```
Agent(description, prompt, subagentType?, runInBackground?, resume?, isolation?, toolContext)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `description` | String | 是 | 任务名称（3-5 个词） |
| `prompt` | String | 是 | 详细指令 |
| `subagentType` | String | 否 | Agent 类型：explore/plan/general/code-reviewer/claude-code-guide |
| `runInBackground` | Boolean | 否 | 异步后台运行 |
| `resume` | String | 否 | 从指定 Agent ID 恢复 |
| `isolation` | String | 否 | 隔离模式：worktree |

---

### agentOutput - 获取 Agent 输出

```
agentOutput(taskId, block?, timeout?)
```

---

## 交互工具

### AskUserQuestion - 向用户提问

在执行期间向用户提问，收集偏好、澄清需求或确认方案。

```
AskUserQuestion(request)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `request` | AskUserQuestionRequest | 是 | 问题请求（1~4 个问题） |

**AskUserQuestionRequest 结构：**
```json
{
  "questions": [
    {
      "header": "标签（≤12字符）",
      "question": "完整问题（以问号结尾）",
      "options": [
        { "label": "选项A", "description": "描述" },
        { "label": "选项B", "description": "描述" }
      ],
      "multiSelect": false
    }
  ],
  "metadata": { "source": "remember" }
}
```

**约束：**
- `multiSelect: true` 时不支持 preview
- 推荐选项应放在第一位并标注 `(Recommended)`
- Plan 模式下只能用于澄清需求，不能询问计划审批

**响应：**
```json
{
  "answers": { "问题": "选项A" },
  "annotations": { "问题": { "preview": "...", "notes": "..." } },
  "timeout": false,
  "skipReason": null
}
```

---

## 知识库工具

### semantic_search - 语义检索

在知识库中进行向量语义检索。

```
semantic_search(kbId?, query)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `kbId` | String | 否 | 知识库 ID，不传则全局检索 |
| `query` | String | 是 | 语义搜索内容，应含核心名词、时间范围或具体事件 |

**强制规则：** 涉及知识库内容的咨询，必须首先调用此工具

---

### read_chunks - 读取文本片段

精准读取指定文件的连续或离散文本段落。

```
read_chunks(chunkReferences)
```

**chunkReferences:** `List<{fileId, chunkIndex}>`

---

### get_file_metadata - 获取文件元数据

```
getFileMetadata(kbId?, fileIds)
```

---

### list_files - 列出知识库文件

```
listFiles(kbId?, pageIndex?, pageSize?)
```

---

## 技能工具

### Skill - 执行技能

在当前对话中执行技能。

```
Skill(skillName, args?)
```

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `skillName` | String | 是 | 技能名称 |
| `args` | String | 否 | 技能参数 |

---

## 速查表

| 工具 | 用途 |
|------|------|
| `read` | 查看文件内容 |
| `write` | 创建/覆盖文件 |
| `edit` | 局部代码替换 |
| `glob` | 搜索文件路径 |
| `grep` | 搜索文件内容 |
| `webSearch` | 互联网搜索 |
| `web_fetch` | 获取网页正文 |
| `bash` | 执行 Shell 命令 |
| `TaskCreate` | 创建任务 |
| `TaskList` | 列出任务 |
| `TaskGet` | 获取任务详情 |
| `TaskUpdate` | 更新任务 |
| `TaskOutput` | 获取任务输出 |
| `TaskStop` | 停止任务 |
| `Agent` | 启动子 Agent |
| `agentOutput` | 获取 Agent 输出 |
| `Skill` | 执行技能 |
| `AskUserQuestion` | 向用户提问 |
| `semantic_search` | 语义检索 |
| `read_chunks` | 读取文本片段 |
| `get_file_metadata` | 获取文件元数据 |
| `list_files` | 列出知识库文件 |
