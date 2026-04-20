# Chat Memory 上下文记忆系统 - 技术规格

## 1. Overview

基于链表式存储（Git Commit 树）重构 OmniAgent 的上下文对话记忆系统。

**设计原则：**
- 链表结构（UUID + parent_id）= 支持 Undo / Branch
- 事件溯源 = 不仅存消息，还存系统状态
- Token 累计 = 触发上下文折叠

---

## 2. Database Design

### 2.1 chat_memory 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) PK | 消息唯一ID (UUID) |
| parent_id | VARCHAR(64) | 链表指针，指向上一条消息 (NULL=根节点) |
| session_id | VARCHAR(64) | 会话ID |
| user_id | VARCHAR(32) | 用户ID (多租户) |
| message_type | VARCHAR(32) | 消息类型: user/assistant/tool/system |
| content | TEXT | 消息内容 |
| tool_call_id | VARCHAR(64) | 关联 tool_call 和 tool_result |
| tool_name | VARCHAR(64) | 工具名称 |
| prompt_tokens | INT | 输入 token 数 |
| completion_tokens | INT | 输出 token 数 |
| total_tokens | INT | 本次总 token 数 |
| error_code | VARCHAR(32) | 错误码 |
| created_at | DATETIME(3) | 创建时间 |

**索引：**
- `idx_session_parent(session_id, parent_id)` - 链表遍历
- `idx_session_type(session_id, message_type)` - 按类型筛选
- `idx_session_created(session_id, created_at)` - 时间排序
- `idx_tool_call(tool_call_id)` - tool_call 关联

### 2.2 chat_session 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) PK | 会话ID |
| user_id | VARCHAR(32) | 用户ID |
| head_id | VARCHAR(64) | 当前链表头 (最后一条消息ID) |
| created_at | DATETIME(3) | 创建时间 |

**索引：**
- `idx_user(user_id)` - 按用户查会话列表

---

## 3. Architecture

### 3.1 组件结构

```
LifecycleToolCallAdvisor              → 保持现有逻辑 (HookRegistry + 生命周期)
       ↓
ChatMemoryAdvisor (新建)             → 只负责消息持久化
       ↓
ChatMemoryService                    → 业务逻辑
       ↓
ChatMemoryRepository                 → 数据库操作
```

### 3.2 ChatMemoryAdvisor 触发时机

| 方法 | 时机 | 操作 |
|------|------|------|
| doBeforeCall | 调用 AI 前 | 保存 user 消息 |
| doAfterCall | AI 响应后 | 保存 assistant 消息 + token |
| doBeforeStream | 流式调用前 | 保存 user 消息 |
| doAfterStream | 流式响应后 | 保存 assistant 消息 + token |

---

## 4. MessageType 枚举

```java
public enum MessageType {
    user,       // 用户消息
    assistant,  // 助手消息
    tool,       // 工具调用结果
    system      // 系统消息
}
```

---

## 5. API Design

### 5.1 ChatMemoryService

```java
public interface ChatMemoryService {
    
    // 保存用户/助手消息
    ChatMemory saveMessage(String sessionId, MessageType type, String content, 
                          String toolCallId, String toolName, Usage usage);
    
    // 递归查询完整上下文（按链表顺序）
    List<ChatMemory> getContext(String sessionId);
    
    // 获取当前链头
    ChatMemory getCurrentHead(String sessionId);
    
    // 累计 Token 数
    int sumTokens(String sessionId);
    
    // 单步 Undo（回退一条消息）
    void undo(String sessionId);
    
    // 折叠旧上下文（插入摘要节点）
    void collapse(String sessionId, String summary);
}
```

### 5.2 ChatSessionService

```java
public interface ChatSessionService {
    
    // 创建会话
    ChatSession createSession(String userId);
    
    // 获取会话
    ChatSession getSession(String sessionId);
    
    // 用户会话列表
    List<ChatSession> getUserSessions(String userId);
    
    // 更新 HEAD
    void updateHead(String sessionId, String newHeadId);
}
```

### 5.3 ContextCollapseService

```java
public interface ContextCollapseService {
    
    // 检查是否需要折叠
    boolean shouldCollapse(String sessionId, int threshold);
    
    // 执行折叠
    void collapse(String sessionId, String summary);
}
```

---

## 6. 上下文查询 SQL

```sql
WITH RECURSIVE ctx AS (
    SELECT * FROM chat_memory 
    WHERE id = (SELECT head_id FROM chat_session WHERE id = ?)
    UNION ALL
    SELECT m.* FROM chat_memory m INNER JOIN ctx ON ctx.parent_id = m.id
)
SELECT * FROM ctx ORDER BY created_at;
```

---

## 7. Undo 实现

```java
public void undo(String sessionId) {
    ChatSession session = chatSessionService.getSession(sessionId);
    ChatMemory currentHead = chatMemoryRepository.findById(session.getHeadId());
    if (currentHead.getParentId() != null) {
        chatSessionService.updateHead(sessionId, currentHead.getParentId());
    }
}
```

---

## 8. 文件结构

```
src/main/java/top/javarem/omni/
├── chat/
│   ├── entity/
│   │   ├── ChatMemory.java
│   │   ├── ChatSession.java
│   │   └── MessageType.java
│   ├── repository/
│   │   ├── ChatMemoryRepository.java
│   │   └── ChatSessionRepository.java
│   ├── service/
│   │   ├── ChatMemoryService.java
│   │   ├── ChatMemoryServiceImpl.java
│   │   ├── ChatSessionService.java
│   │   ├── ChatSessionServiceImpl.java
│   │   ├── ContextCollapseService.java
│   │   └── ContextCollapseServiceImpl.java
│   └── advisor/
│       └── ChatMemoryAdvisor.java
```

---

## 9. 移除的组件

- `ChatHistoryRepository` — 删除
- `MemoryRepository` — 删除
- `LifecycleToolCallAdvisor` 中的 `saveUserMessage()` 方法 — 删除
- `LifecycleToolCallAdvisor` 中的 `storeAssistantMessage()` 方法 — 删除
- `LifecycleToolCallAdvisor` 中的 `saveToolHistory()` 方法 — 删除

---

## 10. 配置

```yaml
chat:
  memory:
    collapse:
      token-threshold: 80000  # 超过此阈值触发折叠
```

---

## 11. 扩展能力（未来）

- [ ] 分支对话 (基于不同 parent_id)
- [ ] 支线任务隔离 (is_sidechain)
- [ ] 折叠区间展开查看
