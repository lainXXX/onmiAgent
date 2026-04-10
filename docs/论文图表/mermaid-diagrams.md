# 论文图表 - Mermaid 代码

> 每个代码块独立可用，复制到 https://mermaid.live 查看
> **提示**：所有图表字体已设置为 18px，适合复制到 Word 文档

---

## 图 3.1 系统用例图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
title 智能代码助手 Agent 系统用例图
usecase

actor "用户" as user
actor "AI Agent" as ai
actor "管理员" as admin
actor "子Agent" as subagent

(UC-01 用户登录)
(UC-02 发送消息)
(UC-03 查看历史)
(UC-04 文件读取)
(UC-05 文件编辑)
(UC-06 代码搜索)
(UC-07 执行命令)
(UC-08 知识检索)
(UC-09 启动子任务)
(UC-10 系统管理)

user --> (UC-01 用户登录)
user --> (UC-02 发送消息)
user --> (UC-03 查看历史)
user --> (UC-08 知识检索)

ai --> (UC-04 文件读取)
ai --> (UC-05 文件编辑)
ai --> (UC-06 代码搜索)
ai --> (UC-07 执行命令)
ai --> (UC-09 启动子任务)

admin --> (UC-10 系统管理)

subagent --> (UC-04 文件读取)
subagent --> (UC-06 代码搜索)

(UC-09 启动子任务) ..> subagent : 启动
```

---

## 图 4.1 系统功能框架图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
graph TD
    %% 样式定义
    classDef root fill:#f9f9f9,stroke:#333,stroke-width:2px
    classDef module fill:#e3f2fd,stroke:#1976d2,stroke-width:1px
    classDef func fill:#fff,stroke:#666,stroke-width:1px

    %% 根节点
    Root[智能代码助手 Agent 系统]:::root

    %% 一级模块
    Root --> Chat[Chat 对话模块]
    Root --> File[文件操作模块]
    Root --> Bash[Bash 命令模块]
    Root --> RAG[知识检索模块]
    Root --> Agent[Agent 管理模块]
    Root --> User[用户交互模块]

    %% Chat 对话模块子功能
    Chat --> C1[消息发送]
    Chat --> C2[流式响应]
    Chat --> C3[上下文管理]
    Chat --> C4[历史记录]

    %% 文件操作模块子功能
    File --> F1[文件读取]
    File --> F2[文件写入]
    File --> F3[文件编辑]
    File --> F4[Glob 搜索]
    File --> F5[Grep 搜索]

    %% Bash 命令模块子功能
    Bash --> B1[命令执行]
    Bash --> B2[危险命令检测]
    Bash --> B3[进程树清理]
    Bash --> B4[超时控制]

    %% 知识检索模块子功能
    RAG --> R1[文档上传]
    RAG --> R2[文档解析]
    RAG --> R3[分块处理]
    RAG --> R4[向量化存储]
    RAG --> R5[语义检索]
    RAG --> R6[Rerank 重排]

    %% Agent 管理模块子功能
    Agent --> A1[任务创建]
    Agent --> A2[任务监控]
    Agent --> A3[会话管理]
    Agent --> A4[Worktree 隔离]
    Agent --> A5[权限控制]

    %% 用户交互模块子功能
    User --> U1[提问接收]
    User --> U2[结果确认]
    User --> U3[取消操作]
    User --> U4[会话切换]

    %% 应用样式
    class Chat,File,Bash,RAG,Agent,User module
    class C1,C2,C3,C4,F1,F2,F3,F4,F5,B1,B2,B3,B4,R1,R2,R3,R4,R5,R6,A1,A2,A3,A4,A5,U1,U2,U3,U4 func
```

---

