# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Spring Boot 3.5.10** AI Agent that provides AI-powered tools for code understanding, document processing, and semantic search capabilities.

## Build & Run Commands

```bash
# Build the project
./mvnw clean package -DskipTests

# Run the application
./mvnw spring-boot:run

# Run with dev profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Run tests
./mvnw test

# Run a single test
./mvnw test -Dtest=ClassName#methodName

# Compile only (fast check)
./mvnw compile
```

## Architecture

### Advisor Chain System

The chat system uses Spring AI's Advisor pattern to process requests/responses in a pipeline:

| Advisor | Order | Responsibility |
|---------|-------|----------------|
| `LifecycleToolCallAdvisor` | `Integer.MAX_VALUE - 1` | Tool call lifecycle management, stores chat history |
| `ContextCompressionAdvisor` | 4000 | Context compression for long conversations |
| `MessageFormatAdvisor` | 10000 | Message formatting, history loading, skill injection |

**Flow**: `MessageFormatAdvisor.before()` → `LifecycleToolCallAdvisor.doInitializeLoop()` → `ToolCall Loop` → `LifecycleToolCallAdvisor.doFinalizeLoop()` → `MessageFormatAdvisor.after()`

### Core Advisors

1. **LifecycleToolCallAdvisor** - Extends `ToolCallAdvisor`, handles:
   - User message persistence (saves to MySQL via `MemoryRepository`)
   - Assistant message persistence (after tool execution completes)
   - Stream/Call mode support via `doGetNextInstructionsForToolCall` / `doGetNextInstructionsForToolCallStream`

2. **MessageFormatAdvisor** - Implements `BaseAdvisor`:
   - Loads system prompt via `SystemMessageLoader`
   - Injects skill descriptions via `SkillLoader`
   - Loads history messages via `MemoryRepository`
   - Uses `ChatClientMessageAggregator` for stream mode

3. **ContextCompressionAdvisor** - Compresses long conversation history:
   - Keeps head + tail messages
   - Summarizes middle messages via LLM
   - Stores summaries in vector store

### Database Schema

- **MySQL** (`spring_ai_chat_memory`) - Chat history storage:
  ```sql
  CREATE TABLE spring_ai_chat_memory (
      id bigint PRIMARY KEY AUTO_INCREMENT,
      conversation_id varchar(36),
      content longtext,
      type varchar(10),  -- USER, ASSISTANT, SYSTEM, TOOL
      timestamp timestamp
  );
  ```

- **PostgreSQL** with `pgvector` - Vector similarity search for skills and RAG

### Tool System (`tool/`)

| Directory | Tools |
|-----------|-------|
| `file/` | ReadToolConfig, WriteToolConfig, EditToolConfig, GrepToolConfig, GlobToolConfig |
| `web/` | WebSearchToolConfig, WebFetchToolConfig |
| `rag/` | RagToolConfig |
| `bash/` | BashToolConfig with security architecture (DangerousPatternValidator, SuicideCommandDetector, CommandApprover, ProcessTreeKiller) |
| root | SkillToolConfig, AskUserToolConfig, TaskToolConfig, AgentTool, ToolsManager |

#### Agent Subsystem (`tool/agent/`)

- `AgentToolConfig` - Tool configuration for agent execution
- `AgentSessionManager` - Manages agent conversation sessions
- `WorktreeManager` - Git worktree isolation for agent tasks
- `AgentTaskRegistry` - Tracks running agent tasks
- `SubAgentChatClientFactory` - Factory for creating sub-agent chat clients
- `AgentType` - Enum defining agent types (CODER, REVIEWER, etc.)

#### Bash Security Architecture

The `bash/` package implements multi-layer command safety:

- `DangerousPatternValidator` - Regex-based pattern matching for dangerous commands
- `SuicideCommandDetector` - Detects commands that could destroy the system (rm -rf, fork bombs, etc.)
- `CommandApprover` - Central approval gate for bash execution
- `ProcessTreeKiller` - Cleans up process trees on timeout/termination
- `PathApprovalService` - Manages approved execution paths

### RAG Service (`service/rag/`)

- `AdvancedRagEtlService` - ETL pipeline for document ingestion
- `RecursiveTextSplitter` - Text chunking with token limits
- `MarkdownHeaderSplitter` - Markdown-aware splitting
- `TokenCounter` - JTokkit-based token counting

### Skill System (`messageLoader/`)

- `SkillLoader` - Scans `src/main/resources/skills/` for SKILL.md files
- `SystemMessageLoader` - Loads system prompt templates
- Skills stored in `src/main/resources/skills/<skill-name>/SKILL.md`

### Memory Repository (`repository/chat/MemoryRepository.java`)

Handles chat history persistence:
- `saveUserMessage()` - Saves user message, returns auto-increment ID
- `saveAssistantMessage()` - Saves assistant message with parentId
- `findMessagesByConversationId()` - Returns `List<Message>` for prompt building

### Key Dependencies

- Spring Boot 3.5.10 + Spring AI 1.1.3 (BOM managed)
- Java 21
- pgvector (vector embeddings)
- Apache Tika + POI (document parsing)
- JTokkit (token counting)

## Configuration

- Main config: `application.yml`
- Dev overrides: `application-dev.yml` (active by default)
- AI providers configured via Spring AI properties

## Skill Development

Skills are stored in `src/main/resources/skills/<skill-name>/SKILL.md`. Each skill has YAML front matter with `name` and `description` fields.

## Context Flow (Stream Mode)

```
Request → MessageFormatAdvisor.before() → LifecycleToolCallAdvisor.doInitializeLoopStream()
       → ToolCall Loop (doGetNextInstructionsForToolCallStream)
       → ChatClientMessageAggregator.aggregateChatClientResponse()
       → LifecycleToolCallAdvisor.doFinalizeLoopStream() → Response
```

## Development Guidelines

See [docs/DEVELOPMENT_GUIDELINES.md](docs/DEVELOPMENT_GUIDELINES.md) for:
- Comment conventions (Chinese, structured Javadoc)
- Logging standards (`[ClassName]` prefix)
- Code style (Early Return, semantic naming)
- Testing standards (TDD, UUID isolation)
- Git commit format
- Common patterns (Repository, Advisor)
