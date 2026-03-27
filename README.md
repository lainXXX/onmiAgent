# omniAgent

> Your personal AI Agent — omni-capable, locally running, built for developers who think in systems.

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.10-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-blue.svg)](https://spring.io/projects/spring-ai)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**omniAgent** is a personal AI Agent powered by Spring Boot + Spring AI. It combines code understanding, document intelligence, semantic search, and an extensible skill system into a single intelligent assistant that runs entirely on your local machine.

The name reflects its core promise: **omni** — all-seeing, all-knowing, all-capable.

---

## 1. 项目标题与简介

### 项目名称
**omniAgent**

### 一句话简介
A local-first AI Agent that understands your code, processes your documents, searches your knowledge base, and grows with you through an extensible skill system.

### Badges

| Badge | Description |
|-------|-------------|
| `Spring Boot 3.5.10` | Core framework |
| `Spring AI 1.0.0` | AI/Agent infrastructure |
| `Java 21` | Runtime |
| `MySQL + pgvector` | Persistence + Vector Search |
| `MIT License` | Open source |

---

## 2. 核心特性

### 技术优势

| 特性 | 说明 |
|------|------|
| **Advisor Chain Pipeline** | 基于 Spring AI 的 Advisor 链式架构，支持请求/响应拦截、上下文压缩、消息格式化、对话历史注入 |
| **流式响应 (Streaming)** | 支持 Stream 模式实时流式输出，提升交互体验 |
| **智能上下文压缩** | 当对话过长时，自动对中间消息进行 LLM summarization，保留头部和尾部关键信息 |
| **多轮对话持久化** | 所有对话历史存入 MySQL，跨 session 保持上下文连贯 |
| **Sub-Agent 架构** | 支持创建和管理子 Agent 任务，实现复杂任务的分解与执行 |

### 功能亮点

| 模块 | 能力 |
|------|------|
| **代码智能** | 文件读写编辑、代码搜索 (grep/glob)、Shell 命令执行 |
| **文档处理** | PDF、Word (DOCX)、Markdown、TXT 多格式解析；支持 LaTeX 公式、表格结构提取 |
| **RAG 知识库** | 向量化存储 + pgvector 相似度检索 + Rerank 重排序 |
| **递归分块 (Recursive Chunking)** | 基于 token 计数的递归文本分割，保证语义完整 |
| **技能系统 (Skill System)** | 通过 Markdown 文件热插拔技能，运行时自动发现与注入 |
| **Web 能力** | 网页搜索 + 内容抓取，实时获取互联网信息 |
| **任务管理** | 内置任务状态追踪，支持长期任务中断与恢复 |

### 性能指标

| 指标 | 数值 |
|------|------|
| 向量嵌入维度 | 1024 (BAAI/bge-m3) |
| 上下文压缩阈值 | 75% context window |
| 单文件上传上限 | 100 MB |
| 数据库连接池 (MySQL/PG) | max=5, min-idle=2 |

---

## 3. 快速上手

### 环境依赖

| 依赖 | 版本要求 |
|------|----------|
| Java | 21+ |
| MySQL | 8.0+ |
| PostgreSQL | 15+ (with pgvector extension) |
| Maven | 3.9+ |

> **Note**: 确保 PostgreSQL 已安装 `pgvector` 扩展，MySQL 用于存储对话历史，PostgreSQL 用于向量检索。

### 安装步骤

```bash
# 1. 克隆项目
git clone https://github.com/your-user/omniAgent.git
cd omniAgent

# 2. 配置数据库
# 确保 MySQL 中存在 rem-agent 数据库
# 确保 PostgreSQL 中安装了 pgvector extension

# 3. 修改配置
# 编辑 src/main/resources/application-dev.yml 中的：
#   - spring.datasource.url / username / password (MySQL)
#   - spring.ai.vectorstore.pgvector.datasource.url / username / password (PostgreSQL)
#   - spring.ai.openai.api-key (或切换为其他 AI Provider)

# 4. 构建
./mvnw clean package -DskipTests

# 5. 运行
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

服务启动后，访问 `http://localhost:8080` 即可开始与 omniAgent 对话。

### 最小化示例

**发送一个 Chat 请求：**

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好，帮我解释一下什么是 RAG",
    "conversationId": "user-001"
  }'
```

**omniAgent 将自动：**
1. 加载对话历史（如果 conversationId 存在）
2. 注入相关 Skills
3. 执行 Tool Call（如需要）
4. 返回流式响应

---

## 4. 架构与设计

### 设计理念

omniAgent 基于 **Spring AI Advisor 链** 构建，采用**管道式请求处理**：

- **消息格式化** → **对话初始化** → **工具调用循环** → **对话终结** → **响应后处理**
- 每个环节职责单一，通过 `Order` 控制执行顺序
- 支持 Stream 和 Call 两种模式

### 架构图

```
                                    ┌─────────────────────────────────────┐
                                    │           omniAgent                  │
                                    │                                      │
  Request                          │  ┌────────────────────────────────┐  │
