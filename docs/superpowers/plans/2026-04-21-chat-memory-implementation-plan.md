# Chat Memory 上下文记忆系统 - 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用链表式存储（UUID + parent_id）替换现有的 chat_memory 表，支持 Undo / Token 统计 / 折叠

**Architecture:** 新建 `ChatMemoryAdvisor` 拦截 AI 调用并持久化消息，复用 `LifecycleToolCallAdvisor` 的生命周期钩子结构。链表式存储通过 `chat_session.head_id` 记录当前链头，递归 CTE 查询上下文。

**Tech Stack:** Spring AI ChatClient Advisor 机制 / JdbcTemplate / MySQL 8.0+

---

## File Structure

```
src/main/java/top/javarem/omni/
├── chat/
│   ├── entity/
│   │   ├── ChatMemory.java              # 消息实体
│   │   ├── ChatSession.java             # 会话实体
│   │   └── MessageType.java             # 枚举
│   ├── repository/
│   │   ├── ChatMemoryRepository.java    # 消息 CRUD + 递归查询
│   │   └── ChatSessionRepository.java   # 会话 CRUD + HEAD 操作
│   ├── service/
│   │   ├── ChatMemoryService.java       # 接口
│   │   ├── ChatMemoryServiceImpl.java   # 实现
│   │   ├── ChatSessionService.java      # 接口
│   │   ├── ChatSessionServiceImpl.java  # 实现
│   │   ├── ContextCollapseService.java  # 接口
│   │   └── ContextCollapseServiceImpl.java # 实现
│   └── advisor/
│       └── ChatMemoryAdvisor.java       # 拦截器
```

---

## Task 1: 数据库表

**Files:**
- Create: `docs/db/mysql/chat-memory.sql`

- [ ] **Step 1: 创建 SQL 文件**

```sql
-- ============================================================================
-- Chat Memory 表 - 链表式消息存储（类 Git Commit 树）
-- ============================================================================
CREATE TABLE chat_memory (
    id              VARCHAR(64) NOT NULL COMMENT '消息唯一ID (UUID)',
    parent_id       VARCHAR(64) COMMENT '链表指针，指向上一条消息 (NULL=根节点)',
    session_id      VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id         VARCHAR(32) COMMENT '用户ID (多租户)',
    message_type    VARCHAR(32) NOT NULL COMMENT '消息类型: user/assistant/tool/system',
    content         TEXT COMMENT '消息内容',
    tool_call_id    VARCHAR(64) COMMENT '关联 tool_call 和 tool_result',
    tool_name       VARCHAR(64) COMMENT '工具名称',
    prompt_tokens   INT DEFAULT 0 COMMENT '输入 token 数',
    completion_tokens INT DEFAULT 0 COMMENT '输出 token 数',
    total_tokens    INT DEFAULT 0 COMMENT '本次总 token 数',
    error_code      VARCHAR(32) COMMENT '错误码',
    created_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_session_parent (session_id, parent_id) COMMENT '链表遍历',
    INDEX idx_session_type (session_id, message_type) COMMENT '按类型筛选',
    INDEX idx_session_created (session_id, created_at) COMMENT '时间排序',
    INDEX idx_tool_call (tool_call_id) COMMENT 'tool_call 关联'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='链表式消息存储，支持 Undo/Branch/Sidechain';

-- ============================================================================
-- Chat Session 表 - 会话状态 (记录当前 HEAD)
-- ============================================================================
CREATE TABLE chat_session (
    id              VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id         VARCHAR(32) COMMENT '用户ID',
    head_id         VARCHAR(64) NOT NULL COMMENT '当前链表头 (最后一条消息ID)',
    created_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_user (user_id) COMMENT '按用户查会话列表'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话状态表，记录当前 HEAD';
```

- [ ] **Step 2: Commit**

```bash
git add docs/db/mysql/chat-memory.sql
git commit -m "feat(chat): add chat_memory and chat_session tables"
```

---

## Task 2: Entity 实体类

