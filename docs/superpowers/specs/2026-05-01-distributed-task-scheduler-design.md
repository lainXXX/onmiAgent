# OmniAgent 分布式任务调度模块技术设计文档

## 1. 文档概述

### 1.1 文档目的
本文档设计 OmniAgent 的分布式任务调度模块（Distributed Task Scheduler, DTS），用于支持跨多个 OmniAgent 实例的复杂任务协调。

### 1.2 背景分析
当前 OmniAgent 的子代理（SubAgent）通过 `ExecutorService` 在单个实例内执行，存在以下局限：
- **扩展性受限**：无法跨多个服务实例扩展
- **容错性不足**：节点故障时任务无法恢复
- **资源利用率低**：无法根据负载动态分配任务
- **缺乏全局协调**：无法实现跨实例的任务依赖和状态同步

### 1.3 设计目标
| 目标 | 描述 |
|------|------|
| 分布式执行 | 支持多 OmniAgent 实例协同执行任务 |
| 依赖链支持 | 支持 A→B→C 任务依赖链编排 |
| 持久化 | 任务状态持久化到数据库，支持故障恢复 |
| 超时取消 | 支持任务超时检测和主动取消 |
| 优先级队列 | 支持任务优先级和公平调度 |

---

## 2. 整体架构设计

### 2.1 架构概览图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              OmniAgent 集群                                  │
├─────────────────┬─────────────────┬─────────────────┬─────────────────────┤
│   Node-1       │    Node-2       │    Node-3       │    Node-N           │
│ ┌───────────┐  │ ┌───────────┐    │ ┌───────────┐    │ ┌───────────┐        │
│ │ Scheduler │  │ │ Scheduler │    │ │ Scheduler │    │ │ Scheduler │        │
│ │  Leader   │  │ │  Follower │    │ │  Follower │    │ │  Follower │        │
│ └─────┬─────┘  │ └─────┬─────┘    │ └─────┬─────┘    │ └─────┬─────┘        │
│       │        │       │           │       │           │       │             │
│ ┌─────┴─────┐  │ ┌─────┴─────┐    │ ┌─────┴─────┐    │ ┌─────┴─────┐        │
│ │ Executor  │  │ │ Executor  │    │ │ Executor  │    │ │ Executor  │        │
│ │  Pool     │  │ │  Pool     │    │ │  Pool     │    │ │  Pool     │        │
│ └───────────┘  │ └───────────┘    │ └───────────┘    │ └───────────┘        │
└────────┬───────┴────────┬─────────┴────────┬─────────┴────────┬────────────┘
         │                │                  │                  │
         └────────────────┴──────────────────┴──────────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │     分布式协调层            │
                    │  ┌───────────────────┐    │
                    │  │  Redis Cluster    │    │
                    │  │  (任务队列/锁/PubSub)│   │
                    │  └───────────────────┘    │
                    │  ┌───────────────────┐    │
                    │  │  MySQL            │    │
                    │  │  (持久化存储)      │    │
                    │  └───────────────────┘    │
                    └───────────────────────────┘
```

### 2.2 核心组件

| 组件 | 职责 | 技术选型 |
|------|------|---------|
| **DistributedScheduler** | 集群协调者，负责任务分发和状态同步 | Redis + Redisson |
| **TaskExecutor** | 本地任务执行器，封装 Agent 执行逻辑 | ThreadPoolExecutor |
| **TaskQueue** | 分布式优先级队列 | Redis Sorted Set |
| **TaskPersistence** | 任务状态持久化 | MySQL |
| **TaskRecovery** | 故障检测与任务恢复 | Redis TTL + 心跳 |
| **TaskDependencyManager** | 依赖链管理和触发 | 状态机 + 事件驱动 |

### 2.3 数据流图

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│   API      │────▶│  Scheduler  │────▶│  TaskQueue  │
│   Request  │     │  Gateway    │     │   Master    │     │   (Redis)   │
└─────────────┘     └─────────────┘     └──────┬──────┘     └──────┬──────┘
                                               │                   │
                          ┌─────────────────────┼───────────────────┘
                          │                     ▼
                    ┌─────┴──────┐      ┌─────────────┐
                    │   MySQL    │      │  Executor   │
                    │ (Persist)  │◀────▶│   Node-1    │
                    └────────────┘      └──────┬──────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
             ┌─────────────┐            ┌─────────────┐            ┌─────────────┐
             │  Task-A     │            │  Task-B     │            │  Task-C     │
             │  (Running)  │───────────▶│  (Waiting)  │───────────▶│  (Pending)  │
             │             │  depends   │             │  depends   │             │
             └─────────────┘            └─────────────┘            └─────────────┘
```

### 2.4 组件职责说明

#### 2.4.1 DistributedScheduler (调度器)
- **Leader 选举**：使用 Redis 分布式锁实现 Leader 选举
- **任务分发**：将任务分配给空闲的 Executor 节点
- **状态同步**：监听任务状态变更事件，同步到所有节点
- **负载均衡**：根据节点负载动态分配任务

#### 2.4.2 TaskExecutor (执行器)
- **本地执行**：在本地线程池中执行分配的任务
- **心跳上报**：定期向 Redis 上报心跳，表明节点存活
- **状态更新**：执行开始、完成、失败时更新任务状态
- **资源隔离**：支持 Worktree 隔离执行

#### 2.4.3 TaskQueue (任务队列)
- **优先级队列**：基于 Redis Sorted Set 实现
- **延迟队列**：支持延迟任务调度
- **广播机制**：使用 Pub/Sub 通知任务到达

#### 2.4.4 TaskPersistence (持久化层)
- **状态持久化**：所有状态变更写入 MySQL
- **历史记录**：记录任务执行日志
- **恢复机制**：启动时扫描未完成任务

---

## 3. 核心接口设计

### 3.1 API 接口定义

```java
package top.javarem.omni.scheduler.api;

/**
 * 分布式任务调度器 API
 * 提供任务提交、查询、取消等核心能力
 */
public interface DistributedTaskScheduler {

    /**
     * 提交分布式任务
     *
     * @param request 任务提交请求
     * @return 任务提交响应，包含任务ID
     */
    TaskSubmitResponse submitTask(TaskSubmitRequest request);

    /**
     * 批量提交任务（支持依赖链）
     *
     * @param requests 任务列表
     * @return 批量提交响应
     */
    BatchSubmitResponse submitTaskBatch(List<TaskSubmitRequest> requests);

    /**
     * 查询任务状态
     *
     * @param taskId 任务ID
     * @return 任务状态信息
     */
    TaskStatus queryTaskStatus(String taskId);

    /**
     * 取消任务
     *
     * @param taskId 任务ID
     * @return 取消结果
     */
    CancelResult cancelTask(String taskId);

    /**
     * 获取任务执行结果
     *
     * @param taskId 任务ID
     * @param timeout 超时时间
     * @return 任务执行结果
     */
    TaskResult getTaskResult(String taskId, long timeout);

    /**
     * 注册任务依赖
     *
     * @param taskId 任务ID
     * @param dependsOn 依赖的任务ID列表
     */
    void registerDependencies(String taskId, List<String> dependsOn);

    /**
     * 更新任务进度
     *
     * @param taskId 任务ID
     * @param progress 进度百分比 (0-100)
     * @param message 进度消息
     */
    void updateProgress(String taskId, int progress, String message);

    /**
     * 获取集群健康状态
     *
     * @return 集群状态信息
     */
    ClusterHealth getClusterHealth();
}
```

### 3.2 请求/响应模型