──────────►  ┌──────────────────┐  │  │   MessageFormatAdvisor         │  │
            │  Client Request   │  │  │   Order: 10000                  │  │
            └────────┬───────────┘  │  │   • 加载 System Prompt          │  │
                     │               │  │   • 注入 Skills                │  │
                     ▼               │  │   • 加载对话历史               │  │
            ┌──────────────────┐    │  └──────────────┬───────────────┘  │
            │ LifecycleTool    │    │                 │
            │ CallAdvisor      │    │                 ▼
            │ Order: MAX-1     │    │  ┌────────────────────────────────┐  │
            │ • 持久化用户消息  │    │  │   LifecycleToolCallAdvisor     │  │
            │ • 持久化助手消息  │    │  │   Order: Integer.MAX_VALUE-1   │  │
            │ • Stream/Call    │    │  │                                │  │
            │   模式支持        │    │  │  ┌──────────────────────────┐  │  │
            └────────┬─────────┘    │  │  │   Tool Call Loop         │  │  │
                     │               │  │  │                          │  │  │
                     ▼               │  │  │  ┌─────────┐ ┌─────────┐ │  │  │
            ┌──────────────────┐    │  │  │  │ File    │ │ Web     │ │  │  │
            │ ContextCompression│    │  │  │  │ Tools   │ │ Tools   │ │  │  │
            │ Advisor           │    │  │  │  ├─────────┤ ├─────────┤ │  │  │
            │ Order: 4000       │    │  │  │  │ RAG     │ │ Bash    │ │  │  │
            │ • 压缩过长上下文  │    │  │  │  │ Tools   │ │ Tool    │ │  │  │
            │ • Head + Tail     │    │  │  │  ├─────────┤ ├─────────┤ │  │  │
            │   保留策略        │    │  │  │  │ Skill   │ │ Task    │ │  │  │
            └──────────────────┘    │  │  │  │ Tool    │ │ Tool    │ │  │  │
                                    │  │  │  └─────────┘ └─────────┘ │  │  │
                                    │  │  └──────────────────────────┘  │  │
                                    │  └──────────────┬───────────────┘  │
                                    │                 │
                                    │                 ▼
                                    │  ┌────────────────────────────────┐  │
                                    │  │   LifecycleToolCallAdvisor     │  │
                                    │  │   doFinalizeLoop()             │  │
                                    │  └──────────────┬───────────────┘  │
                                    │                 │
                                    │                 ▼
                                    │  ┌────────────────────────────────┐  │
                                    │  │   MessageFormatAdvisor.after() │  │
                                    │  │   Order: 10000                 │  │
                                    │  └────────────────────────────────┘  │
                                    └─────────────────────────────────────┘
                                                                 │
                                                                 ▼
                                                            Response
```

### 核心组件

| 组件 | 职责 | 包路径 |
|------|------|--------|
| `LifecycleToolCallAdvisor` | 工具调用生命周期 + 消息持久化 | `advisor/` |
| `MessageFormatAdvisor` | System Prompt 加载、Skill 注入、历史加载 | `advisor/` |
| `ContextCompressionAdvisor` | 上下文压缩与摘要 | `advisor/` |
| `ToolsManager` | 工具注册与调用分发 | `tool/` |
| `SkillLoader` | 扫描并加载 `resources/skills/` 下的 SKILL.md | `loader/` |
| `SystemMessageLoader` | 加载系统提示词模板 | `loader/` |
| `MemoryRepository` | MySQL 对话历史读写 | `repository/chat/` |
| `AdvancedRagEtlService` | 文档 ETL 流水线 | `service/rag/` |
| `AgentTool` | Sub-Agent 创建与调度 | `tool/agent/` |

### 数据模型

**对话历史 (MySQL)**
```
spring_ai_chat_memory
├── id            BIGINT (PK, AUTO_INCREMENT)
├── conversation_id  VARCHAR(36)
├── content       LONGTEXT
├── type          VARCHAR(10)   -- USER | ASSISTANT | SYSTEM | TOOL
└── timestamp     TIMESTAMP
```

**向量知识库 (PostgreSQL + pgvector)**
```
parent_chunk_record
├── id              BIGINT (PK)
├── file_id         BIGINT (FK)
├── parent_id       BIGINT (nullable)
├── content         TEXT
├── metadata        JSONB
├── embedding       vector(1024)
└── created_at      TIMESTAMP
```

---

## 5. 配置说明

### 配置文件

| 文件 | 说明 | 激活方式 |
|------|------|----------|
| `application.yml` | 公共配置 | 默认加载 |
| `application-dev.yml` | 开发环境配置 | `spring.profiles.active=dev` |
| `application-test.yml` | 测试环境配置 | `spring.profiles.active=test` |

### 关键配置项

#### AI Provider

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.siliconflow.cn  # 或其他兼容 API
      embedding:
        enabled: true
        options:
          model: BAAI/bge-m3
    minimax:
      api-key: ${MINIMAX_API_KEY}
      base-url: https://api.minimaxi.com
      chat:
        options:
          model: MiniMax-M2.7
          temperature: 1.0
```

#### 数据库