**Files:**
- Create: `src/main/java/top/javarem/omni/chat/entity/MessageType.java`
- Create: `src/main/java/top/javarem/omni/chat/entity/ChatMemory.java`
- Create: `src/main/java/top/javarem/omni/chat/entity/ChatSession.java`

- [ ] **Step 1: 创建 MessageType.java**

```java
package top.javarem.omni.chat.entity;

/**
 * 消息类型枚举
 */
public enum MessageType {
    user,       // 用户消息
    assistant,  // 助手消息
    tool,       // 工具调用结果
    system      // 系统消息
}
```

- [ ] **Step 2: 创建 ChatMemory.java**

```java
package top.javarem.omni.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMemory {
    private String id;              // UUID
    private String parentId;        // 链表指针
    private String sessionId;       // 会话ID
    private String userId;         // 用户ID
    private MessageType messageType; // 消息类型
    private String content;        // 消息内容
    private String toolCallId;     // 关联 tool_call
    private String toolName;       // 工具名称
    private Integer promptTokens;   // 输入 token
    private Integer completionTokens; // 输出 token
    private Integer totalTokens;   // 总 token
    private String errorCode;      // 错误码
    private LocalDateTime createdAt; // 创建时间
}
```

- [ ] **Step 3: 创建 ChatSession.java**

```java
package top.javarem.omni.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    private String id;              // 会话ID
    private String userId;         // 用户ID
    private String headId;          // 当前链头ID
    private LocalDateTime createdAt; // 创建时间
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/top/javarem/omni/chat/entity/
git commit -m "feat(chat): add entity classes for chat_memory"
```

---

## Task 3: Repository 层

**Files:**
- Create: `src/main/java/top/javarem/omni/chat/repository/ChatMemoryRepository.java`
- Create: `src/main/java/top/javarem/omni/chat/repository/ChatSessionRepository.java`

- [ ] **Step 1: 创建 ChatMemoryRepository.java**

```java
package top.javarem.omni.chat.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.MessageType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ChatMemory> ROW_MAPPER = (rs, rowNum) -> ChatMemory.builder()
            .id(rs.getString("id"))
            .parentId(rs.getString("parent_id"))
            .sessionId(rs.getString("session_id"))
            .userId(rs.getString("user_id"))
            .messageType(MessageType.valueOf(rs.getString("message_type")))
            .content(rs.getString("content"))
            .toolCallId(rs.getString("tool_call_id"))
            .toolName(rs.getString("tool_name"))
            .promptTokens(rs.getObject("prompt_tokens") != null ? rs.getInt("prompt_tokens") : 0)
            .completionTokens(rs.getObject("completion_tokens") != null ? rs.getInt("completion_tokens") : 0)
            .totalTokens(rs.getObject("total_tokens") != null ? rs.getInt("total_tokens") : 0)
            .errorCode(rs.getString("error_code"))
            .createdAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    /**
     * 保存消息
     */
    public void save(ChatMemory chatMemory) {
        String sql = """
            INSERT INTO chat_memory (id, parent_id, session_id, user_id, message_type, content, 
                                    tool_call_id, tool_name, prompt_tokens, completion_tokens, total_tokens, error_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql, 
                chatMemory.getId(),
                chatMemory.getParentId(),
                chatMemory.getSessionId(),
                chatMemory.getUserId(),
                chatMemory.getMessageType().name(),
                chatMemory.getContent(),
                chatMemory.getToolCallId(),
                chatMemory.getToolName(),
                chatMemory.getPromptTokens(),
                chatMemory.getCompletionTokens(),
                chatMemory.getTotalTokens(),
                chatMemory.getErrorCode());
    }

    /**
     * 根据 ID 查询
     */
    public ChatMemory findById(String id) {
        String sql = "SELECT * FROM chat_memory WHERE id = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, id).stream().findFirst().orElse(null);
    }

    /**
     * 递归查询完整上下文（链表顺序）
     */
    public List<ChatMemory> findContextBySessionId(String sessionId, String headId) {
        String sql = """
            WITH RECURSIVE ctx AS (
                SELECT * FROM chat_memory WHERE id = ?
                UNION ALL
                SELECT m.* FROM chat_memory m INNER JOIN ctx ON ctx.parent_id = m.id
            )
            SELECT * FROM ctx ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER, headId);
    }

    /**
     * 累计 Token 数
     */
    public int sumTokensBySessionId(String sessionId) {
        String sql = """
            WITH RECURSIVE ctx AS (
                SELECT * FROM chat_memory WHERE id = (SELECT head_id FROM chat_session WHERE id = ?)
                UNION ALL
                SELECT m.* FROM chat_memory m INNER JOIN ctx ON ctx.parent_id = m.id
            )
            SELECT COALESCE(SUM(total_tokens), 0) as total FROM ctx
            """;
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, sessionId);
        return result != null ? result : 0;
    }
}
```