## 图 4.2 系统总体架构图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
flowchart TB
    subgraph CLIENT["表现层"]
        BROWSER[浏览器]
        API[API客户端]
    end

    subgraph GATEWAY["网关层-Spring Boot"]
        FILTER[安全Filter链]
        CTRL[Controller层]
    end

    subgraph ADVISOR["Advisor链"]
        MSG[MessageFormatAdvisor]
        CTX[ContextCompressionAdvisor]
        LIFE[LifecycleToolCallAdvisor]
    end

    subgraph ENGINE["AI引擎"]
        CHAT[ChatModel]
        ORCH[任务编排器]
    end

    subgraph TOOLS["工具层"]
        FILE[文件操作工具]
        BASH[Bash命令工具]
        RAG[RAG检索工具]
        WEB[Web工具]
        AGENT[Agent任务工具]
    end

    subgraph DATA["数据层"]
        subgraph MYSQL["MySQL"]
            HIST[(聊天历史)]
            TASK[(任务表)]
            KB[(知识库文件)]
        end
        subgraph PG["PostgreSQL-pgvector"]
            VEC[(向量索引)]
            CHUNK[(母块存储)]
        end
    end

    CLIENT --> GATEWAY
    FILTER --> CTRL
    CTRL --> ADVISOR
    MSG --> LIFE
    LIFE --> ENGINE
    ENGINE --> TOOLS
    ENGINE --> DATA
```

---

## 图 4.3 系统 E-R 图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
erDiagram
    USER ||--o{ CONVERSATION : "1对多"
    USER {
        string user_id PK
        string username
        string email
        timestamp created_at
    }

    CONVERSATION ||--o{ MESSAGE : "1对多"
    CONVERSATION {
        string conversation_id PK
        string user_id FK
        string title
        timestamp created_at
    }

    MESSAGE ||--|| MESSAGE : "父子"
    MESSAGE {
        bigint id PK
        bigint parent_id FK
        string conversation_id FK
        string role
        text content
        json metadata
        timestamp created_at
    }

    USER ||--o{ TASK : "1对多"
    TASK {
        string task_id PK
        string user_id FK
        string agent_type
        string status
        text result
        timestamp created_at
    }

    KB_FILE ||--o{ PARENT_CHUNK : "1对多"
    KB_FILE {
        bigint file_id PK
        string kb_id
        string filename
        string status
    }

    PARENT_CHUNK ||--o{ CHILD_CHUNK : "1对多"
    PARENT_CHUNK {
        string parent_id PK
        bigint file_id FK
        text content
    }

    CHILD_CHUNK ||--|| VECTOR_STORE : "1对1"
    CHILD_CHUNK {
        string chunk_id PK
        string parent_id FK
        text content
    }

    VECTOR_STORE {
        uuid id PK
        text content
        vector embedding
    }
```

---

## 图 4.4 系统核心流程图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
flowchart TD
    START(("用户请求")) --> INPUT{{"输入消息"}}
    INPUT --> INTENT{意图分析}
    INTENT -->|工具调用| TOOL["执行工具"]
    INTENT -->|对话| RESP["AI响应"]
    INTENT -->|检索| RETRIEVE["RAG检索"]

    TOOL --> TYPE{工具类型}
    TYPE -->|文件| FILE["文件操作"]
    TYPE -->|Bash| BASH["Bash执行"]
    TYPE -->|知识| RETRIEVE
    TYPE -->|子Agent| SUB["启动子Agent"]

    FILE --> RESULT["工具结果"]
    BASH --> CHECK{安全检测}
    CHECK -->|通过| RESULT
    CHECK -->|危险| REJECT["拒绝执行"]
    RETRIEVE --> RESULT
    SUB --> WORKTREE["Git隔离"]
    WORKTREE --> RESULT

    RESP --> STREAM["流式输出"]
    RETRIEVE --> SUMM["结果汇总"]
    RESULT --> SUMM
    REJECT --> LOG["记录日志"]

    SUMM --> STREAM
    STREAM --> SAVE["保存历史"]
    SAVE --> END(("用户"))

    LOG --> END_LOG["错误日志"]
```

---

## 图 5.1 Advisor 链时序图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
sequenceDiagram
    participant C as 客户端
    participant M as MsgFormatAdvisor
    participant L as LifecycleAdvisor
    participant AI as AI引擎
    participant T as 工具集
    participant DB as 数据库

    C->>M: 用户消息
    M->>DB: 加载历史
    DB-->>M: 历史消息
    M->>M: 注入技能
    M->>L: 预处理消息

    L->>DB: 保存用户消息
    L->>AI: 发送请求

    loop 工具调用
        AI->>T: 调用工具
        T->>T: 执行
        T-->>AI: 返回结果
    end

    AI-->>L: 最终响应
    L->>DB: 保存助手消息

    alt 上下文超限
        M->>M: 压缩上下文
    end

    M-->>C: 返回响应
```

