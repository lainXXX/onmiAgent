# omniAgent 完整接口文档

---

## 一、ChatController (`/chat`)

### 1.1 同步对话

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /chat/user/input` |
| **功能描述** | 用户发送问题，获取 AI 同步（阻塞式）回答 |
| **Content-Type** | `application/json` |

**请求参数 (ChatRequest)**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `question` | String | ✅ | 用户问题内容 |
| `sessionId` | String | ✅ | 会话唯一标识（UUID） |
| `workspace` | String | ❌ | 工作目录路径 |
| `bypassApproval` | Boolean | ❌ | 是否绕过危险命令审批（默认 false） |

**请求示例 1：基础对话**
```json
{
  "question": "分析这个项目的结构",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": false
}
```

**请求示例 2：绕过审批执行危险命令**
```json
{
  "question": "帮我清理 /tmp 目录下的测试文件",
  "sessionId": "660e8400-e29b-41d4-a716-446655440001",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": true
}
```

**请求示例 3：新会话对话**
```json
{
  "question": "你好，请介绍一下你自己",
  "sessionId": "770e8400-e29b-41d4-a716-446655440002",
  "workspace": null,
  "bypassApproval": false
}
```

**响应：** 直接返回文本字符串

```json
"这是 omniAgent 项目，一个 Spring Boot 3.5.10 的 AI Agent 系统..."
```

---

### 1.2 流式对话（SSE）

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /chat/stream` |
| **功能描述** | 支持工具调用、思考过程的流式输出，使用 Server-Sent Events |
| **Content-Type** | `text/event-stream` |

**请求参数：** 同 1.1

**请求示例 1：代码分析任务**
```json
{
  "question": "帮我分析 ChatController.java 的代码结构",
  "sessionId": "880e8400-e29b-41d4-a716-446655440003",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": false
}
```

**请求示例 2：文件搜索任务**
```json
{
  "question": "找出项目中所有使用了 @RestController 的文件",
  "sessionId": "990e8400-e29b-41d4-a716-446655440004",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": false
}
```

**请求示例 3：复杂多步骤任务**
```json
{
  "question": "创建一个测试文件，写入 'Hello World'，然后读取验证内容",
  "sessionId": "aa0e8400-e29b-41d4-a716-446655440005",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": false
}
```

**响应事件类型 (SSE Event)**

| 事件名 | 触发时机 | 数据结构 |
|--------|----------|----------|
| `message` | AI 输出普通文本 | `ChatChunk` |
| `thought` | AI 思考`） | `ChatChunk` |
| `tool` | 工具调用触发 | `ChatChunk` |
| `dangerous-command` | 危险命令需审批 | 包含 `ticketId` |
| `ask-user-question` | 向用户提问 | 包含问题列表 |
| `error` | 系统错误 | 错误信息 |

**ChatChunk 数据结构：**
```json
{
  "id": "uuid-xxx",
  "content": "实际文本内容",
  "role": "message | thought | tool | error",
  "toolName": "Bash | Read | Write | ...",
  "done": false
}
```

**SSE 响应示例（完整对话流程）：**
```
event:message
data:{"id":"msg-001","content":"我来帮你分析这个文件...","role":"message","done":false}

event:thought
data:{"id":"thought-001","content":"首先需要读取文件内容，使用 Read 工具...","role":"thought","done":false}

event:tool
data:{"id":"tool-001","content":null,"role":"tool","toolName":"Read","done":false}

event:message
data:{"id":"msg-002","content":"文件已读取完成，共 285 行代码...","role":"message","done":false}
```

**异常处理：**
- `429` → "API 请求过于频繁，请稍后重试"
- `529` → "API 服务暂不可用，请稍后重试"
- `timeout` → "API 请求超时，请检查网络"
- 其他 → 截断至 100 字符

---

## 二、ApprovalController (`/approval`)

### 2.1 提交审批决策

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /approval` |
| **功能描述** | 审批或拒绝危险命令执行 |