- [ ] **Step 2: 创建 ChatSessionRepository.java**

```java
package top.javarem.omni.chat.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import top.javarem.omni.chat.entity.ChatSession;

import java.sql.ResultSet;
import java.time.LocalDateTime;

@Repository
@Slf4j
@RequiredArgsConstructor
public class ChatSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ChatSession> ROW_MAPPER = (rs, rowNum) -> ChatSession.builder()
            .id(rs.getString("id"))
            .userId(rs.getString("user_id"))
            .headId(rs.getString("head_id"))
            .createdAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    /**
     * 创建会话
     */
    public void save(ChatSession chatSession) {
        String sql = """
            INSERT INTO chat_session (id, user_id, head_id)
            VALUES (?, ?, ?)
            """;
        jdbcTemplate.update(sql, 
                chatSession.getId(),
                chatSession.getUserId(),
                chatSession.getHeadId());
    }

    /**
     * 根据 ID 查询
     */
    public ChatSession findById(String id) {
        String sql = "SELECT * FROM chat_session WHERE id = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, id).stream().findFirst().orElse(null);
    }

    /**
     * 更新 HEAD
     */
    public void updateHead(String sessionId, String newHeadId) {
        String sql = "UPDATE chat_session SET head_id = ? WHERE id = ?";
        jdbcTemplate.update(sql, newHeadId, sessionId);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/top/javarem/omni/chat/repository/
git commit -m "feat(chat): add repository layer for chat_memory"
```

---

## Task 4: Service 层

**Files:**
- Create: `src/main/java/top/javarem/omni/chat/service/ChatMemoryService.java`
- Create: `src/main/java/top/javarem/omni/chat/service/ChatMemoryServiceImpl.java`
- Create: `src/main/java/top/javarem/omni/chat/service/ChatSessionService.java`
- Create: `src/main/java/top/javarem/omni/chat/service/ChatSessionServiceImpl.java`
- Create: `src/main/java/top/javarem/omni/chat/service/ContextCollapseService.java`
- Create: `src/main/java/top/javarem/omni/chat/service/ContextCollapseServiceImpl.java`

- [ ] **Step 1: 创建 ChatMemoryService.java (接口)**

```java
package top.javarem.omni.chat.service;

import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.MessageType;

import java.util.List;

public interface ChatMemoryService {
    
    // 保存消息
    ChatMemory saveMessage(String sessionId, MessageType type, String content, 
                           String toolCallId, String toolName, 
                           Integer promptTokens, Integer completionTokens);
    
    // 查询上下文
    List<ChatMemory> getContext(String sessionId);
    
    // 获取当前链头
    ChatMemory getCurrentHead(String sessionId);
    
    // 累计 Token
    int sumTokens(String sessionId);
    
    // Undo
    void undo(String sessionId);
}
```

- [ ] **Step 2: 创建 ChatMemoryServiceImpl.java**