```yaml
# MySQL (对话历史)
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/rem-agent
    username: root
    password: root

# PostgreSQL (向量存储)
spring:
  ai:
    vectorstore:
      pgvector:
        embedding-dimension: 1024
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        datasource:
          url: jdbc:postgresql://localhost:5432/springai
          username: postgres
          password: postgres
```

#### 上下文压缩

```yaml
spring:
  ai:
    context:
      compression:
        enabled: true
        context-window: 204800      # context window 大小
        threshold: 0.75             # 超过 75% 开始压缩
        keepEarliest: 2             # 保留最早 2 条消息
        keepRecent: 4               # 保留最近 4 条消息
```

#### RAG & Rerank

```yaml
spring:
  ai:
    rerank:
      enabled: true
      api-key: ${RERANK_API_KEY}
      base-url: https://api.siliconflow.cn
      model: BAAI/bge-reranker-v2-m3
      top-n: 3
```

---

## 6. 开发与测试

### 常用命令

```bash
# 编译（快速检查）
./mvnw compile

# 运行测试
./mvnw test

# 运行单个测试
./mvnw test -Dtest=ClassName#methodName

# 构建（跳过测试）
./mvnw clean package -DskipTests

# 仅运行测试覆盖率
./mvnw test jacoco:report
```

### 项目结构

```
src/main/java/top/javarem/skillDemo/
├── loader/                     # Skill 与 System Prompt 加载
│   ├── SkillLoader.java        # 扫描 resources/skills/ 目录
│   └── SystemMessageLoader.java
├── advisor/                    # Advisor 链核心
│   ├── LifecycleToolCallAdvisor.java
│   ├── MessageFormatAdvisor.java
│   └── ContextCompressionAdvisor.java
├── tool/                      # 工具定义
│   ├── file/                   # 文件操作工具
│   │   ├── ReadToolConfig.java
│   │   ├── WriteToolConfig.java
│   │   ├── EditToolConfig.java
│   │   ├── GrepToolConfig.java
│   │   └── GlobToolConfig.java
│   ├── web/                    # Web 工具
│   │   ├── WebSearchToolConfig.java
│   │   └── WebFetchToolConfig.java
│   ├── rag/                    # RAG 工具
│   │   └── RagToolConfig.java
│   ├── agent/                  # Sub-Agent 工具
│   │   └── AgentToolConfig.java
│   ├── ToolsManager.java       # 工具注册与分发
│   └── SkillToolConfig.java
├── service/                    # 业务逻辑
│   └── rag/
│       ├── AdvancedRagEtlService.java   # ETL 流水线
│       ├── RecursiveTextSplitter.java  # 递归分块
│       ├── MarkdownHeaderSplitter.java  # Markdown 感知分块
│       └── TokenCounter.java           # Token 计数
├── repository/                 # 数据访问层
│   ├── chat/
│   │   └── MemoryRepository.java    # 对话历史
│   └── rag/
│       ├── RagFileRepository.java    # 文件记录
│       └── RagChunkRepository.java   # Chunk 存储
└── controller/
    └── ChatController.java
```

### 代码风格

- **注释**: 仅在复杂逻辑处添加解释"为什么"的注释
- **命名**: 语义化，避免 `data`、`temp` 等模糊命名
- **结构**: Early Return，单一职责
- **日志**: `[ClassName]` 前缀，结构化输出

详细规范见 [docs/DEVELOPMENT_GUIDELINES.md](docs/DEVELOPMENT_GUIDELINES.md)。

---

## 7. 路线图

- [x] **Advisor Chain 架构** — 基于 Spring AI 的管道式 Agent
- [x] **多工具系统** — 文件、Web、RAG、Bash、Skill、Task、Agent
- [x] **持久化对话** — MySQL 存储对话历史
- [x] **上下文压缩** — LLM summarization 自动压缩长对话
- [x] **RAG ETL 流水线** — 多格式文档解析与向量化
- [x] **技能热插拔** — `resources/skills/<name>/SKILL.md` 动态加载
- [x] **Sub-Agent 机制** — 复杂任务的分解与并行执行
- [ ] **增量索引更新** — 支持知识库的增量更新与删除
- [ ] **多模态支持** — 图片、音频的解析与理解
- [ ] **Agent 记忆机制** — 更长期的知识积累与遗忘策略
- [ ] **性能优化** — 向量检索加速、连接池调优

---

## 8. 贡献指南与许可证

### 贡献指南

欢迎提交 Issue 和 Pull Request！

**提交流程：**
1. Fork 本仓库
2. 创建 Feature Branch (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'feat(scope): add amazing feature'`)
4. Push 到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

详细规范见 [docs/DEVELOPMENT_GUIDELINES.md](docs/DEVELOPMENT_GUIDELINES.md)。

### 许可证

本项目基于 **MIT License** 开源。详见 [LICENSE](LICENSE) 文件。

---

---

## 作者

**omniAgent** © 2025-2026 LainXXX

- GitHub: [https://github.com/LainXXX](https://github.com/LainXXX)
- Project: [https://github.com/LainXXX/omniAgent](https://github.com/LainXXX/omniAgent)

---

**omniAgent** — 一个 AI Agent，解决你所有的本地开发需求。