**请求参数 (ApprovalRequest)**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ticketId` | String | ✅ | 票根 ID |
| `approved` | Boolean | ✅ | true=批准，false=拒绝 |
| `command` | String | ❌ | 命令内容（标准审批需提供，用于防篡改校验） |

**请求示例 1：标准审批 - 批准危险命令**
```json
{
  "ticketId": "ticket-abc-123",
  "approved": true,
  "command": "rm -rf /tmp/test"
}
```

**请求示例 2：标准审批 - 拒绝命令**
```json
{
  "ticketId": "ticket-abc-123",
  "approved": false,
  "command": "rm -rf /tmp/test"
}
```

**请求示例 3：快捷审批 - 仅批准（无需命令匹配）**
```json
{
  "ticketId": "ticket-abc-123",
  "approved": true,
  "command": null
}
```

**响应：**
```json
{
  "success": true,
  "message": "已批准命令执行"
}
```

**业务逻辑：**
- **标准审批**：`command` 非空时，需命令匹配防篡改
- **快捷审批**：仅 `ticketId` + `approved=true` 批准

---

### 2.2 查询票根状态

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /approval/{ticketId}` |
| **功能描述** | 获取指定票根的详细信息 |

**路径参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ticketId` | String | ✅ | 票根 ID |

**请求示例 1：查询待审批票根**
```
GET /approval/ticket-abc-123
```

**请求示例 2：查询已批准票根**
```
GET /approval/ticket-def-456
```

**请求示例 3：查询已拒绝票根**
```
GET /approval/ticket-ghi-789
```

**响应：**
```json
{
  "success": true,
  "ticketId": "ticket-abc-123",
  "command": "rm -rf /tmp/test",
  "status": "PENDING | APPROVED | REJECTED",
  "createdAt": 1714567890000
}
```

**响应（票根不存在时）：**
```json
{
  "success": false,
  "message": "票根不存在或已过期"
}
```

---

### 2.3 获取待审批列表

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /approval/pending` |
| **功能描述** | 获取所有待审批的危险命令（用于前端轮询） |

**请求示例 1：无待审批命令**
```
GET /approval/pending
```

**响应：**
```json
[]
```

**请求示例 2：有多条待审批命令**
```
GET /approval/pending
```

**响应：**
```json
[
  {
    "ticketId": "ticket-001",
    "command": "rm -rf /tmp/test1",
    "message": "危险命令待审批"
  },
  {
    "ticketId": "ticket-002",
    "command": "format D:",
    "message": "危险命令待审批"
  }
]
```

**请求示例 3：前端轮询间隔示例**
```bash
# 每 5 秒轮询一次
curl -X GET http://localhost:8080/approval/pending
```

---

## 三、AskUserQuestionController (`/api/questions`)

### 3.1 提交答案

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /api/questions/{questionId}/answer` |
| **功能描述** | 用户回答 Agent 提出的问题 |

**路径参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `questionId` | String | ✅ | 问题 ID |

**请求参数 (AnswerRequest)**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `answers` | Map<String, String> | ❌ | 问题 ID → 答案 |
| `annotations` | Map<String, UserAnnotation> | ❌ | 标注信息 |
| `skip` | Boolean | ❌ | true=跳过此问题 |
| `skipReason` | String | ❌ | 跳过原因 |

**请求示例 1：提交单选题答案**
```json
{
  "questionId": "q-001",
  "answers": {
    "q1": "A"
  },
  "annotations": {},
  "skip": false,
  "skipReason": null
}
```

**请求示例 2：提交多选题答案**
```json
{
  "questionId": "q-002",
  "answers": {
    "q1": "选项A",
    "q2": "选项C"
  },
  "annotations": {},
  "skip": false,
  "skipReason": null
}
```

**请求示例 3：跳过问题**
```json
{
  "questionId": "q-003",
  "answers": {},
  "annotations": {},
  "skip": true,
  "skipReason": "我不确定正确答案"
}
```

**响应：** `void` (204 No Content)

---

### 3.2 长轮询问题

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /api/questions/poll` |
| **功能描述** | 阻塞 30 秒等待问题，适合前端轮询 |

**请求示例 1：首次轮询，无问题**
```
GET /api/questions/poll
```