```java
package top.javarem.omni.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.repository.ChatMemoryRepository;
import top.javarem.omni.chat.repository.ChatSessionRepository;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Override
    public ChatMemory saveMessage(String sessionId, MessageType type, String content,
                                  String toolCallId, String toolName,
                                  Integer promptTokens, Integer completionTokens) {
        // 获取当前链头作为 parent_id
        ChatSession session = chatSessionRepository.findById(sessionId);
        String parentId = session != null ? session.getHeadId() : null;
        
        // 计算 total_tokens
        Integer totalTokens = (promptTokens != null ? promptTokens : 0) + 
                             (completionTokens != null ? completionTokens : 0);
        
        // 构建消息
        ChatMemory chatMemory = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(parentId)
                .sessionId(sessionId)
                .messageType(type)
                .content(content)
                .toolCallId(toolCallId)
                .toolName(toolName)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .build();
        
        // 保存
        chatMemoryRepository.save(chatMemory);
        
        // 更新 session head
        if (session != null) {
            chatSessionRepository.updateHead(sessionId, chatMemory.getId());
        }
        
        log.debug("[ChatMemory] 保存消息 id={}, type={}, sessionId={}", 
                chatMemory.getId(), type, sessionId);
        
        return chatMemory;
    }

    @Override
    public List<ChatMemory> getContext(String sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return List.of();
        }
        return chatMemoryRepository.findContextBySessionId(sessionId, session.getHeadId());
    }

    @Override
    public ChatMemory getCurrentHead(String sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return null;
        }
        return chatMemoryRepository.findById(session.getHeadId());
    }

    @Override
    public int sumTokens(String sessionId) {
        return chatMemoryRepository.sumTokensBySessionId(sessionId);
    }

    @Override
    public void undo(String sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return;
        }
        
        ChatMemory currentHead = chatMemoryRepository.findById(session.getHeadId());
        if (currentHead != null && currentHead.getParentId() != null) {
            chatSessionRepository.updateHead(sessionId, currentHead.getParentId());
            log.info("[ChatMemory] Undo 完成, sessionId={}, newHeadId={}", 
                    sessionId, currentHead.getParentId());
        }
    }
}
```

- [ ] **Step 3: 创建 ChatSessionService.java (接口)**

```java
package top.javarem.omni.chat.service;

import top.javarem.omni.chat.entity.ChatSession;

import java.util.List;

public interface ChatSessionService {
    
    // 创建会话
    ChatSession createSession(String userId);
    
    // 获取会话
    ChatSession getSession(String sessionId);
    
    // 用户会话列表
    List<ChatSession> getUserSessions(String userId);
}
```

- [ ] **Step 4: 创建 ChatSessionServiceImpl.java**

```java
package top.javarem.omni.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.repository.ChatSessionRepository;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatSessionRepository chatSessionRepository;

    private static final RowMapper<ChatSession> ROW_MAPPER = (rs, rowNum) -> ChatSession.builder()
            .id(rs.getString("id"))
            .userId(rs.getString("user_id"))
            .headId(rs.getString("head_id"))
            .createdAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    @Override
    public ChatSession createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = ChatSession.builder()
                .id(sessionId)
                .userId(userId)
                .headId(null)  // 初始无链头
                .build();
        chatSessionRepository.save(session);
        log.info("[ChatSession] 创建会话 id={}, userId={}", sessionId, userId);
        return session;
    }

    @Override
    public ChatSession getSession(String sessionId) {
        return chatSessionRepository.findById(sessionId);
    }

    @Override
    public List<ChatSession> getUserSessions(String userId) {
        String sql = "SELECT * FROM chat_session WHERE user_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, userId);
    }
}
```

- [ ] **Step 5: 创建 ContextCollapseService.java (接口)**

```java
package top.javarem.omni.chat.service;

public interface ContextCollapseService {
    
    // 检查是否需要折叠
    boolean shouldCollapse(String sessionId);
    
    // 执行折叠（插入摘要节点）
    void collapse(String sessionId, String summary);
}
```

- [ ] **Step 6: 创建 ContextCollapseServiceImpl.java**