```java
package top.javarem.omni.scheduler.model;

// ========== 请求模型 ==========

/**
 * 任务提交请求
 */
public record TaskSubmitRequest(
    // 任务基本信息
    String taskName,                    // 任务名称
    String taskType,                    // 任务类型: AGENT, WORKFLOW, SCRIPT
    String description,                 // 任务描述
    
    // 执行配置
    String prompt,                      // Agent 执行指令
    String agentType,                  // Agent 类型: explore, plan, verification, general
    String isolation,                   // 隔离模式: worktree, none
    Map<String, Object> parameters,    // 执行参数
    
    // 调度配置
    int priority,                       // 优先级 (1-10, 10最高)
    long timeoutMs,                     // 超时时间 (毫秒)
    List<String> dependsOn,             // 依赖任务ID列表
    
    // 上下文
    String userId,                      // 用户ID
    String sessionId,                   // 会话ID
    Map<String, Object> context         // 扩展上下文
) {
    public TaskSubmitRequest {
        if (priority < 1) priority = 1;
        if (priority > 10) priority = 10;
        if (timeoutMs <= 0) timeoutMs = 180000; // 默认3分钟
        if (dependsOn == null) dependsOn = List.of();
        if (parameters == null) parameters = Map.of();
        if (context == null) context = Map.of();
    }
}

/**
 * 批量任务提交请求
 */
public record BatchSubmitRequest(
    List<TaskSubmitRequest> tasks,      // 任务列表
    String workflowName,                // 工作流名称
    ExecuteStrategy strategy            // 执行策略: PARALLEL, SEQUENTIAL, DAG
) {
    public enum ExecuteStrategy {
        PARALLEL,   // 全部并行
        SEQUENTIAL,  // 顺序执行
        DAG          // 按依赖关系DAG执行
    }
}

// ========== 响应模型 ==========

/**
 * 任务提交响应
 */
public record TaskSubmitResponse(
    String taskId,                      // 任务ID
    String status,                      // 提交状态: ACCEPTED, REJECTED
    String message,                     // 响应消息
    int queuePosition,                  // 队列位置 (如在等待中)
    long estimatedStartTime            // 预计开始时间
);

/**
 * 批量提交响应
 */
public record BatchSubmitResponse(
    List<TaskSubmitResponse> results,   // 各任务提交结果
    String workflowId,                  // 工作流ID
    String status,                     // 整体状态
    Map<String, String> taskIdMapping  // 任务名称到ID的映射
);

/**
 * 任务状态信息
 */
public record TaskStatus(
    String taskId,
    TaskState state,
    int progress,
    String progressMessage,
    String assignedNode,
    long createdAt,
    long startedAt,
    long updatedAt,
    long estimatedCompletionTime,
    String errorMessage,
    List<String> dependsOn,
    List<String> dependentTasks,
    Map<String, Object> metadata
) {
    public enum TaskState {
        PENDING,        // 等待中
        QUEUED,         // 已入队
        WAITING_DEPS,   // 等待依赖
        ASSIGNED,       // 已分配
        RUNNING,        // 执行中
        CANCELLED,      // 已取消
        TIMEOUT,        // 已超时
        COMPLETED,      // 已完成
        FAILED          // 执行失败
    }
}

/**
 * 任务执行结果
 */
public record TaskResult(
    String taskId,
    String status,                      // completed, failed, timeout, cancelled
    String output,                      // 执行输出
    String error,                       // 错误信息 (如有)
    long durationMs,                   // 执行时长
    String executorNode,                // 执行节点
    Map<String, Object> artifacts      // 产出物
);

/**
 * 集群健康状态
 */
public record ClusterHealth(
    boolean healthy,
    String leaderNode,
    List<NodeStatus> nodes,
    QueueMetrics queueMetrics,
    long timestamp
) {
    public record NodeStatus(
        String nodeId,
        String host,
        int port,
        boolean active,
        int runningTasks,
        int maxTasks,
        long lastHeartbeat
    );

    public record QueueMetrics(
        long pendingTasks,
        long runningTasks,
        long completedTasksToday,
        long failedTasksToday,
        double avgWaitTimeMs,
        double avgExecutionTimeMs
    );
}

/**
 * 取消结果
 */
public record CancelResult(
    String taskId,
    boolean success,
    String message,
    boolean wasRunning
);
```

### 3.3 内部服务接口

```java
package top.javarem.omni.scheduler.internal;

/**
 * 内部调度服务接口
 * 供 Scheduler 内部组件使用
 */
public interface SchedulerInternalService {

    /**
     * 节点注册
     */
    void registerNode(NodeInfo nodeInfo);

    /**
     * 节点心跳
     */
    void nodeHeartbeat(String nodeId);

    /**
     * 获取可用节点
     */
    Optional<NodeInfo> getAvailableNode();

    /**
     * 认领任务
     */
    Optional<TaskInfo> claimTask(String nodeId);

    /**
     * 任务状态变更
     */
    void onTaskStateChanged(TaskStateChangeEvent event);

    /**
     * 触发依赖任务
     */
    void triggerDependentTasks(String completedTaskId);
}

/**
 * 任务信息
 */
public record TaskInfo(
    String taskId,
    String taskName,
    String prompt,
    String agentType,
    String isolation,
    Map<String, Object> parameters,
    int priority,
    long timeoutMs,
    String userId,
    String sessionId
);

/**
 * 节点信息
 */
public record NodeInfo(
    String nodeId,
    String host,
    int port,
    int capacity,
    int currentLoad,
    long lastHeartbeat,
    Set<String> tags
);

/**
 * 任务状态变更事件
 */
public record TaskStateChangeEvent(
    String taskId,
    TaskStatus.TaskState oldState,
    TaskStatus.TaskState newState,
    String nodeId,
    String message,
    long timestamp
);
```

---

## 4. 数据模型设计

### 4.1 数据库表结构