**响应：**
```json
{
  "hasQuestion": false,
  "questionId": null,
  "questions": [],
  "metadata": null
}
```

**请求示例 2：轮询到问题**
```
GET /api/questions/poll
```

**响应：**
```json
{
  "hasQuestion": true,
  "questionId": "q-xxx-yyy",
  "questions": [
    {
      "header": "确认操作",
      "options": [
        {"label": "是"},
        {"label": "否"}
      ],
      "question": "确定要删除这些文件吗？"
    }
  ],
  "metadata": {}
}
```

**请求示例 3：前端定时轮询脚本**
```bash
# 每 3 秒轮询一次
while true; do
  response=$(curl -s http://localhost:8080/api/questions/poll)
  if echo "$response" | grep -q '"hasQuestion":true'; then
    echo "收到问题: $response"
    break
  fi
  sleep 3
done
```

---

### 3.3 SSE 订阅问题

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /api/questions/subscribe` |
| **功能描述** | 实时推送问题事件（WebSocket 的 SSE 替代方案） |

**请求示例 1：订阅指定问题**
```
GET /api/questions/subscribe?questionId=q-001
```

**请求示例 2：订阅所有问题（不指定 questionId）**
```
GET /api/questions/subscribe?questionId=all
```

**请求示例 3：JavaScript 前端订阅示例**
```javascript
const eventSource = new EventSource('http://localhost:8080/api/questions/subscribe?questionId=q-001');
eventSource.onmessage = (event) => {
  console.log('收到问题:', JSON.parse(event.data));
};
eventSource.onerror = (error) => {
  console.error('SSE 错误:', error);
};
```

**响应：** `SseEmitter` 事件流

---

### 3.4 问题状态查询

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /api/questions/status` |
| **功能描述** | 检查当前是否有待回答问题 |

**请求示例 1：无待处理问题**
```
GET /api/questions/status
```

**响应：**
```json
{
  "hasPending": false,
  "pendingCount": 0
}
```

**请求示例 2：有待处理问题**
```
GET /api/questions/status
```

**响应：**
```json
{
  "hasPending": true,
  "pendingCount": 2
}
```

**请求示例 3：前端状态检查**
```javascript
const checkStatus = async () => {
  const res = await fetch('http://localhost:8080/api/questions/status');
  const data = await res.json();
  if (data.hasPending) {
    showNotification(`有 ${data.pendingCount} 个问题待回答`);
  }
};
```

---

### 3.5 获取待回答问题（立即返回）

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /api/questions/pending` |
| **功能描述** | 获取所有待回答的问题（立即返回，不阻塞） |

**请求示例 1：无待回答问题**
```
GET /api/questions/pending
```

**响应：**
```json
{
  "hasQuestion": false,
  "questionId": null,
  "questions": [],
  "metadata": null
}
```

**请求示例 2：有待回答问题**
```
GET /api/questions/pending
```

**响应：**
```json
{
  "hasQuestion": true,
  "questionId": "q-001",
  "questions": [
    {
      "header": "文件操作",
      "options": [
        {"label": "覆盖"},
        {"label": "跳过"},
        {"label": "取消"}
      ],
      "question": "文件已存在，如何处理？"
    }
  ],
  "metadata": {}
}
```

**请求示例 3：与轮询配合使用**
```javascript
// 启动时检查是否有待处理问题
const checkPending = async () => {
  const res = await fetch('http://localhost:8080/api/questions/pending');
  const data = await res.json();
  if (data.hasQuestion) {
    displayQuestions(data.questions);
  }
};
```

---

## 四、KnowledgeBaseController (`/api/knowledge-base`)

### 4.1 统计信息

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /api/knowledge-base/stats` |
| **功能描述** | 获取知识库文件统计 |

**请求示例 1：获取全量统计**
```
GET /api/knowledge-base/stats
```

**响应：**
```json
{
  "totalFiles": 100,
  "completedFiles": 85,
  "processingFiles": 5,
  "failedFiles": 10,
  "totalChunks": 15000
}
```