```java
package top.javarem.omni.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.repository.ChatMemoryRepository;
import top.javarem.omni.chat.repository.ChatSessionRepository;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContextCollapseServiceImpl implements ContextCollapseService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Value("${chat.memory.collapse.token-threshold:80000}")
    private int tokenThreshold;

    @Override
    public boolean shouldCollapse(String sessionId) {
        int totalTokens = chatMemoryRepository.sumTokensBySessionId(sessionId);
        return totalTokens > tokenThreshold;
    }

    @Override
    public void collapse(String sessionId, String summary) {
        ChatSession session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getHeadId() == null) {
            return;
        }

        // 找到当前链头的前一条作为 parent
        ChatMemory currentHead = chatMemoryRepository.findById(session.getHeadId());
        if (currentHead == null) {
            return;
        }

        // 创建摘要节点
        ChatMemory collapseNode = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(currentHead.getParentId())  // 指向更早的节点
                .sessionId(sessionId)
                .messageType(MessageType.system)  // 用 system 类型标记摘要
                .content(summary)
                .build();

        chatMemoryRepository.save(collapseNode);
        chatSessionRepository.updateHead(sessionId, collapseNode.getId());

        log.info("[ContextCollapse] 折叠完成, sessionId={}, collapseId={}, tokensThreshold={}", 
                sessionId, collapseNode.getId(), tokenThreshold);
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/top/javarem/omni/chat/service/
git commit -m "feat(chat): add service layer for chat_memory"
```

---

## Task 5: ChatMemoryAdvisor

**Files:**
- Create: `src/main/java/top/javarem/omni/chat/advisor/ChatMemoryAdvisor.java`

- [ ] **Step 1: 创建 ChatMemoryAdvisor.java**

```java
package top.javarem.omni.chat.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.AroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.ChatClientAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleChatClientAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.chat.metadata.ChatCompletionUsage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.chat.entity.MessageType;
import top.javarem.omni.chat.service.ChatMemoryService;
import top.javarem.omni.chat.service.ChatSessionService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chat Memory Advisor
 * 拦截 AI 调用并持久化消息到 chat_memory 表
 */
@Component
@Slf4j
public class ChatMemoryAdvisor implements ChatClientAdvisor {

    private final ChatMemoryService chatMemoryService;
    private final ChatSessionService chatSessionService;

    public ChatMemoryAdvisor(ChatMemoryService chatMemoryService,
                             ChatSessionService chatSessionService) {
        this.chatMemoryService = chatMemoryService;
        this.chatSessionService = chatSessionService;
    }

    @Override
    public int getOrder() {
        return SimpleChatClientAdvisor.super.getOrder();
    }

    // ==================== Around Advisor (支持 before/after) ====================

    @Override
    public ChatClientResponse aroundCall(AroundAdvisorChain chain, 
                                         Map<String, Object> attributes) {
        ChatClientRequest request = chain.request();
        ChatClientResponse response = chain.proceed(attributes);
        
        // 保存用户消息 (before)
        saveUserMessage(request);
        
        // 保存助手消息 (after)
        saveAssistantMessage(response, request);
        
        return response;
    }

    @Override
    public Flux<ChatClientResponse> aroundStream(AroundAdvisorChain chain,
                                                  Map<String, Object> attributes) {
        // 流式暂不处理（等 response 完整后再保存）
        return chain.proceed(attributes)
                .doOnNext(response -> {
                    // 保存用户消息
                    saveUserMessage(chain.request());
                    // 保存助手消息
                    saveAssistantMessage(response, chain.request());
                });
    }

    // ==================== 消息保存逻辑 ====================

    private void saveUserMessage(ChatClientRequest request) {
        try {
            String sessionId = extractSessionId(request);
            String userId = extractUserId(request);
            
            if (sessionId == null || userId == null) {
                return;
            }

            // 确保会话存在
            if (chatSessionService.getSession(sessionId) == null) {
                chatSessionService.createSession(userId);
            }

            // 获取用户消息内容
            List<Message> messages = request.prompt().getInstructions();
            List<Message> userMessages = messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .collect(Collectors.toList());

            for (Message msg : userMessages) {
                String content = msg.getText();
                chatMemoryService.saveMessage(sessionId, MessageType.user, content, 
                        null, null, null, null);
            }
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存用户消息失败", e);
        }
    }

    private void saveAssistantMessage(ChatClientResponse response, ChatClientRequest request) {
        try {
            if (response == null || response.chatResponse() == null) {
                return;
            }

            String sessionId = extractSessionId(request);
            String userId = extractUserId(request);
            
            if (sessionId == null || userId == null) {
                return;
            }

            // 确保会话存在
            if (chatSessionService.getSession(sessionId) == null) {
                chatSessionService.createSession(userId);
            }

            // 提取助手消息
            var results = response.chatResponse().getResults();
            if (results == null || results.isEmpty()) {
                return;
            }

            for (var result : results) {
                AssistantMessage assistantMessage = result.getOutput();
                if (assistantMessage == null) {
                    continue;
                }

                String content = assistantMessage.getText();
                if (content == null || content.isBlank()) {
                    continue;
                }

                // 提取 token 使用量
                Integer promptTokens = null;
                Integer completionTokens = null;
                
                if (response.chatResponse().getMetadata() != null) {
                    ChatCompletionUsage usage = response.chatResponse().getMetadata().getUsage();
                    if (usage != null) {
                        promptTokens = usage.getPromptTokens();
                        completionTokens = usage.getCompletionTokens();
                    }
                }

                chatMemoryService.saveMessage(sessionId, MessageType.assistant, content,
                        null, null, promptTokens, completionTokens);
            }
        } catch (Exception e) {
            log.error("[ChatMemoryAdvisor] 保存助手消息失败", e);
        }
    }

    // ==================== 工具方法 ====================

    private String extractSessionId(ChatClientRequest request) {
        if (request.context() != null && request.context().containsKey("sessionId")) {
            return request.context().get("sessionId").toString();
        }
        return null;
    }

    private String extractUserId(ChatClientRequest request) {
        if (request.context() != null && request.context().containsKey("userId")) {
            return request.context().get("userId").toString();
        }
        return null;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/chat/advisor/ChatMemoryAdvisor.java
git commit -m "feat(chat): add ChatMemoryAdvisor for message persistence"
```