```sql
-- ============================================
-- 分布式任务调度模块 - 数据库表结构
-- ============================================

-- 4.1.1 任务表 (主表)
CREATE TABLE `dts_task` (
    -- 基础信息
    `id`              VARCHAR(36) PRIMARY KEY COMMENT '任务唯一标识 (UUID)',
    `task_name`       VARCHAR(255) NOT NULL COMMENT '任务名称',
    `task_type`       VARCHAR(50) NOT NULL DEFAULT 'AGENT' COMMENT '任务类型: AGENT, WORKFLOW, SCRIPT',
    
    -- 执行配置
    `prompt`          TEXT COMMENT 'Agent 执行指令',
    `agent_type`      VARCHAR(50) COMMENT 'Agent 类型: explore, plan, verification, general',
    `isolation`       VARCHAR(50) DEFAULT 'none' COMMENT '隔离模式: worktree, none',
    `parameters`     JSON COMMENT '执行参数 JSON',
    
    -- 调度配置
    `priority`        TINYINT NOT NULL DEFAULT 5 COMMENT '优先级 1-10',
    `timeout_ms`      BIGINT NOT NULL DEFAULT 180000 COMMENT '超时时间(毫秒)',
    `retry_count`    TINYINT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retries`    TINYINT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    
    -- 状态管理
    `status`          VARCHAR(50) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    `progress`        TINYINT NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    `progress_message` VARCHAR(500) COMMENT '进度消息',
    `error_message`   TEXT COMMENT '错误信息',
    
    -- 执行信息
    `assigned_node`   VARCHAR(100) COMMENT '分配的节点ID',
    `started_at`      DATETIME(3) COMMENT '开始执行时间',
    `completed_at`    DATETIME(3) COMMENT '完成时间',
    `execution_time_ms` BIGINT COMMENT '执行时长(毫秒)',
    
    -- 上下文
    `user_id`         VARCHAR(100) NOT NULL COMMENT '用户ID',
    `session_id`      VARCHAR(100) NOT NULL COMMENT '会话ID',
    `workflow_id`     VARCHAR(36) COMMENT '所属工作流ID',
    `root_task_id`    VARCHAR(36) COMMENT '根任务ID (用于关联)',
    
    -- 元数据
    `metadata`        JSON COMMENT '扩展元数据',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `version`         INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    
    -- 索引
    INDEX `idx_user_session` (`user_id`, `session_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_priority_status` (`priority`, `status`),
    INDEX `idx_assigned_node` (`assigned_node`),
    INDEX `idx_workflow` (`workflow_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分布式任务表';

-- 4.1.2 任务依赖关系表
CREATE TABLE `dts_task_dependency` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         VARCHAR(36) NOT NULL COMMENT '任务ID',
    `depends_on`      VARCHAR(36) NOT NULL COMMENT '依赖的任务ID',
    `dependency_type` VARCHAR(20) NOT NULL DEFAULT 'BLOCKS' COMMENT '依赖类型: BLOCKS, TRIGGERS',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    
    UNIQUE KEY `uk_task_dependency` (`task_id`, `depends_on`),
    INDEX `idx_depends_on` (`depends_on`),
    
    FOREIGN KEY (`task_id`) REFERENCES `dts_task`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`depends_on`) REFERENCES `dts_task`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务依赖关系表';

-- 4.1.3 任务执行日志表
CREATE TABLE `dts_task_log` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         VARCHAR(36) NOT NULL COMMENT '任务ID',
    `log_level`       VARCHAR(20) NOT NULL COMMENT '日志级别: INFO, WARN, ERROR',
    `log_type`        VARCHAR(50) COMMENT '日志类型: STATE_CHANGE, HEARTBEAT, ERROR, PROGRESS',
    `message`         TEXT NOT NULL COMMENT '日志内容',
    `node_id`         VARCHAR(100) COMMENT '产生日志的节点ID',
    `context`         JSON COMMENT '上下文信息',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    
    INDEX `idx_task_time` (`task_id`, `created_at`),
    INDEX `idx_log_type` (`log_type`),
    
    FOREIGN KEY (`task_id`) REFERENCES `dts_task`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务执行日志表';

-- 4.1.4 工作流表
CREATE TABLE `dts_workflow` (
    `id`              VARCHAR(36) PRIMARY KEY COMMENT '工作流ID',
    `workflow_name`   VARCHAR(255) NOT NULL COMMENT '工作流名称',
    `strategy`        VARCHAR(50) NOT NULL DEFAULT 'DAG' COMMENT '执行策略: PARALLEL, SEQUENTIAL, DAG',
    `status`          VARCHAR(50) NOT NULL DEFAULT 'PENDING' COMMENT '工作流状态',
    `total_tasks`     INT NOT NULL DEFAULT 0 COMMENT '总任务数',
    `completed_tasks` INT NOT NULL DEFAULT 0 COMMENT '已完成任务数',
    `failed_tasks`    INT NOT NULL DEFAULT 0 COMMENT '失败任务数',
    `user_id`         VARCHAR(100) NOT NULL COMMENT '用户ID',
    `session_id`      VARCHAR(100) NOT NULL COMMENT '会话ID',
    `metadata`        JSON COMMENT '元数据',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    
    INDEX `idx_user_session` (`user_id`, `session_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流表';

-- 4.1.5 节点表
CREATE TABLE `dts_node` (
    `id`              VARCHAR(36) PRIMARY KEY COMMENT '节点ID',
    `host`            VARCHAR(255) NOT NULL COMMENT '主机地址',
    `port`            INT NOT NULL COMMENT '端口',
    `capacity`        INT NOT NULL DEFAULT 10 COMMENT '任务容量',
    `current_load`    INT NOT NULL DEFAULT 0 COMMENT '当前负载',
    `is_leader`       BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为Leader',
    `is_active`       BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否活跃',
    `tags`            JSON COMMENT '节点标签',
    `last_heartbeat`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后心跳时间',
    `registered_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '注册时间',
    `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    
    INDEX `idx_active` (`is_active`),
    INDEX `idx_leader` (`is_leader`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度节点表';

-- 4.1.6 任务结果表
CREATE TABLE `dts_task_result` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         VARCHAR(36) NOT NULL UNIQUE COMMENT '任务ID',
    `status`          VARCHAR(50) NOT NULL COMMENT '最终状态',
    `output`          LONGTEXT COMMENT '执行输出',
    `error`           TEXT COMMENT '错误信息',
    `artifacts`       JSON COMMENT '产出物',
    `executor_node`   VARCHAR(100) COMMENT '执行节点',
    `duration_ms`     BIGINT COMMENT '执行时长',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    
    FOREIGN KEY (`task_id`) REFERENCES `dts_task`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务结果表';
```

### 4.2 Redis 数据结构

```redis
-- ============================================
-- Redis 数据结构设计
-- ============================================

-- 任务优先级队列 (Sorted Set)
-- Key: dts:queue:priority
-- Score: priority * timestamp (高位优先级，低位时间戳保证FIFO)
-- Member: taskId
ZADD dts:queue:priority "10000001725394800001" "task-uuid-1"
ZADD dts:queue:priority "90000001725394800002" "task-uuid-2"

-- 延迟任务队列 (Sorted Set)
-- Key: dts:queue:delayed
-- Score: 执行时间戳
-- Member: taskId
ZADD dts:queue:delayed "1725394900000" "task-uuid-3"

-- 任务状态缓存 (Hash)
-- Key: dts:task:{taskId}
-- Fields: status, progress, nodeId, updatedAt
HSET dts:task:task-uuid-1 status "RUNNING" progress "45" nodeId "node-1" updatedAt "1725394800000"

-- 节点心跳 (String with TTL)
-- Key: dts:node:heartbeat:{nodeId}
-- Value: timestamp
SET dts:node:heartbeat:node-1 "1725394800000" EX 30

-- 节点锁 (String)
-- Key: dts:lock:node:{nodeId}
-- Value: nodeId
SET dts:lock:node:node-1 "node-1" NX EX 10

-- Leader 选举锁 (RedLock)
-- Key: dts:lock:leader
-- Value: nodeId
SET dts:lock:leader "node-1" NX PX 30000

-- 任务分配锁 (防止重复分配)
-- Key: dts:lock:task:{taskId}
-- Value: nodeId
SET dts:lock:task:task-uuid-1 "node-1" NX EX 60

-- 任务结果 Pub/Sub 通道
-- Channel: dts:channel:task-events
PUBLISH dts:channel:task-events '{"type":"STATE_CHANGED","taskId":"task-1","newState":"RUNNING"}'

-- 任务依赖关系缓存 (Set)
-- Key: dts:deps:waiting:{taskId}
-- Members: taskIds that are waiting for this task
SADD dts:deps:waiting:task-uuid-1 "task-uuid-2" "task-uuid-3"

-- 任务执行中标记 (Set)
-- Key: dts:running:{nodeId}
-- Members: taskIds running on this node
SADD dts:running:node-1 "task-uuid-1" "task-uuid-2"

-- 分布式计数器
-- Key: dts:counter:tasks:{date}
-- 每日任务统计
INCR dts:counter:tasks:2024-09-05
```

---

## 5. 分布式协调方案

### 5.1 技术选型对比

| 特性 | Redis | RabbitMQ | ZooKeeper | Consul |
|------|-------|----------|-----------|--------|
| **任务队列** | ✅ Sorted Set | ✅ 优先级队列 | ❌ 需自实现 | ⚠️ 有限 |
| **分布式锁** | ✅ Redisson | ❌ 需自实现 | ✅ 原生支持 | ✅ 支持 |
| **服务发现** | ⚠️ 需自实现 | ❌ | ✅ 原生支持 | ✅ 原生支持 |
| **Leader选举** | ✅ Redisson | ❌ | ✅ 原生支持 | ✅ 支持 |
| **Pub/Sub** | ✅ 支持 | ✅ 支持 | ⚠️ 临时节点 | ✅ 支持 |
| **持久化** | ⚠️ RDB/AOF | ✅ 支持 | ✅ 支持 | ✅ 支持 |
| **部署复杂度** | 低 | 中 | 高 | 低 |
| **性能** | 极高 | 高 | 中 | 高 |
| **一致性模型** | 最终一致 | 最终一致 | 强一致 | 最终一致 |

### 5.2 推荐方案：Redis + Redisson

**选型理由**：
1. **性能最优**：Redis 单节点 QPS 可达 10万+，满足高频任务调度
2. **功能完备**：Redisson 提供完整的分布式数据结构
3. **成本最低**：Redis 部署运维简单，社区活跃
4. **生态兼容**：OmniAgent 已有 Redis 使用经验

**架构设计**：

```
┌─────────────────────────────────────────────────────────────────┐
│                     OmniAgent Cluster                           │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐          │
│  │  Node-1    │    │  Node-2    │    │  Node-3    │          │
│  │ Scheduler  │    │ Scheduler  │    │ Scheduler  │          │
│  │  (Leader)  │    │ (Follower) │    │ (Follower) │          │
│  └──────┬─────┘    └──────┬─────┘    └──────┬─────┘          │
│         │                 │                 │                  │
│         └─────────────────┴─────────────────┘                  │
│                           │                                      │
│                    ┌──────┴──────┐                               │
│                    │ Redis Cluster│                              │
│                    │  (3主3从)   │                               │
│                    └─────────────┘                               │
└─────────────────────────────────────────────────────────────────┘
```

### 5.3 核心协调流程

#### 5.3.1 Leader 选举

```java
/**
 * Leader 选举实现
 * 使用 Redisson 的分布式锁实现主从选举
 */
public class LeaderElection {
    
    private final RedissonClient redisson;
    private static final String LEADER_LOCK_KEY = "dts:lock:leader";
    private static final long LEADER_TTL_MS = 30000; // 30秒租约
    
    private volatile String currentLeaderId;
    private ScheduledFuture<?> renewalTask;
    
    /**
     * 尝试成为 Leader
     * @return 是否成功成为 Leader
     */
    public boolean tryBecomeLeader(String nodeId) {
        RLock lock = redisson.getLock(LEADER_LOCK_KEY);
        
        try {
            // 尝试获取锁，设置 TTL
            boolean acquired = lock.tryLock(0, LEADER_TTL_MS, TimeUnit.MILLISECONDS);
            
            if (acquired) {
                // 写入选举信息
                RBucket<String> bucket = redisson.getBucket(LEADER_LOCK_KEY);
                bucket.set(nodeId);
                
                currentLeaderId = nodeId;
                
                // 启动续约任务
                startRenewalTask(nodeId);
                
                log.info("[LeaderElection] Node {} became leader", nodeId);
                return true;
            }
            
            // 检查当前是否已经是 Leader
            String leader = bucket.get();
            if (nodeId.equals(leader)) {
                currentLeaderId = nodeId;
                return true;
            }
            
            return false;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 续约任务：定期续约 Leader 锁
     */
    private void startRenewalTask(String nodeId) {
        renewalTask = Executors.newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(() -> {
                try {
                    RLock lock = redisson.getLock(LEADER_LOCK_KEY);
                    if (lock.isHeldByCurrentThread()) {
                        lock.expire(LEADER_TTL_MS, TimeUnit.MILLISECONDS);
                        log.debug("[LeaderElection] Leader lock renewed by {}", nodeId);
                    }
                } catch (Exception e) {
                    log.error("[LeaderElection] Failed to renew leader lock", e);
                }
            }, LEADER_TTL_MS / 2, LEADER_TTL_MS / 2, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 检查是否为 Leader
     */
    public boolean isLeader(String nodeId) {
        return nodeId.equals(currentLeaderId);
    }
    
    /**
     * 获取当前 Leader
     */
    public Optional<String> getCurrentLeader() {
        return Optional.ofNullable(currentLeaderId);
    }
    
    /**
     * 释放 Leader 身份
     */
    public void releaseLeadership(String nodeId) {
        if (nodeId.equals(currentLeaderId)) {
            RLock lock = redisson.getLock(LEADER_LOCK_KEY);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            
            if (renewalTask != null) {
                renewalTask.cancel(false);
            }
            
            currentLeaderId = null;
            log.info("[LeaderElection] Node {} released leadership", nodeId);
        }
    }
}
```

#### 5.3.2 任务分发流程

```java
/**
 * 任务分发器
 * Leader 节点负责将任务分配给空闲的 Worker 节点
 */
public class TaskDispatcher {
    
    private final RedissonClient redisson;
    private final TaskRepository taskRepository;
    private final NodeRegistry nodeRegistry;
    
    private static final String QUEUE_KEY = "dts:queue:priority";
    private static final String TASK_LOCK_PREFIX = "dts:lock:task:";
    private static final String RUNNING_SET_PREFIX = "dts:running:";
    
    /**
     * 分发下一个可用任务给指定节点
     * @param nodeId 目标节点ID
     * @return 分发的任务信息，无可用任务返回空
     */
    public Optional<TaskDispatchResult> dispatchNextTask(String nodeId) {
        // 1. 检查节点是否可接收任务
        NodeInfo node = nodeRegistry.getNode(nodeId);
        if (node == null || !node.isActive()) {
            return Optional.empty();
        }
        
        if (node.currentLoad() >= node.capacity()) {
            log.debug("[TaskDispatcher] Node {} at capacity", nodeId);
            return Optional.empty();
        }
        
        // 2. 从优先级队列获取最高优先级任务
        Set<String> candidates = redisson.getZSet(QUEUE_KEY)
            .valueRange(0, 100); // 获取前100个候选
        
        for (String taskId : candidates) {
            // 3. 尝试获取任务分配锁
            RLock taskLock = redisson.getLock(TASK_LOCK_PREFIX + taskId);
            
            try {
                // 非阻塞获取锁，waitTime=0
                boolean acquired = taskLock.tryLock(0, 60, TimeUnit.SECONDS);
                
                if (!acquired) {
                    continue; // 任务已被其他节点认领
                }
                
                // 4. 再次验证任务状态
                TaskEntity task = taskRepository.findById(taskId);
                if (task == null || !TaskStatus.TaskState.QUEUED.name().equals(task.getStatus())) {
                    taskLock.unlock();
                    continue;
                }
                
                // 5. 检查依赖是否满足
                if (!areDependenciesMet(taskId)) {
                    taskLock.unlock();
                    continue;
                }
                
                // 6. 分配任务给节点
                return assignTaskToNode(taskId, nodeId, taskLock);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * 检查任务依赖是否全部完成
     */
    private boolean areDependenciesMet(String taskId) {
        List<String> deps = taskRepository.findDependencies(taskId);
        
        for (String depId : deps) {
            TaskEntity dep = taskRepository.findById(depId);
            if (dep == null || !TaskStatus.TaskState.COMPLETED.name().equals(dep.getStatus())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 将任务分配给节点
     */
    private Optional<TaskDispatchResult> assignTaskToNode(
            String taskId, String nodeId, RLock taskLock) {
        
        try {
            // 1. 更新任务状态为 ASSIGNED
            taskRepository.updateStatus(taskId, TaskStatus.TaskState.ASSIGNED.name());
            taskRepository.updateAssignedNode(taskId, nodeId);
            
            // 2. 从队列移除
            redisson.getZSet(QUEUE_KEY).remove(taskId);
            
            // 3. 加入节点运行中集合
            redisson.getSet(RUNNING_SET_PREFIX + nodeId).add(taskId);
            
            // 4. 发布任务分配事件
            redisson.getTopic("dts:channel:task-assigned")
                .publish(new TaskAssignedEvent(taskId, nodeId));
            
            log.info("[TaskDispatcher] Task {} assigned to node {}", taskId, nodeId);
            
            // 5. 获取完整任务信息
            TaskEntity task = taskRepository.findById(taskId);
            TaskInfo taskInfo = TaskInfo.from(task);
            
            return Optional.of(new TaskDispatchResult(taskInfo, () -> {
                taskLock.unlock();
                redisson.getSet(RUNNING_SET_PREFIX + nodeId).remove(taskId);
            }));
            
        } catch (Exception e) {
            taskLock.unlock();
            log.error("[TaskDispatcher] Failed to assign task {}", taskId, e);
            return Optional.empty();
        }
    }
}

/**
 * 任务分配结果
 */
public record TaskDispatchResult(
    TaskInfo taskInfo,
    Runnable releaseAction  // 用于释放锁的回调
);
```

#### 5.3.3 任务状态同步

```java
/**
 * 任务状态变更监听器
 * 处理分布式环境下的状态同步
 */
@Component
public class TaskStateListener {
    
    private final RedissonClient redisson;
    private final TaskRepository taskRepository;
    private final TaskDependencyManager dependencyManager;
    
    private static final String TASK_CACHE_PREFIX = "dts:task:";
    private static final long CACHE_TTL_SECONDS = 3600;
    
    @PostConstruct
    public void init() {
        // 订阅任务状态变更通道
        redisson.getTopic("dts:channel:task-events")
            .addListener(TaskStateChangeEvent.class, (channel, event) -> {
                handleTaskStateChange(event);
            });
    }
    
    /**
     * 处理任务状态变更
     */
    @Transactional
    public void handleTaskStateChange(TaskStateChangeEvent event) {
        String taskId = event.taskId();
        TaskStatus.TaskState newState = event.newState();
        
        log.info("[TaskStateListener] Task {} state changed: {} -> {}", 
            taskId, event.oldState(), newState);
        
        // 1. 更新数据库持久化
        taskRepository.updateStatus(taskId, newState.name());
        if (event.message() != null) {
            taskRepository.updateErrorMessage(taskId, event.message());
        }
        
        // 2. 更新缓存
        updateTaskCache(taskId, newState, event);
        
        // 3. 记录日志
        taskRepository.insertLog(TaskLog.builder()
            .taskId(taskId)
            .logType("STATE_CHANGE")
            .logLevel("INFO")
            .message(String.format("State changed from %s to %s", 
                event.oldState(), newState))
            .nodeId(event.nodeId())
            .build());
        
        // 4. 处理状态特定的业务逻辑
        switch (newState) {
            case COMPLETED -> onTaskCompleted(taskId, event);
            case FAILED, TIMEOUT, CANCELLED -> onTaskFailed(taskId, newState, event);
            case RUNNING -> onTaskStarted(taskId, event);
            default -> { }
        }
    }
    
    /**
     * 任务完成处理
     */
    private void onTaskCompleted(String taskId, TaskStateChangeEvent event) {
        // 1. 更新完成时间
        taskRepository.updateCompletedAt(taskId, Instant.now());
        
        // 2. 触发依赖任务检查
        dependencyManager.onDependencyCompleted(taskId);
        
        // 3. 发布完成事件
        redisson.getTopic("dts:channel:task-completed")
            .publish(new TaskCompletedEvent(taskId, event.nodeId()));
    }
    
    /**
     * 任务失败处理
     */
    private void onTaskFailed(String taskId, TaskStatus.TaskState state, 
                              TaskStateChangeEvent event) {
        // 1. 标记依赖任务为失败（如果配置了级联失败）
        List<String> dependentTasks = taskRepository.findDependentTasks(taskId);
        for (String depTaskId : dependentTasks) {
            if (shouldCascadeFailure(depTaskId)) {
                publishStateChange(depTaskId, TaskStatus.TaskState.WAITING_DEPS, 
                    "Dependency failed: " + taskId, event.nodeId());
            }
        }
        
        // 2. 检查是否需要重试
        handleRetryIfNeeded(taskId);
    }
    
    /**
     * 更新任务缓存
     */
    private void updateTaskCache(String taskId, TaskStatus.TaskState state, 
                                 TaskStateChangeEvent event) {
        String cacheKey = TASK_CACHE_PREFIX + taskId;
        RMap<String, String> cache = redisson.getMap(cacheKey);
        
        cache.put("status", state.name());
        cache.put("progress", String.valueOf(event.progress()));
        cache.put("nodeId", event.nodeId() != null ? event.nodeId() : "");
        cache.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        
        // 设置 TTL
        redisson.getBucket(cacheKey).expire(CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }
    
    /**
     * 发布状态变更
     */
    private void publishStateChange(String taskId, TaskStatus.TaskState newState,
                                    String message, String nodeId) {
        TaskStateChangeEvent event = new TaskStateChangeEvent(
            taskId, null, newState, nodeId, message, System.currentTimeMillis());
        
        redisson.getTopic("dts:channel:task-events").publish(event);
    }
}
```

---

## 6. 一致性和可用性权衡

### 6.1 CAP 定理分析

在分布式任务调度场景中，我们面临 CAP 三角的权衡：

| 选项 | 一致性 | 可用性 | 分区容错 | 适用场景 |
|------|--------|--------|----------|----------|
| **强一致** | ✅ | ❌ | ✅ | 金融交易、库存管理 |
| **最终一致** | ⚠️ | ✅ | ✅ | 任务调度、消息通知 |
| **高可用** | ❌ | ✅ | ⚠️ | 日志收集、指标采集 |

**我们的选择**：最终一致 + 高可用

### 6.2 一致性保障策略

#### 6.2.1 任务状态一致性

```
┌─────────────────────────────────────────────────────────────────┐
│                    任务状态一致性保障                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 数据库作为 Source of Truth                                    │
│     └─ 所有状态变更必须先持久化到 MySQL                           │
│                                                                  │
│  2. Redis 作为缓存和通知层                                        │
│     └─ 缓存最新状态用于快速查询                                   │
│     └─ Pub/Sub 通知状态变更                                      │
│                                                                  │
│  3. 事件溯源 (Event Sourcing)                                     │
│     └─ 记录完整的状态变更历史                                     │
│     └─ 支持故障恢复时重放事件                                     │
│                                                                  │
│  4. 乐观锁保护                                                   │
│     └─ 使用 version 字段防止并发更新冲突                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 6.2.2 任务分配一致性

```java
/**
 * 任务分配一致性保障
 * 使用 Redis 分布式锁防止任务被重复分配
 */
public class TaskAllocationConsistency {
    
    private final RedissonClient redisson;
    private final TaskRepository taskRepository;
    
    private static final String ALLOCATION_LOCK_PREFIX = "dts:lock:task:";
    private static final long LOCK_TIMEOUT_SECONDS = 60;
    
    /**
     * 原子性任务分配
     * 使用 Lua 脚本保证 Check-Then-Act 的原子性
     */
    public AllocationResult allocateTaskAtomically(String taskId, String nodeId) {
        String lockKey = ALLOCATION_LOCK_PREFIX + taskId;
        RLock lock = redisson.getLock(lockKey);
        
        try {
            // 1. 获取分布式锁（带超时）
            boolean acquired = lock.tryLock(5, LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                return new AllocationResult(false, "Task is being processed by another node");
            }
            
            try {
                // 2. 在锁内检查并更新状态（原子操作）
                TaskAllocationResult result = taskRepository.allocateTask(taskId, nodeId);
                
                if (result.success()) {
                    // 3. 更新 Redis 缓存
                    updateCache(taskId, nodeId, TaskStatus.TaskState.ASSIGNED);
                    
                    log.info("[TaskAllocation] Task {} allocated to node {} atomically", 
                        taskId, nodeId);
                    return new AllocationResult(true, "Allocated successfully");
                } else {
                    return new AllocationResult(false, result.reason());
                }
                
            } finally {
                lock.unlock();
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AllocationResult(false, "Allocation interrupted");
        }
    }
    
    /**
     * 数据库原子分配操作
     */
    @Transactional
    public TaskAllocationResult allocateTask(String taskId, String nodeId) {
        // 使用乐观锁更新
        int updated = jdbcTemplate.update("""
            UPDATE dts_task 
            SET status = 'ASSIGNED', 
                assigned_node = ?, 
                updated_at = NOW(3)
            WHERE id = ? 
            AND status = 'QUEUED'
            AND version = ?
            """, 
            nodeId, taskId, getCurrentVersion(taskId));
        
        if (updated == 0) {
            // 检查当前状态
            String currentStatus = getCurrentStatus(taskId);
            return new TaskAllocationResult(false, 
                "Task not in QUEUED state: " + currentStatus);
        }
        
        return new TaskAllocationResult(true, "OK");
    }
}

record AllocationResult(boolean success, String message);
record TaskAllocationResult(boolean success, String reason);
```

### 6.3 可用性保障策略

#### 6.3.1 节点故障检测与恢复

```java
/**
 * 节点故障检测器
 * 使用心跳机制检测节点存活状态
 */
@Component
public class NodeFailureDetector {
    
    private final RedissonClient redisson;
    private final TaskRepository taskRepository;
    private final TaskDispatcher taskDispatcher;
    
    private static final String HEARTBEAT_PREFIX = "dts:node:heartbeat:";
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;
    
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor();
    
    @PostConstruct
    public void init() {
        // 启动心跳检查任务
        scheduler.scheduleAtFixedRate(
            this::checkNodeHealth,
            10, 10, TimeUnit.SECONDS
        );
        
        // 启动任务恢复扫描
        scheduler.scheduleAtFixedRate(
            this::recoverOrphanedTasks,
            60, 60, TimeUnit.SECONDS
        );
    }
    
    /**
     * 检查节点健康状态
     */
    private void checkNodeHealth() {
        Set<String> allNodes = discoverAllNodes();
        
        for (String nodeId : allNodes) {
            String heartbeatKey = HEARTBEAT_PREFIX + nodeId;
            long lastHeartbeat = getHeartbeat(nodeId);
            
            if (lastHeartbeat == 0) {
                // 节点从未心跳过，可能是新节点或故障
                log.warn("[NodeFailureDetector] Node {} never sent heartbeat", nodeId);
                continue;
            }
            
            long elapsed = System.currentTimeMillis() - lastHeartbeat;
            
            if (elapsed > HEARTBEAT_TIMEOUT_SECONDS * 1000) {
                // 节点超时，标记为不活跃
                handleNodeTimeout(nodeId);
            }
        }
    }
    
    /**
     * 处理节点超时
     */
    private void handleNodeTimeout(String nodeId) {
        log.warn("[NodeFailureDetector] Node {} heartbeat timeout, marking inactive", nodeId);
        
        // 1. 更新节点状态
        taskRepository.updateNodeActiveStatus(nodeId, false);
        
        // 2. 将该节点的任务标记为待重新分配
        List<String> orphanedTasks = taskRepository.findTasksByNode(nodeId);
        
        for (String taskId : orphanedTasks) {
            TaskStatus.TaskState currentState = taskRepository.getTaskStatus(taskId);
            
            // 只有 ASSIGNED 或 RUNNING 状态的任务需要恢复
            if (currentState == TaskStatus.TaskState.ASSIGNED || 
                currentState == TaskStatus.TaskState.RUNNING) {
                
                // 重置任务状态为 QUEUED，等待重新分配
                taskRepository.updateStatus(taskId, TaskStatus.TaskState.QUEUED.name());
                taskRepository.updateAssignedNode(taskId, null);
                
                log.info("[NodeFailureDetector] Task {} marked for redistribution", taskId);
            }
        }
    }
    
    /**
     * 恢复孤儿任务
     * 扫描长时间处于 ASSIGNED/RUNNING 状态的任务
     */
    private void recoverOrphanedTasks() {
        // 查找超过 5 分钟未更新的 ASSIGNED 任务
        List<String> orphanedTasks = taskRepository.findStaleTasks(
            Set.of(TaskStatus.TaskState.ASSIGNED.name(), 
                   TaskStatus.TaskState.RUNNING.name()),
            5 * 60 * 1000 // 5 分钟
        );
        
        for (String taskId : orphanedTasks) {
            // 检查分配节点是否还活跃
            String assignedNode = taskRepository.getAssignedNode(taskId);
            
            if (assignedNode != null && !isNodeActive(assignedNode)) {
                // 节点已不活跃，重新分配任务
                log.info("[NodeFailureDetector] Recovering orphaned task {}", taskId);
                
                taskRepository.updateStatus(taskId, TaskStatus.TaskState.QUEUED.name());
                taskRepository.updateAssignedNode(taskId, null);
                
                // 通知调度器有新任务可分配
                taskDispatcher.notifyNewTaskAvailable();
            }
        }
    }
}
```

#### 6.3.2 Leader 故障转移

```java
/**
 * Leader 故障转移处理
 */
@Component
public class LeaderFailoverHandler {
    
    private final LeaderElection leaderElection;
    private final RedissonClient redisson;
    private final TaskRepository taskRepository;
    private final NodeRegistry nodeRegistry;
    
    private volatile boolean running = true;
    
    /**
     * 启动 Leader 故障检测
     */
    @PostConstruct
    public void startLeaderHealthCheck() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            if (running) {
                checkLeaderHealth();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * 检查 Leader 健康状态
     */
    private void checkLeaderHealth() {
        Optional<String> currentLeader = leaderElection.getCurrentLeader();
        
        if (currentLeader.isEmpty()) {
            // 没有 Leader，尝试竞选
            attemptLeadershipElection();
            return;
        }
        
        String leaderId = currentLeader.get();
        
        // 检查 Leader 是否还活跃
        if (!nodeRegistry.isNodeActive(leaderId)) {
            log.warn("[LeaderFailover] Leader {} is inactive, triggering failover", leaderId);
            
            // 释放旧的 Leader 锁（如果还能获取到）
            leaderElection.releaseLeadership(leaderId);
            
            // 尝试新 Leader 选举
            attemptLeadershipElection();
        }
    }
    
    /**
     * 尝试成为新 Leader
     */
    private void attemptLeadershipElection() {
        String myNodeId = nodeRegistry.getMyNodeId();
        
        if (leaderElection.tryBecomeLeader(myNodeId)) {
            log.info("[LeaderFailover] Node {} became new leader", myNodeId);
            onBecomeLeader();
        }
    }
    
    /**
     * 成为 Leader 后的初始化
     */
    private void onBecomeLeader() {
        // 1. 重新扫描并分配待处理任务
        List<String> pendingTasks = taskRepository.findQueuedTasks();
        for (String taskId : pendingTasks) {
            // 尝试分配给合适的节点
            Optional<String> targetNode = nodeRegistry.getLeastLoadedNode();
            targetNode.ifPresent(nodeId -> {
                // 分配任务...
            });
        }
        
        // 2. 处理之前 Leader 遗留的任务
        handleIncompleteTasksFromPreviousLeader();
        
        // 3. 发布 Leader 变更事件
        redisson.getTopic("dts:channel:leader-changed")
            .publish(new LeaderChangedEvent(nodeRegistry.getMyNodeId()));
    }
}
```

### 6.4 性能优化策略

#### 6.4.1 批量操作优化

```java
/**
 * 批量任务操作优化
 * 减少网络往返次数
 */
public class BatchTaskOperations {
    
    private final RedissonClient redisson;
    private final RedissonTransactionManager transactionManager;
    
    /**
     * 批量入队优化
     * 使用 Pipeline 减少网络往返
     */
    public void batchEnqueue(List<TaskSubmitRequest> tasks) {
        RTransaction transaction = transactionManager.beginTransaction();
        
        try {
            // 1. 批量写入数据库
            batchInsertToDatabase(tasks);
            
            // 2. 批量写入 Redis 队列 (Pipeline)
            RPipeline pipeline = redisson.getPipeline();
            
            for (TaskSubmitRequest task : tasks) {
                String taskId = UUID.randomUUID().toString();
                double score = calculatePriorityScore(task.priority());
                
                // 批量添加任务到优先级队列
                pipeline.getZSet("dts:queue:priority").addAsync(
                    "dts:queue:priority", score, taskId
                );
                
                // 批量缓存任务信息
                RMapAsync<String, String> cache = 
                    redisson.getMapAsync("dts:task:" + taskId);
                cache.putAsync("status", "QUEUED");
                cache.putAsync("priority", String.valueOf(task.priority()));
                cache.putAsync("createdAt", String.valueOf(System.currentTimeMillis()));
            }
            
            // 3. 执行 Pipeline
            pipeline.execute();
            
            transaction.commit();
            
            log.info("[BatchTaskOperations] Batch enqueued {} tasks", tasks.size());
            
        } catch (Exception e) {
            transaction.rollback();
            throw new RuntimeException("Batch enqueue failed", e);
        }
    }
    
    /**
     * 计算优先级分数
     * 高位: priority (1-10)
     * 低位: timestamp (保证 FIFO)
     */
    private double calculatePriorityScore(int priority) {
        // priority * 10^13 + timestamp
        // 这样 priority=10 的任务永远在 priority=1 之前
        return priority * 10_000_000_000_000L + 
               (System.currentTimeMillis() % 10_000_000_000_000L);
    }
}
```

#### 6.4.2 连接池优化

```yaml
# application-dts.yml
spring:
  data:
    redis:
      # 连接池配置
      lettuce:
        pool:
          enabled: true
          max-active: 64      # 最大连接数
          max-idle: 32        # 最大空闲连接
          min-idle: 8         # 最小空闲连接
          max-wait: 5000ms    # 获取连接最大等待时间
        
        # 集群配置 (如使用 Redis Cluster)
        cluster:
          refresh:
            enabled: true
            period: 30s        # 定期刷新拓扑
          redirects: 3        # 最大重定向次数

# MySQL 连接池
datasource:
  hikari:
    maximum-pool-size: 20
    minimum-idle: 5
    connection-timeout: 30000
    idle-timeout: 600000
    max-lifetime: 1800000
```

---

## 7. 关键代码示例

### 7.1 核心调度器实现

```java
package top.javarem.omni.scheduler.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.javarem.omni.scheduler.api.*;
import top.javarem.omni.scheduler.internal.*;
import top.javarem.omni.scheduler.model.*;
import top.javarem.omni.scheduler.persistence.*;
import top.javarem.omni.scheduler.coordination.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 分布式任务调度器核心实现
 * 
 * 职责：
 * 1. 接收任务提交请求
 * 2. 管理任务生命周期
 * 3. 协调分布式执行
 * 4. 处理任务依赖和触发
 */
@Slf4j
@Component
public class DistributedTaskSchedulerImpl implements DistributedTaskScheduler {
    
    // ========== 依赖注入 ==========
    private final TaskRepository taskRepository;
    private final TaskQueueService taskQueueService;
    private final TaskExecutorService taskExecutorService;
    private final TaskDependencyManager dependencyManager;
    private final LeaderElection leaderElection;
    private final NodeRegistry nodeRegistry;
    private final TaskStatePublisher statePublisher;
    
    // ========== 内部状态 ==========
    private final Map<String, CompletableFuture<TaskResult>> taskFutures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(2);
    
    // ========== 构造器注入 ==========
    public DistributedTaskSchedulerImpl(
            TaskRepository taskRepository,
            TaskQueueService taskQueueService,
            TaskExecutorService taskExecutorService,
            TaskDependencyManager dependencyManager,
            LeaderElection leaderElection,
            NodeRegistry nodeRegistry,
            TaskStatePublisher statePublisher) {
        this.taskRepository = taskRepository;
        this.taskQueueService = taskQueueService;
        this.taskExecutorService = taskExecutorService;
        this.dependencyManager = dependencyManager;
        this.leaderElection = leaderElection;
        this.nodeRegistry = nodeRegistry;
        this.statePublisher = statePublisher;
        
        // 启动调度循环
        startSchedulingLoop();
        startTimeoutChecker();
    }
    
    // ========== 核心接口实现 ==========
    
    @Override
    public TaskSubmitResponse submitTask(TaskSubmitRequest request) {
        String taskId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        
        log.info("[Scheduler] Submitting task: id={}, name={}, type={}, priority={}", 
            taskId, request.taskName(), request.taskType(), request.priority());
        
        try {
            // 1. 持久化任务到数据库
            TaskEntity task = TaskEntity.builder()
                .id(taskId)
                .taskName(request.taskName())
                .taskType(request.taskType())
                .description(request.description())
                .prompt(request.prompt())
                .agentType(request.agentType())
                .isolation(request.isolation())
                .parameters(request.parameters())
                .priority(request.priority())
                .timeoutMs(request.timeoutMs())
                .status(TaskStatus.TaskState.PENDING.name())
                .progress(0)
                .userId(request.userId())
                .sessionId(request.sessionId())
                .metadata(request.context())
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
            
            taskRepository.save(task);
            
            // 2. 注册依赖关系
            if (request.dependsOn() != null && !request.dependsOn().isEmpty()) {
                dependencyManager.registerDependencies(taskId, request.dependsOn());
            }
            
            // 3. 根据依赖状态决定入队还是等待
            TaskStatus.TaskState initialState = determineInitialState(request.dependsOn());
            
            if (initialState == TaskStatus.TaskState.QUEUED) {
                // 无依赖或依赖已满足，直接入队
                taskQueueService.enqueue(taskId, request.priority());
            }
            
            // 4. 创建结果 Future
            CompletableFuture<TaskResult> future = new CompletableFuture<>();
            taskFutures.put(taskId, future);
            
            // 5. 发布状态变更
            statePublisher.publishStateChange(taskId, null, initialState, null, null);
            
            long queuePosition = taskQueueService.getQueuePosition(taskId);
            long estimatedStart = estimateStartTime(queuePosition);
            
            log.info("[Scheduler] Task submitted successfully: id={}, initialState={}", 
                taskId, initialState);
            
            return new TaskSubmitResponse(
                taskId,
                "ACCEPTED",
                "Task submitted successfully",
                (int) queuePosition,
                estimatedStart
            );
            
        } catch (Exception e) {
            log.error("[Scheduler] Failed to submit task", e);
            return new TaskSubmitResponse(
                taskId,
                "REJECTED",
                "Failed to submit task: " + e.getMessage(),
                -1,
                -1
            );
        }
    }
    
    @Override
    public BatchSubmitResponse submitTaskBatch(List<TaskSubmitRequest> requests) {
        String workflowId = UUID.randomUUID().toString();
        Map<String, String> taskIdMapping = new HashMap<>();
        List<TaskSubmitResponse> results = new ArrayList<>();
        
        log.info("[Scheduler] Submitting batch of {} tasks, workflowId={}", 
            requests.size(), workflowId);
        
        // 按顺序提交任务，建立依赖关系
        String previousTaskId = null;
        
        for (TaskSubmitRequest request : requests) {
            // 如果是顺序执行，添加对前一个任务的依赖
            TaskSubmitRequest modifiedRequest = request;
            if (previousTaskId != null) {
                List<String> deps = new ArrayList<>();
                if (request.dependsOn() != null) {
                    deps.addAll(request.dependsOn());
                }
                deps.add(previousTaskId);
                modifiedRequest = new TaskSubmitRequest(
                    request.taskName(),
                    request.taskType(),
                    request.description(),
                    request.prompt(),
                    request.agentType(),
                    request.isolation(),
                    request.parameters(),
                    request.priority(),
                    request.timeoutMs(),
                    deps,
                    request.userId(),
                    request.sessionId(),
                    request.context()
                );
            }
            
            TaskSubmitResponse response = submitTask(modifiedRequest);
            results.add(response);
            
            if ("ACCEPTED".equals(response.status())) {
                taskIdMapping.put(request.taskName(), response.taskId());
                previousTaskId = response.taskId();
            }
        }
        
        return new BatchSubmitResponse(
            results,
            workflowId,
            "ACCEPTED",
            taskIdMapping
        );
    }
    
    @Override
    public TaskStatus queryTaskStatus(String taskId) {
        // 优先从缓存获取
        TaskStatus cached = taskQueueService.getTaskStatusFromCache(taskId);
        if (cached != null) {
            return cached;
        }
        
        // 从数据库获取
        TaskEntity task = taskRepository.findById(taskId);
        if (task == null) {
            return null;
        }
        
        return TaskStatus.builder()
            .taskId(taskId)
            .state(TaskStatus.TaskState.valueOf(task.getStatus()))
            .progress(task.getProgress())
            .progressMessage(task.getProgressMessage())
            .assignedNode(task.getAssignedNode())
            .createdAt(toEpochMilli(task.getCreatedAt()))
            .startedAt(toEpochMilli(task.getStartedAt()))
            .updatedAt(toEpochMilli(task.getUpdatedAt()))
            .dependsOn(dependencyManager.getDependencies(taskId))
            .dependentTasks(dependencyManager.getDependents(taskId))
            .metadata(task.getMetadata())
            .build();
    }
    
    @Override
    public CancelResult cancelTask(String taskId) {
        log.info("[Scheduler] Cancel request for task: {}", taskId);
        
        TaskEntity task = taskRepository.findById(taskId);
        if (task == null) {
            return new CancelResult(taskId, false, "Task not found", false);
        }
        
        TaskStatus.TaskState currentState = TaskStatus.TaskState.valueOf(task.getStatus());
        
        // 检查是否可以取消
        if (currentState == TaskStatus.TaskState.COMPLETED || 
            currentState == TaskStatus.TaskState.FAILED ||
            currentState == TaskStatus.TaskState.CANCELLED) {
            return new CancelResult(taskId, false, 
                "Task already in terminal state: " + currentState, false);
        }
        
        boolean wasRunning = currentState == TaskStatus.TaskState.RUNNING;
        
        // 如果正在运行，通知执行节点取消
        if (wasRunning && task.getAssignedNode() != null) {
            taskExecutorService.cancelOnNode(taskId, task.getAssignedNode());
        }
        
        // 从队列移除
        taskQueueService.removeFromQueue(taskId);
        
        // 更新状态
        statePublisher.publishStateChange(taskId, currentState, 
            TaskStatus.TaskState.CANCELLED, null, "Cancelled by user");
        
        // 完成 Future
        CompletableFuture<TaskResult> future = taskFutures.remove(taskId);
        if (future != null) {
            future.complete(new TaskResult(
                taskId,
                "cancelled",
                null,
                "Cancelled by user",
                System.currentTimeMillis() - toEpochMilli(task.getCreatedAt()),
                task.getAssignedNode(),
                Map.of()
            ));
        }
        
        return new CancelResult(taskId, true, "Task cancelled", wasRunning);
    }
    
    @Override
    public TaskResult getTaskResult(String taskId, long timeout) {
        CompletableFuture<TaskResult> future = taskFutures.get(taskId);
        
        if (future == null) {
            // 任务可能已完成，从数据库获取结果
            TaskResultEntity result = taskRepository.findTaskResult(taskId);
            if (result != null) {
                return TaskResult.builder()
                    .taskId(taskId)
                    .status(result.getStatus())
                    .output(result.getOutput())
                    .error(result.getError())
                    .durationMs(result.getDurationMs())
                    .executorNode(result.getExecutorNode())
                    .artifacts(result.getArtifacts())
                    .build();
            }
            return null;
        }
        
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new TaskResult(taskId, "timeout", null, "Result retrieval timeout", 0, null, Map.of());
        } catch (ExecutionException e) {
            return new TaskResult(taskId, "failed", null, e.getCause().getMessage(), 0, null, Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TaskResult(taskId, "interrupted", null, "Thread interrupted", 0, null, Map.of());
        }
    }
    
    @Override
    public void registerDependencies(String taskId, List<String> dependsOn) {
        dependencyManager.registerDependencies(taskId, dependsOn);
        
        // 检查依赖是否满足，决定是否入队
        if (dependencyManager.areAllDependenciesMet(taskId)) {
            TaskEntity task = taskRepository.findById(taskId);
            if (task != null && 
                TaskStatus.TaskState.WAITING_DEPS.name().equals(task.getStatus())) {
                taskQueueService.enqueue(taskId, task.getPriority());
                statePublisher.publishStateChange(taskId, 
                    TaskStatus.TaskState.WAITING_DEPS, 
                    TaskStatus.TaskState.QUEUED, null, null);
            }
        }
    }
    
    @Override
    public void updateProgress(String taskId, int progress, String message) {
        taskRepository.updateProgress(taskId, progress, message);
        statePublisher.publishProgressUpdate(taskId, progress, message);
    }
    
    @Override
    public ClusterHealth getClusterHealth() {
        String leaderId = leaderElection.getCurrentLeader().orElse(null);
        List<NodeRegistry.NodeInfo> nodes = nodeRegistry.getAllNodes();
        QueueMetrics metrics = taskQueueService.getQueueMetrics();
        
        List<ClusterHealth.NodeStatus> nodeStatuses = nodes.stream()
            .map(n -> new ClusterHealth.NodeStatus(
                n.nodeId(),
                n.host(),
                n.port(),
                n.isActive(),
                n.currentLoad(),
                n.capacity(),
                n.lastHeartbeat()
            ))
            .collect(Collectors.toList());
        
        boolean healthy = leaderId != null && 
            nodes.stream().anyMatch(n -> n.isActive() && n.isLeader());
        
        return new ClusterHealth(
            healthy,
            leaderId,
            nodeStatuses,
            metrics,
            System.currentTimeMillis()
        );
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 确定任务的初始状态
     */
    private TaskStatus.TaskState determineInitialState(List<String> dependsOn) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return TaskStatus.TaskState.QUEUED;
        }
        
        for (String depId : dependsOn) {
            TaskEntity dep = taskRepository.findById(depId);
            if (dep == null || !TaskStatus.TaskState.COMPLETED.name().equals(dep.getStatus())) {
                return TaskStatus.TaskState.WAITING_DEPS;
            }
        }
        
        return TaskStatus.TaskState.QUEUED;
    }
    
    /**
     * 估算任务开始时间
     */
    private long estimateStartTime(long queuePosition) {
        // 假设平均任务执行时间 30 秒，节点数 3
        long avgExecutionTime = 30_000;
        int activeNodes = Math.max(nodeRegistry.getActiveNodeCount(), 1);
        return System.currentTimeMillis() + (queuePosition / activeNodes) * avgExecutionTime;
    }
    
    /**
     * 启动调度循环 (Leader 节点执行)
     */
    private void startSchedulingLoop() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!leaderElection.isLeader(nodeRegistry.getMyNodeId())) {
                return; // 非 Leader 不参与调度
            }
            
            try {
                // 获取可用节点
                Optional<String> availableNode = nodeRegistry.getLeastLoadedNode();
                
                if (availableNode.isEmpty()) {
                    log.debug("[Scheduler] No available nodes for task dispatch");
                    return;
                }
                
                // 分发任务
                taskQueueService.dispatchTask(availableNode.get());
                
            } catch (Exception e) {
                log.error("[Scheduler] Error in scheduling loop", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    
    /**
     * 启动超时检查器
     */
    private void startTimeoutChecker() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 查找超时任务
                List<String> timeoutTasks = taskRepository.findTimeoutTasks();
                
                for (String taskId : timeoutTasks) {
                    log.warn("[Scheduler] Task {} timeout, marking as TIMEOUT", taskId);
                    
                    TaskStatus.TaskState oldState = TaskStatus.TaskState.RUNNING;
                    statePublisher.publishStateChange(taskId, oldState, 
                        TaskStatus.TaskState.TIMEOUT, null, "Task execution timeout");
                    
                    // 完成 Future
                    CompletableFuture<TaskResult> future = taskFutures.remove(taskId);
                    if (future != null) {
                        future.complete(new TaskResult(
                            taskId,
                            "timeout",
                            null,
                            "Task execution timeout",
                            0,
                            taskRepository.findById(taskId).getAssignedNode(),
                            Map.of()
                        ));
                    }
                }
            } catch (Exception e) {
                log.error("[Scheduler] Error checking timeouts", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }
    
    /**
     * LocalDateTime 转 epoch milliseconds
     */
    private long toEpochMilli(java.time.LocalDateTime ldt) {
        return ldt != null ? 
            ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 0;
    }
}
```

### 7.2 任务执行器实现

```java
package top.javarem.omni.scheduler.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.javarem.omni.scheduler.internal.TaskInfo;
import top.javarem.omni.scheduler.internal.TaskExecutorService;
import top.javarem.omni.scheduler.model.TaskStatus;
import top.javarem.omni.scheduler.persistence.TaskRepository;
import top.javarem.omni.scheduler.coordination.TaskStatePublisher;
import top.javarem.omni.tool.agent.*;

import java.util.concurrent.*;
import java.util.Map;

/**
 * 本地任务执行器
 * 负责在当前节点执行分配的任务
 */
@Slf4j
@Component
public class LocalTaskExecutor {
    
    // ========== 依赖注入 ==========
    private final TaskRepository taskRepository;
    private final TaskStatePublisher statePublisher;
    private final SubAgentChatClientFactory agentFactory;
    private final WorktreeManager worktreeManager;
    
    // ========== 执行器配置 ==========
    private final ExecutorService executor;
    private final String nodeId;
    private final Map<String, Future<AgentResult>> runningTasks = new ConcurrentHashMap<>();
    
    public LocalTaskExecutor(
            TaskRepository taskRepository,
            TaskStatePublisher statePublisher,
            SubAgentChatClientFactory agentFactory,
            WorktreeManager worktreeManager,
            @Value("${dts.node.id:unknown}") String nodeId,
            @Value("${dts.executor.pool-size:8}") int poolSize) {
        
        this.taskRepository = taskRepository;
        this.statePublisher = statePublisher;
        this.agentFactory = agentFactory;
        this.worktreeManager = worktreeManager;
        this.nodeId = nodeId;
        
        // 创建线程池
        this.executor = new ThreadPoolExecutor(
            poolSize / 2, poolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(poolSize * 2),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "dts-exec-" + (++count));
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        log.info("[LocalTaskExecutor] Initialized with nodeId={}, poolSize={}", nodeId, poolSize);
    }
    
    /**
     * 执行任务
     */
    public void executeTask(TaskInfo taskInfo) {
        String taskId = taskInfo.taskId();
        
        log.info("[LocalTaskExecutor] Starting task: id={}, name={}, agentType={}", 
            taskId, taskInfo.taskName(), taskInfo.agentType());
        
        // 1. 更新状态为 RUNNING
        statePublisher.publishStateChange(taskId, 
            TaskStatus.TaskState.ASSIGNED, 
            TaskStatus.TaskState.RUNNING, 
            nodeId, null);
        
        taskRepository.updateStartedAt(taskId, java.time.LocalDateTime.now());
        
        // 2. 提交执行任务
        Future<AgentResult> future = executor.submit(() -> {
            try {
                return doExecute(taskInfo);
            } catch (Exception e) {
                log.error("[LocalTaskExecutor] Task execution error: {}", taskId, e);
                throw new RuntimeException(e);
            }
        });
        
        runningTasks.put(taskId, future);
        
        // 3. 处理超时
        scheduleTimeout(taskId, taskInfo.timeoutMs(), future);
    }
    
    /**
     * 实际执行任务
     */
    private AgentResult doExecute(TaskInfo taskInfo) {
        String taskId = taskInfo.taskId();
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 处理工作目录隔离
            java.nio.file.Path worktreePath = null;
            if ("worktree".equals(taskInfo.isolation())) {
                worktreePath = worktreeManager.createWorktree(
                    taskId, 
                    "dts-" + taskId.substring(0, 8),
                    java.nio.file.Path.of(System.getProperty("user.dir"))
                );
            }
            
            // 2. 构建 Agent 类型
            AgentType agentType = AgentType.fromValue(taskInfo.agentType());
            
            // 3. 执行 Agent
            AgentResult result = agentFactory.execute(
                taskId,
                agentType,
                taskInfo.prompt(),
                "dts-" + taskId,
                taskInfo.userId(),
                null,
                worktreePath
            );
            
            long duration = System.currentTimeMillis() - startTime;
            
            // 4. 处理执行结果
            if ("completed".equals(result.status())) {
                onTaskCompleted(taskId, result, duration);
            } else {
                onTaskFailed(taskId, result.error(), duration);
            }
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            onTaskFailed(taskId, e.getMessage(), duration);
            
            return new AgentResult(
                taskId,
                taskInfo.agentType(),
                "failed",
                null,
                e.getMessage(),
                duration,
                worktreePath != null ? worktreePath.toString() : null
            );
        }
    }
    
    /**
     * 任务成功完成
     */
    private void onTaskCompleted(String taskId, AgentResult result, long duration) {
        log.info("[LocalTaskExecutor] Task completed: id={}, duration={}ms", taskId, duration);
        
        // 1. 更新状态
        statePublisher.publishStateChange(taskId,
            TaskStatus.TaskState.RUNNING,
            TaskStatus.TaskState.COMPLETED,
            nodeId, null);
        
        // 2. 持久化结果
        taskRepository.saveTaskResult(taskId, 
            "completed", result.output(), null, duration, nodeId, Map.of());
        
        // 3. 清理资源
        cleanupTask(taskId);
    }
    
    /**
     * 任务执行失败
     */
    private void onTaskFailed(String taskId, String error, long duration) {
        log.error("[LocalTaskExecutor] Task failed: id={}, error={}", taskId, error);
        
        // 1. 更新状态
        statePublisher.publishStateChange(taskId,
            TaskStatus.TaskState.RUNNING,
            TaskStatus.TaskState.FAILED,
            nodeId, error);
        
        // 2. 持久化结果
        taskRepository.saveTaskResult(taskId,
            "failed", null, error, duration, nodeId, Map.of());
        
        // 3. 清理资源
        cleanupTask(taskId);
    }
    
    /**
     * 取消任务
     */
    public boolean cancelTask(String taskId) {
        Future<AgentResult> future = runningTasks.remove(taskId);
        
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            
            if (cancelled) {
                log.info("[LocalTaskExecutor] Task cancelled: id={}", taskId);
                
                statePublisher.publishStateChange(taskId,
                    TaskStatus.TaskState.RUNNING,
                    TaskStatus.TaskState.CANCELLED,
                    nodeId, "Cancelled by scheduler");
                
                cleanupTask(taskId);
            }
            
            return cancelled;
        }
        
        return false;
    }
    
    /**
     * 调度超时检测
     */
    private void scheduleTimeout(String taskId, long timeoutMs, Future<AgentResult> future) {
        if (timeoutMs <= 0) {
            timeoutMs = 180_000; // 默认 3 分钟
        }
        
        // 留一点缓冲时间
        long checkInterval = Math.min(timeoutMs / 10, 10_000);
        
        Executors.newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(() -> {
                if (future.isDone()) {
                    return; // 任务已完成，不再检查
                }
                
                // 再次检查当前任务是否还存在
                if (!runningTasks.containsKey(taskId)) {
                    return; // 任务已被移除
                }
                
                // 从数据库检查状态（可能被外部取消）
                String status = taskRepository.getTaskStatus(taskId);
                if (TaskStatus.TaskState.CANCELLED.name().equals(status)) {
                    cancelTask(taskId);
                    return;
                }
                
            }, checkInterval, checkInterval, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 清理任务相关资源
     */
    private void cleanupTask(String taskId) {
        runningTasks.remove(taskId);
        
        // 清理 Worktree
        if (worktreeManager.hasWorktree(taskId)) {
            worktreeManager.cleanupWorktree(taskId, true);
        }
        
        log.debug("[LocalTaskExecutor] Task resources cleaned: id={}", taskId);
    }
    
    /**
     * 获取当前运行中的任务数
     */
    public int getRunningTaskCount() {
        return runningTasks.size();
    }
    
    /**
     * 关闭执行器
     */
    public void shutdown() {
        log.info("[LocalTaskExecutor] Shutting down...");
        
        // 取消所有运行中的任务
        runningTasks.keySet().forEach(this::cancelTask);
        
        // 关闭线程池
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("[LocalTaskExecutor] Shutdown complete");
    }
}
```

---

## 8. 总结与实施计划

### 8.1 架构总结

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         分布式任务调度模块架构总结                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  【技术选型】                                                                 │
│  ├── 消息中间件: Redis (Sorted Set + Pub/Sub)                                │
│  ├── 分布式锁: Redisson (RLock)                                              │
│  ├── 持久化存储: MySQL                                                       │
│  └── Leader选举: Redis分布式锁 + 租约机制                                    │
│                                                                              │
│  【核心特性】                                                                 │
│  ├── ✓ 分布式执行 (多节点协同)                                               │
│  ├── ✓ 任务依赖链 (DAG编排)                                                  │
│  ├── ✓ 状态持久化 (MySQL)                                                    │
│  ├── ✓ 超时取消 (定时检测)                                                    │
│  └── ✓ 优先级队列 (Redis Sorted Set)                                         │
│                                                                              │
│  【一致性保障】                                                               │
│  ├── 数据库作为 Source of Truth                                              │
│  ├── Redis 分布式锁防止重复分配                                              │
│  ├── 乐观锁 + 版本号控制并发                                                 │
│  └── 事件驱动状态同步                                                        │
│                                                                              │
│  【可用性保障】                                                               │
│  ├── Leader 自动选举与故障转移                                               │
│  ├── 节点心跳检测与故障恢复                                                  │
│  ├── 孤儿任务自动重新分配                                                    │
│  └── 任务超时主动取消                                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 实施阶段

| 阶段 | 内容 | 预计工时 |
|------|------|----------|
| Phase 1 | 基础框架搭建（表结构、Redis结构、基础接口） | 3 天 |
| Phase 2 | 分布式协调实现（Leader选举、节点注册、心跳） | 2 天 |
| Phase 3 | 任务调度核心（优先级队列、任务分发、执行器） | 3 天 |
| Phase 4 | 依赖管理（依赖注册、触发检查、DAG） | 2 天 |
| Phase 5 | 容错机制（故障检测、任务恢复、超时处理） | 2 天 |
| Phase 6 | 与现有 Agent 系统集成 | 2 天 |
| Phase 7 | 单元测试与集成测试 | 2 天 |
| **总计** | | **16 天** |

### 8.3 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| Redis 单点故障 | 高 | Redis Cluster 部署（3主3从） |
| 任务分配冲突 | 中 | Redis 分布式锁 + 数据库乐观锁 |
| 网络分区 | 中 | 最终一致性 + 手动恢复接口 |
| 节点脑裂 | 低 | Leader 租约机制 + 节点存活检测 |

---

*文档版本: v1.0*  
*创建日期: 2024-09-05*  
*作者: OmniAgent Architecture Team*