**请求示例 2：前端展示统计卡片**
```javascript
const loadStats = async () => {
  const res = await fetch('http://localhost:8080/api/knowledge-base/stats');
  const stats = await res.json();
  document.getElementById('total-files').textContent = stats.totalFiles;
  document.getElementById('completed-files').textContent = stats.completedFiles;
};
```

**请求示例 3：监控失败文件数**
```bash
curl -s http://localhost:8080/api/knowledge-base/stats | jq '.failedFiles'
```

---

### 4.2 文件列表

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /api/knowledge-base/files` |
| **功能描述** | 获取文件列表（可选按知识库筛选） |

**查询参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `kbId` | Long | ❌ | 知识库 ID |

**请求示例 1：获取全部文件**
```
GET /api/knowledge-base/files
```

**响应：**
```json
[
  {
    "id": 1,
    "fileName": "Spring Boot 指南.pdf",
    "fileSize": 2048576,
    "status": "completed",
    "kbId": null,
    "createdAt": "2024-01-15T10:30:00"
  },
  {
    "id": 2,
    "fileName": "技术文档.docx",
    "fileSize": 512000,
    "status": "failed",
    "kbId": 1,
    "createdAt": "2024-01-16T14:20:00"
  }
]
```

**请求示例 2：筛选指定知识库的文件**
```
GET /api/knowledge-base/files?kbId=1
```

**响应：**
```json
[
  {
    "id": 2,
    "fileName": "技术文档.docx",
    "fileSize": 512000,
    "status": "failed",
    "kbId": 1,
    "createdAt": "2024-01-16T14:20:00"
  }
]
```

**请求示例 3：分页查询（假设支持 kbId 分页）**
```javascript
const loadFiles = async (kbId = null) => {
  const url = kbId
    ? `http://localhost:8080/api/knowledge-base/files?kbId=${kbId}`
    : 'http://localhost:8080/api/knowledge-base/files';
  const res = await fetch(url);
  return await res.json();
};
```

---

### 4.3 上传文件

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /api/knowledge-base/files/upload` |
| **功能描述** | 上传文件并触发 ETL 处理 |

**请求参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | MultipartFile | ✅ | 文件内容 |
| `kbId` | Long | ❌ | 知识库 ID |

**请求示例 1：上传 PDF 文件**
```bash
curl -X POST http://localhost:8080/api/knowledge-base/files/upload \
  -F "file=@Spring-Boot-Guide.pdf"
```

**请求示例 2：上传到指定知识库**
```bash
curl -X POST http://localhost:8080/api/knowledge-base/files/upload \
  -F "file=@技术文档.docx" \
  -F "kbId=1"
```

**请求示例 3：前端多文件上传**
```javascript
const uploadFile = async (file, kbId = null) => {
  const formData = new FormData();
  formData.append('file', file);
  if (kbId) formData.append('kbId', kbId);

  const res = await fetch('http://localhost:8080/api/knowledge-base/files/upload', {
    method: 'POST',
    body: formData
  });
  return await res.json();
};

// 使用
await uploadFile(fileInput.files[0], 1);
```

**响应：**
```json
{
  "success": true,
  "message": "文件处理成功",
  "report": {
    "parentChunkCount": 5,
    "childChunkCount": 20,
    "avgTokenPerParent": 450.0,
    "avgTokenPerChild": 120.0,
    "totalTokenConsumed": 5000,
    "processTimeMs": 1500
  }
}
```

---

### 4.4 删除文件

| 项目 | 说明 |
|------|------|
| **接口地址** | `DELETE /api/knowledge-base/files/{fileId}` |
| **功能描述** | 删除文件及其关联的母子块和向量 |

**路径参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `fileId` | Long | ✅ | 文件 ID |

**请求示例 1：删除单个文件**
```bash
curl -X DELETE http://localhost:8080/api/knowledge-base/files/1
```

**响应：**
```json
{
  "success": true,
  "deletedParentChunks": 5,
  "deletedFiles": 1
}
```

**请求示例 2：删除不存在的文件**
```bash
curl -X DELETE http://localhost:8080/api/knowledge-base/files/999
```

**响应：**
```json
{
  "success": false,
  "error": "文件不存在"
}
```