---

## Task 6: 清理旧代码

**Files:**
- Modify: `src/main/java/top/javarem/omni/advisor/LifecycleToolCallAdvisor.java`
- Delete: `src/main/java/top/javarem/omni/repository/chat/ChatHistoryRepository.java`
- Delete: `src/main/java/top/javarem/omni/repository/chat/MemoryRepository.java`

- [ ] **Step 1: 从 LifecycleToolCallAdvisor 移除保存逻辑**

从 `LifecycleToolCallAdvisor.java` 中移除：
- `saveUserMessage()` 方法
- `saveToolHistory()` 方法
- `storeAssistantMessage()` 方法
- 移除 `ChatHistoryRepository` 和 `MemoryRepository` 的依赖

- [ ] **Step 2: 删除旧 Repository**

```bash
rm src/main/java/top/javarem/omni/repository/chat/ChatHistoryRepository.java
rm src/main/java/top/javarem/omni/repository/chat/MemoryRepository.java
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor(chat): remove old ChatHistoryRepository and MemoryRepository"
```

---

## Task 7: 单元测试

**Files:**
- Create: `src/test/java/top/javarem/omni/chat/service/ChatMemoryServiceTest.java`
- Create: `src/test/java/top/javarem/omni/chat/repository/ChatMemoryRepositoryTest.java`

- [ ] **Step 1: 创建 ChatMemoryRepositoryTest.java**