---

## 图 5.2 Bash 安全机制流程图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
flowchart TD
    START{{"输入命令"}} --> PATT["危险模式检测"]
    PATT --> DANG{危险?}
    DANG -->|是| REJ1["拒绝执行"]
    DANG -->|否| SUIC["自我毁灭检测"]
    SUIC -->|检测到| REJ2["拒绝执行"]
    SUIC -->|正常| APPROV["中央审批门禁"]

    APPROV --> |白名单| WHITE{在白名单?}
    WHITE -->|否| REJ3["拒绝执行"]
    WHITE -->|是| OK["批准执行"]

    APPROV --> |黑名单| BLACK{在黑名单?}
    BLACK -->|是| REJ4["拒绝执行"]
    BLACK -->|否| OK

    OK --> EXEC["执行命令"]
    EXEC --> TIMEOUT{超时?}
    TIMEOUT -->|是| KILL["清理进程树"]
    TIMEOUT -->|否| SUCCESS["执行成功"]

    KILL --> LOGK["记录日志"]
    REJ1 --> LOG
    REJ2 --> LOG
    REJ3 --> LOG
    REJ4 --> LOG
    SUCCESS --> ENDS["返回结果"]
```

---

## 图 5.3 RAG 检索流程图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
flowchart LR
    subgraph INDEX["文档索引流程"]
        DOC[上传文档] --> PARSE[文档解析]
        PARSE --> SPLIT[递归分块]
        SPLIT --> PARENT[母块800tokens]
        SPLIT --> CHILD[子块200tokens]
        PARENT --> MYSQL[(母块存储)]
        CHILD --> EMBED[向量化-bge-m3]
        EMBED --> PG[(向量索引)]
    end

    subgraph QUERY["检索流程"]
        Q[用户查询] --> Q_EMBED[查询向量化]
        Q_EMBED --> SEARCH[(相似度搜索)]
        SEARCH --> RANK[Rerank重排]
        RANK --> TOP[Top-K母块ID]
        TOP --> FETCH[回查母块]
        FETCH --> RESULT[返回结果]
    end
```

---

## 图 5.4 Agent 子系统架构图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
flowchart TB
    subgraph ENTRY["任务入口"]
        USER[用户请求]
        REG[AgentTaskRegistry]
    end

    subgraph WORK["Worktree隔离层"]
        WM[WorktreeManager]
        CREATE[创建Worktree]
        SWITCH[切换Worktree]
        CLEAN[清理Worktree]
    end

    subgraph SESSION["会话管理层"]
        SM[AgentSessionManager]
        SNAP[会话快照]
        RES[会话恢复]
    end

    subgraph EXEC["执行层"]
        FACT[SubAgentChatClientFactory]
        EXE[Agent执行器]
        PERM[工具权限控制]
    end

    USER --> REG
    REG --> CREATE
    CREATE --> SWITCH
    SWITCH --> FACT
    FACT --> PERM
    PERM --> EXE
    EXE --> SM
    SM --> SNAP

    EXE ..> CLEAN : 任务完成
```

---

## 图 6.1 测试架构图

```
%%{init: {'theme':'base', 'themeVariables':{'fontSize':'18px'}}}%%
flowchart TB
    subgraph CLIENTS["测试客户端"]
        JM[JMeter]
        POSTMAN[Postman]
        CURL[cURL]
    end

    subgraph SERVER["应用服务器"]
        APP[Spring Boot App]
        THREAD[线程池]
    end

    subgraph DEP["依赖服务"]
        MYSQL[(MySQL)]
        PG[(PostgreSQL)]
        OPENAI[AI模型API]
    end

    JM --> APP
    POSTMAN --> APP
    CURL --> APP
    APP --> THREAD
    APP --> MYSQL
    APP --> PG
    APP --> OPENAI
```

---

## 使用说明

1. 打开 https://mermaid.live
2. 选择对应图表类型（或让系统自动检测）
3. 粘贴纯 Mermaid 代码（包含 `%%{init}` 字体配置）
4. 点击 "Actions" -> "Export" 导出 PNG/SVG

**字体大小调整**：所有图表默认字体已设置为 18px，如需更大字体，可修改代码中的 `'fontSize':'18px'` 为 `'fontSize':'24px'` 或更大值。