**请求示例 3：前端删除确认**
```javascript
const deleteFile = async (fileId) => {
  if (!confirm('确定要删除这个文件吗？')) return;

  const res = await fetch(`http://localhost:8080/api/knowledge-base/files/${fileId}`, {
    method: 'DELETE'
  });
  const result = await res.json();
  if (result.success) {
    alert('文件已删除');
    refreshFileList();
  }
};
```

---

### 4.5 重试失败文件

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /api/knowledge-base/files/{fileId}/retry` |
| **功能描述** | 重试处理失败的文件 |

**路径参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `fileId` | Long | ✅ | 文件 ID |

**查询参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `kbId` | Long | ❌ | 知识库 ID |

**请求示例 1：重试指定文件**
```bash
curl -X POST "http://localhost:8080/api/knowledge-base/files/2/retry"
```

**请求示例 2：重试并指定知识库**
```bash
curl -X POST "http://localhost:8080/api/knowledge-base/files/2/retry?kbId=1"
```

**请求示例 3：前端重试按钮**
```javascript
const retryFile = async (fileId, kbId = null) => {
  const url = kbId
    ? `http://localhost:8080/api/knowledge-base/files/${fileId}/retry?kbId=${kbId}`
    : `http://localhost:8080/api/knowledge-base/files/${fileId}/retry`;

  const res = await fetch(url, { method: 'POST' });
  return await res.json();
};

// 点击重试按钮
retryFile(2, 1).then(r => {
  if (r.success) showToast('文件已重新加入处理队列');
});
```

**响应：**
```json
{
  "success": true,
  "message": "文件已重新加入处理队列",
  "fileId": 2
}
```

---

### 4.6 搜索知识库

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /api/knowledge-base/search` |
| **功能描述** | 语义向量搜索 |

**查询参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | String | ✅ | 搜索 query |
| `topK` | int | ❌ | 返回条数（默认 5） |
| `kbId` | Long | ❌ | 知识库 ID |

**请求示例 1：基础搜索**
```
GET /api/knowledge-base/search?query=Spring+AI配置
```

**响应：**
```json
{
  "query": "Spring AI 配置",
  "results": "Spring AI 是 Spring 生态中用于 AI 集成的框架...\n\n---\n\n配置 Spring AI 需要在 application.yml 中设置 API Key..."
}
```

**请求示例 2：指定返回条数**
```
GET /api/knowledge-base/search?query=Spring+AI配置&topK=10
```

**请求示例 3：限制知识库搜索**
```
GET /api/knowledge-base/search?query=Spring+AI配置&kbId=1&topK=5
```

**请求示例 4：前端搜索组件**
```javascript
const searchKnowledgeBase = async (query, topK = 5, kbId = null) => {
  const params = new URLSearchParams({ query, topK });
  if (kbId) params.append('kbId', kbId);

  const res = await fetch(`http://localhost:8080/api/knowledge-base/search?${params}`);
  return await res.json();
};

// 使用
searchKnowledgeBase('Spring AI配置', 10, 1).then(r => {
  displayResults(r.results);
});
```

---

## 五、RagEtlController (`/api/etl`)

### 5.1 ETL 上传（简单分块）

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /api/etl/upload` |
| **功能描述** | 简单分块 ETL（Tika 提取 + TokenTextSplitter 分块） |

**请求参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | MultipartFile | ✅ | 上传文件 |

**请求示例 1：上传 PDF 文件**
```bash
curl -X POST http://localhost:8080/api/etl/upload \
  -F "file=@document.pdf"
```

**响应：**
```
文件 [document.pdf] ETL处理成功！共生成 12 个向量分块入库。
```

**请求示例 2：上传 Word 文档**
```bash
curl -X POST http://localhost:8080/api/etl/upload \
  -F "file=@技术文档.docx"
```

**响应：**
```
文件 [技术文档.docx] ETL处理成功！共生成 8 个向量分块入库。
```

**请求示例 3：上传 TXT 文件**
```bash
curl -X POST http://localhost:8080/api/etl/upload \
  -F "file=@readme.txt"
```

**响应：**
```
文件 [readme.txt] ETL处理成功！共生成 3 个向量分块入库。
```