```java
package top.javarem.omni.chat.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.MessageType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ChatMemoryRepositoryTest {

    @Autowired
    private ChatMemoryRepository repository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Test
    void testSaveAndFind() {
        String sessionId = UUID.randomUUID().toString();
        
        // 创建会话
        sessionRepository.save(top.javarem.omni.chat.entity.ChatSession.builder()
                .id(sessionId)
                .userId("test")
                .headId(null)
                .build());

        // 保存消息1 (根节点)
        ChatMemory msg1 = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(null)
                .sessionId(sessionId)
                .userId("test")
                .messageType(MessageType.user)
                .content("Hello")
                .totalTokens(10)
                .build();
        repository.save(msg1);
        sessionRepository.updateHead(sessionId, msg1.getId());

        // 保存消息2
        ChatMemory msg2 = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(msg1.getId())
                .sessionId(sessionId)
                .userId("test")
                .messageType(MessageType.assistant)
                .content("Hi there")
                .totalTokens(20)
                .build();
        repository.save(msg2);
        sessionRepository.updateHead(sessionId, msg2.getId());

        // 验证递归查询
        List<ChatMemory> context = repository.findContextBySessionId(sessionId, msg2.getId());
        assertEquals(2, context.size());
        assertEquals("Hello", context.get(0).getContent());
        assertEquals("Hi there", context.get(1).getContent());
    }

    @Test
    void testSumTokens() {
        String sessionId = UUID.randomUUID().toString();
        
        sessionRepository.save(top.javarem.omni.chat.entity.ChatSession.builder()
                .id(sessionId)
                .userId("test")
                .headId(null)
                .build());

        ChatMemory msg1 = ChatMemory.builder()
                .id(UUID.randomUUID().toString())
                .parentId(null)
                .sessionId(sessionId)
                .userId("test")
                .messageType(MessageType.user)
                .totalTokens(100)
                .build();
        repository.save(msg1);
        sessionRepository.updateHead(sessionId, msg1.getId());

        int sum = repository.sumTokensBySessionId(sessionId);
        assertEquals(100, sum);
    }
}
```

- [ ] **Step 2: 创建 ChatMemoryServiceTest.java**

```java
package top.javarem.omni.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.ChatSession;
import top.javarem.omni.chat.entity.MessageType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ChatMemoryServiceTest {

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void testSaveMessage() {
        String userId = "test-user";
        ChatSession session = chatSessionService.createSession(userId);
        String sessionId = session.getId();

        ChatMemory msg = chatMemoryService.saveMessage(
                sessionId, MessageType.user, "Hello", null, null, 10, 5);

        assertNotNull(msg.getId());
        assertNull(msg.getParentId());  // 第一条消息无 parent
        assertEquals("Hello", msg.getContent());
        assertEquals(15, msg.getTotalTokens());

        // 验证 HEAD 更新
        ChatMemory head = chatMemoryService.getCurrentHead(sessionId);
        assertEquals(msg.getId(), head.getId());
    }

    @Test
    void testUndo() {
        String userId = "test-user";
        ChatSession session = chatSessionService.createSession(userId);
        String sessionId = session.getId();

        // 保存两条消息
        ChatMemory msg1 = chatMemoryService.saveMessage(
                sessionId, MessageType.user, "Hello", null, null, 10, 5);
        ChatMemory msg2 = chatMemoryService.saveMessage(
                sessionId, MessageType.assistant, "Hi", null, null, 20, 10);

        // Undo
        chatMemoryService.undo(sessionId);

        // 验证 HEAD 回退到 msg1
        ChatMemory head = chatMemoryService.getCurrentHead(sessionId);
        assertEquals(msg1.getId(), head.getId());
    }

    @Test
    void testGetContext() {
        String userId = "test-user";
        ChatSession session = chatSessionService.createSession(userId);
        String sessionId = session.getId();

        chatMemoryService.saveMessage(sessionId, MessageType.user, "Hello", null, null, 10, 5);
        chatMemoryService.saveMessage(sessionId, MessageType.assistant, "Hi", null, null, 20, 10);

        List<ChatMemory> context = chatMemoryService.getContext(sessionId);
        assertEquals(2, context.size());
        assertEquals("Hello", context.get(0).getContent());
        assertEquals("Hi", context.get(1).getContent());
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/top/javarem/omni/chat/
git commit -m "test(chat): add unit tests for chat_memory"
```

---

## Task 8: 配置

**Files:**
- Modify: `application.yml` (添加 collapse 配置)

- [ ] **Step 1: 添加配置**

```yaml
chat:
  memory:
    collapse:
      token-threshold: 80000
```

- [ ] **Step 2: Commit**

```bash
git add application.yml
git commit -m "feat(chat): add collapse token threshold config"
```