---

### 5.2 母子嵌套分块处理（单文件）

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /api/etl/process/file` |
| **功能描述** | 单文件母子嵌套递归分块 |

**请求参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | MultipartFile | ✅ | 上传文件 |
| `kbId` | String | ❌ | 知识库 ID |

**请求示例 1：处理 PDF 到默认知识库**
```bash
curl -X POST http://localhost:8080/api/etl/process/file \
  -F "file=@large-document.pdf"
```

**响应：**
```
=== 母子嵌套递归分块处理完成 ===

文件: large-document.pdf
生成母块数: 10
生成子块数: 45
平均母块Token数: 520.00
平均子块Token数: 150.00
总Token消耗: 12000
处理耗时: 3200ms
```

**请求示例 2：处理文件到指定知识库**
```bash
curl -X POST http://localhost:8080/api/etl/process/file \
  -F "file=@技术文档.docx" \
  -F "kbId=kb-001"
```

**响应：**
```
=== 母子嵌套递归分块处理完成 ===

文件: 技术文档.docx
知识库: kb-001
生成母块数: 5
生成子块数: 20
平均母块Token数: 450.00
平均子块Token数: 120.00
总Token消耗: 5000
处理耗时: 1500ms
```

**请求示例 3：前端上传并处理**
```javascript
const processFile = async (file, kbId = null) => {
  const formData = new FormData();
  formData.append('file', file);
  if (kbId) formData.append('kbId', kbId);

  const res = await fetch('http://localhost:8080/api/etl/process/file', {
    method: 'POST',
    body: formData
  });
  return await res.text();
};

processFile(fileInput.files[0], 'kb-001').then(report => {
  console.log(report); // 打印处理报告
});
```

---

### 5.3 批量目录处理

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /api/etl/process` |
| **功能描述** | 批量处理目录下所有文件 |

**请求参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `path` | String | ❌ | 资源路径（默认 `classpath:elt_test/*`） |
| `kbId` | String | ❌ | 知识库 ID |

**请求示例 1：处理默认目录**
```bash
curl -X POST http://localhost:8080/api/etl/process
```

**响应：**
```
=== 母子嵌套递归分块处理完成 ===

处理文件数: 15
生成母块数: 50
生成子块数: 200
平均母块Token数: 480.00
平均子块Token数: 130.00
总Token消耗: 45000
处理耗时: 8500ms
```

**请求示例 2：处理指定路径**
```bash
curl -X POST "http://localhost:8080/api/etl/process?path=classpath:documents/*"
```

**请求示例 3：处理指定路径和知识库**
```bash
curl -X POST "http://localhost:8080/api/etl/process?path=classpath:documents/*&kbId=kb-001"
```

---

### 5.4 RAG 检索测试

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /api/etl/search` |
| **功能描述** | 验证 RAG 检索效果 |

**请求参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | String | ✅ | 搜索 query |
| `topK` | int | ❌ | 返回条数（默认 5） |

**请求示例 1：基础检索测试**
```bash
curl -X POST "http://localhost:8080/api/etl/search?query=Spring+AI"
```

**响应：**
```
[Document{text=Spring AI 配置指南..., score=0.89},
 Document{text=Spring AI 入门教程..., score=0.85},
 Document{text=Spring Boot 集成..., score=0.82}]
```

**请求示例 2：指定 topK**
```bash
curl -X POST "http://localhost:8080/api/etl/search?query=Spring+AI&topK=3"
```

**请求示例 3：测试检索相关性**
```javascript
const testSearch = async (query, topK = 5) => {
  const res = await fetch(`http://localhost:8080/api/etl/search?query=${encodeURIComponent(query)}&topK=${topK}`, {
    method: 'POST'
  });
  return await res.text();
};

// 测试不同 query 的检索效果
testSearch('什么是 RAG', 10).then(console.log);
testSearch('如何配置向量数据库', 10).then(console.log);
```

---

## 六、ChatTestController (`/chatTest`)

### 6.1 直接聊天测试

| 项目 | 说明 |
|------|------|
| **接口地址** | `POST /chatTest/chat` |
| **功能描述** | 直接测试 Anthropic API（同步模式） |

**请求参数：** 同 ChatRequest

**请求示例 1：基础对话测试**
```json
{
  "question": "用一句话介绍你自己",
  "sessionId": "test-001",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": false
}
```

**请求示例 2：测试思考模型**
```json
{
  "question": "解释为什么 sky 是蓝色的，不要少于 500 字",
  "sessionId": "test-002",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": false
}
```

**请求示例 3：绕过审批测试**
```json
{
  "question": "执行 echo hello",
  "sessionId": "test-003",
  "workspace": "D:/Develop/ai/omniAgent",
  "bypassApproval": true
}
```

---

### 6.2 流式测试

| 项目 | 说明 |
|------|------|
| **接口地址** | `GET /chatTest/stream` |
| **功能描述** | 流式测试原始 AI 响应 |

**查询参数：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✅ | 测试消息 |

**请求示例 1：流式 hello world**
```
GET /chatTest/stream?message=hello
```

**请求示例 2：测试多轮对话**
```
GET /chatTest/stream?message=继续上次的对话
```

**请求示例 3：测试中文**
```
GET /chatTest/stream?message=你好，请介绍一下Spring AI
```

---

## 七、前端 API 封装

### 7.1 chat.ts

```typescript
// 流式对话
export const streamChat = (request: ChatRequest): EventSource => {
  const es = new EventSource(`/chat/stream?${new URLSearchParams(request)}`);
  return es;
};

// 提交审批
export const submitApproval = async (request: ApprovalRequest): Promise<ApprovalResponse> => {
  const res = await fetch('/approval', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request)
  });
  return res.json();
};

// 提交问题答案
export const submitQuestionAnswer = async (questionId: string, answer: AnswerRequest): Promise<void> => {
  await fetch(`/api/questions/${questionId}/answer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(answer)
  });
};

// SSE 连接（审批事件）
export const connectApprovalEvents = (): EventSource => {
  return new EventSource('/approval-events');
};
```

### 7.2 knowledgeBase.ts

```typescript
// 获取统计
export const getKnowledgeBaseStats = async (): Promise<Stats> => {
  const res = await fetch('/api/knowledge-base/stats');
  return res.json();
};

// 获取文件列表
export const getFiles = async (kbId?: number): Promise<FileRecord[]> => {
  const url = kbId ? `/api/knowledge-base/files?kbId=${kbId}` : '/api/knowledge-base/files';
  const res = await fetch(url);
  return res.json();
};

// 上传文件
export const uploadFile = async (file: File, kbId?: number): Promise<UploadResponse> => {
  const formData = new FormData();
  formData.append('file', file);
  if (kbId) formData.append('kbId', String(kbId));
  const res = await fetch('/api/knowledge-base/files/upload', { method: 'POST', body: formData });
  return res.json();
};

// 删除文件
export const deleteFile = async (fileId: number): Promise<DeleteResponse> => {
  const res = await fetch(`/api/knowledge-base/files/${fileId}`, { method: 'DELETE' });
  return res.json();
};

// 搜索
export const searchKnowledgeBase = async (query: string, topK = 5, kbId?: number): Promise<SearchResponse> => {
  const params = new URLSearchParams({ query, topK: String(topK) });
  if (kbId) params.append('kbId', String(kbId));
  const res = await fetch(`/api/knowledge-base/search?${params}`);
  return res.json();
};
```

### 7.3 rag.ts

```typescript
// RAG 文件上传
export const uploadRagFile = async (file: File): Promise<string> => {
  const formData = new FormData();
  formData.append('file', file);
  const res = await fetch('/api/etl/upload', { method: 'POST', body: formData });
  return res.text();
};
```

---

## 八、典型业务场景

### 场景 1：完整对话流程（流式）

```bash
# 1. 发起流式对话
curl -X POST http://localhost:8080/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"question":"帮我分析项目的技术栈","sessionId":"session-001","workspace":"D:/Develop/ai/omniAgent"}'

# 2. SSE 事件流
event:thought
data:{"id":"t1","content":"用户想了解技术栈，我需要读取项目配置...","role":"thought","done":false}

event:tool
data:{"id":"t2","content":null,"role":"tool","toolName":"Glob","done":false}

event:message
data:{"id":"m1","content":"根据分析，你的项目使用以下技术栈：\n\n1. Spring Boot 3.5.10\n2. Spring AI 1.1.3\n3. Java 21","role":"message","done":false}
```

---

### 场景 2：危险命令审批完整流程

```bash
# 1. Agent 执行危险命令，收到 dangerous-command 事件
event:dangerous-command
data:{"ticketId":"ticket-001","command":"rm -rf /tmp/test","message":"危险命令待审批"}

# 2. 前端查询票根详情
curl http://localhost:8080/approval/ticket-001

# 3. 用户确认后提交审批
curl -X POST http://localhost:8080/approval \
  -H "Content-Type: application/json" \
  -d '{"ticketId":"ticket-001","approved":true,"command":"rm -rf /tmp/test"}'

# 4. 响应
{"success":true,"message":"已批准命令执行"}
```

---

### 场景 3：用户提问中断流程

```bash
# 1. Agent 执行过程中发起提问
event:ask-user-question
data:{"questionId":"q-001","questions":[{"header":"确认操作","options":[{"label":"是"},{"label":"否"}],"question":"确定要删除这些文件吗？"}]}

# 2. 用户提交答案
curl -X POST http://localhost:8080/api/questions/q-001/answer \
  -H "Content-Type: application/json" \
  -d '{"answers":{"q1":"是"},"annotations":{},"skip":false}'

# 3. Agent 继续执行
event:message
data:{"id":"m1","content":"好的，已删除这些文件。","role":"message","done":false}
```

---

### 场景 4：知识库 RAG 完整流程

```bash
# 1. 上传文件到知识库
curl -X POST http://localhost:8080/api/knowledge-base/files/upload \
  -F "file=@技术文档.pdf" \
  -F "kbId=1"

# 2. 查看处理状态
curl http://localhost:8080/api/knowledge-base/stats

# 3. 搜索知识库
curl "http://localhost:8080/api/knowledge-base/search?query=Spring+AI配置&topK=5&kbId=1"

# 4. 查看文件列表
curl "http://localhost:8080/api/knowledge-base/files?kbId=1"

# 5. 删除不需要的文件
curl -X DELETE http://localhost:8080/api/knowledge-base/files/5
```

---

## 九、专家建议

### 9.1 安全建议

1. **bypassApproval 参数**：生产环境应强制校验，建议增加权限验证机制，防止未授权绕过
2. **command 字段**：标准审批必须匹配命令，防止 CSRF 重放攻击
3. **ticketId**：建议使用防预测 UUID（UUID.randomUUID() 即可）
4. **文件上传**：建议增加文件类型白名单校验（当前仅校验空文件）

### 9.2 性能建议

1. **流式连接**：建议前端设置连接超时（30s）和自动重连机制
2. **长轮询 `/poll`**：30 秒超时，频繁轮询建议改用 SSE 订阅
3. **文件上传**：建议限制文件大小（当前未做校验，建议 100MB 以内）
4. **批量 ETL**：大文件建议使用异步处理，增加进度查询接口

### 9.3 边界条件

1. **空文件上传**：已做校验（`file.isEmpty()`），但未限制文件类型
2. **sessionId 为空**：会导致历史记录丢失，建议必填
3. **工具调用 JSON 过滤**：使用正则过滤可能存在绕过情况（如 JSON 嵌套）
4. **并发审批**：同一 ticketId 并发审批时需加锁

### 9.4 监控建议

1. 建议增加 `/approval/pending` 数量上限告警（如超过 10 条）
2. 建议监控 `/api/questions/poll` 超时率
3. 建议记录知识库 ETL 失败文件的重试次数
4. 建议增加 SSE 连接数的监控

### 9.5 未来优化建议

1. 增加 WebSocket 支持，实现真正的双向通信
2. 增加文件上传进度条
3. 增加 API 限流机制
4. 增加操作审计日志
5. 增加请求签名验证
