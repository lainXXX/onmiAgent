# Spring AI BashTool 实现设计文档

## 概述

本文档描述如何在 Spring AI 项目中复现 Claude Code 的 BashTool，实现一个安全可控的命令执行工具。

**目标**：复刻 Claude Code BashTool 的核心功能，作为 Spring AI Agent 的可调用工具。

**参考实现**：Claude Code `restored-src/src/tools/BashTool/` 目录下的完整实现。

---

## 1. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     Spring AI Agent                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐   │
│  │ User Input  │→ │  Prompt     │→ │  Tool Executor          │   │
│  └─────────────┘  │  (Spring AI) │  │  (BashTool)            │   │
│                   └─────────────┘  └─────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                        BashTool                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Spring AI Tool Interface                                  │   │
│  │ - name: "bash"                                           │   │
│  │ - description: 执行shell命令                             │   │
│  │ - inputSchema: command, timeout, runInBackground          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ PermissionChecker                                         │   │
│  │ 1. CommandParser - 解析命令结构                            │   │
│  │ 2. DangerousCommandFilter - 危险命令检测                   │   │
│  │ 3. RuleMatcher - 规则匹配                                  │   │
│  │ 4. PathValidator - 路径验证                               │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ BashExecutor                                              │   │
│  │ 1. ProcessBuilder / ProcessImpl 执行命令                  │   │
│  │ 2. WorkingDirectoryManager - CWD 跟踪                     │   │
│  │ 3. TimeoutController - 超时控制                          │   │
│  │ 4. OutputCollector - stdout/stderr 收集                   │   │
│  │ 5. CommandSemantics - 退出码语义解释                      │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ SandboxManager (可选)                                     │   │
│  │ - Linux: bwrap (bubblewrap)                              │   │
│  │ - macOS: 简化处理                                        │   │
│  │ - Windows: 暂不支持                                       │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 目录结构

```
src/main/java/com/example/ai/
└── tool/
    └── bash/
        ├── BashTool.java                   # Spring AI Tool 接口实现
        ├── BashExecutor.java               # 核心执行器
        ├── PermissionChecker.java          # 权限检查器
        ├── CommandParser.java              # 命令解析器
        ├── DangerousCommandFilter.java     # 危险命令过滤器
        ├── CommandSemantics.java           # 退出码语义
        ├── WorkingDirectoryManager.java    # 工作目录管理
        ├── BashRequest.java                # 请求对象
        ├── BashResult.java                 # 结果对象
        └── config/
            ├── BashToolAutoConfiguration.java
            └── BashToolProperties.java    # 配置属性
```

---

## 3. 核心组件设计

### 3.1 BashRequest (请求对象)

```java
public record BashRequest(
    String command,           // 要执行的命令 (必填)
    Integer timeout,         // 超时时间 ms (默认: 180000 = 3分钟)
    Boolean runInBackground,  // 是否后台运行 (默认: false)
    Boolean dangerouslyDisableSandbox  // 是否禁用沙箱 (默认: false)
) {}
```

### 3.2 BashResult (结果对象)

```java
public record BashResult(
    String stdout,           // 标准输出
    String stderr,           // 标准错误
    Integer exitCode,        // 退出码
    Boolean interrupted,     // 是否被中断
    String returnCodeInterpretation,  // 退出码语义解释
    String backgroundTaskId, // 后台任务ID (如有)
    Boolean isImage          // 是否为图片输出
) {}
```

### 3.3 PermissionChecker (权限检查器)

**检查流程：**

```
┌────────────────────────────────────────────────────────────┐
│                    权限检查流程                              │
├────────────────────────────────────────────────────────────┤
│ 1. CommandParser.parse(command)                            │
│    → 提取: mainCommand, subCommand, args, redirects, pipes │
│                                                            │
│ 2. DangerousCommandFilter.isDangerous(command)            │
│    → 黑名单检测: rm -rf /, :(){ :|:& };:, mkfs, etc.     │
│    → 警告列表: dd, fdisk, partition                        │
│                                                            │
│ 3. RuleMatcher.matches(command, rules)                    │
│    → 精确匹配: "npm"                                       │
│    → 前缀匹配: "git *"                                     │
│    → 正则匹配: "git (push|pull|commit).*"                 │
│                                                            │
│ 4. PathValidator.isAllowed(path, allowedRoots)           │
│    → 防止 cd 逃逸到 /, ~, 等禁止目录                        │
│    → 限制在项目目录内                                       │
└────────────────────────────────────────────────────────────┘
```

**危险命令黑名单（参考 Claude Code）：**

```java
private static final Set<String> DANGEROUS_COMMANDS = Set.of(
    ":(){ :|:& };:",  // Fork bomb
    "mkfs",
    "fdisk",
    "parted",
    "dd if=",
    "shutdown",
    "reboot",
    "init 0",
    "init 6",
    // ... 更多
);
```

### 3.4 BashExecutor (执行器)

**核心执行逻辑：**

```java
public class BashExecutor {

    // 共享线程池，避免每次执行创建新线程导致资源泄漏
    private final ExecutorService executorService;

    public BashExecutor(BashToolProperties properties) {
        // 使用可配置大小的线程池
        this.executorService = new ThreadPoolExecutor(
            2,                              // corePoolSize
            properties.getMaxConcurrentCommands(), // maxPoolSize
            60L, TimeUnit.SECONDS,          // keepAliveTime
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder()
                .setNameFormat("bash-executor-%d")
                .build()
        );
    }

    public BashResult execute(BashRequest request, Path workingDir) {
        // 1. 创建进程
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("bash", "-c", request.command());
        pb.directory(workingDir.toFile());

        // 过滤敏感环境变量
        Map<String, String> env = filterSensitiveEnv();
        pb.environment().putAll(env);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // 进程创建失败处理
            return BashResult.error("Failed to start process: " + e.getMessage());
        }

        // 2. 收集输出（异步）
        CompletableFuture<BashResult> future = CompletableFuture.supplyAsync(
            () -> collectOutput(process),
            executorService
        );

        try {
            return future.get(request.timeout(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            process.destroyForcibly();
            return BashResult.timeout(request.timeout());
        } catch (ExecutionException e) {
            return BashResult.error("Execution error: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return BashResult.interrupted();
        }
    }

    private Map<String, String> filterSensitiveEnv() {
        // 移除敏感环境变量，防止泄漏
        Map<String, String> env = new HashMap<>(System.getenv());
        env.remove("API_KEY");
        env.remove("SECRET_KEY");
        env.remove("TOKEN");
        env.remove("PASSWORD");
        // ... 更多敏感变量
        return env;
    }

    // 优雅关闭
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}
```

**关键修复点：**
- 使用共享 `ExecutorService` 替代每次创建新线程池
- 添加进程创建失败的 `IOException` 处理
- 敏感环境变量过滤

### 3.5 WorkingDirectoryManager (工作目录管理)

```java
public class WorkingDirectoryManager {

    private Path currentDir;
    private final Path projectRoot;
    private final Path normalizedProjectRoot;

    public WorkingDirectoryManager(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        this.currentDir = this.normalizedProjectRoot;
    }

    // 跟踪 cd 命令，更新当前目录
    public Path trackAndValidate(String stdout, String stderr) {
        // 解析 pwd -P 输出，验证目录
        Path newDir = parseNewWorkingDir(stdout);
        if (isWithinProject(newDir)) {
            this.currentDir = newDir.toAbsolutePath().normalize();
            return this.currentDir;
        }
        return resetToProjectRoot();
    }

    // 使用 normalize() 和 toRealPath() 防止符号链接绕过
    private boolean isWithinProject(Path dir) {
        try {
            // 解析符号链接获取真实路径
            Path realPath = dir.toAbsolutePath().normalize().toRealPath();
            return realPath.startsWith(normalizedProjectRoot);
        } catch (IOException e) {
            // 如果无法解析符号链接，使用保守策略
            return dir.toAbsolutePath().normalize().startsWith(normalizedProjectRoot);
        }
    }

    private Path resetToProjectRoot() {
        this.currentDir = normalizedProjectRoot;
        return currentDir;
    }
}
```

**关键修复点：**
- 使用 `normalize()` 解析 `.`, `..` 等路径
- 使用 `toRealPath()` 解析符号链接的真实路径
- 比较规范化后的真实路径

### 3.6 CommandSemantics (退出码语义)

```java
@Service  // 作为 Spring Bean 便于注入和测试
public class CommandSemantics {

    private final Map<String, BiFunction<Integer, String, String>> semantics;

    public CommandSemantics() {
        this.semantics = Map.of(
            "grep", this::interpretGrep,
            "rg", this::interpretRipgrep,
            "find", this::interpretFind,
            "diff", this::interpretDiff,
            "git", this::interpretGit,
            "npm", this::interpretNpm,
            "docker", this::interpretDocker
        );
    }

    public String interpret(String command, int exitCode, String output) {
        String mainCmd = extractMainCommand(command);
        BiFunction<Integer, String, String> fn = semantics.get(mainCmd);
        return fn != null ? fn.apply(exitCode, output) : null;
    }

    private String interpretGrep(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "Match found";
            case 1 -> "No matches found";  // 不是错误！
            default -> "Error in grep";
        };
    }

    private String interpretGit(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "Success";
            case 1 -> output.contains("nothing to commit") ? "Nothing to commit" : "Git error: " + output;
            default -> "Git error with exit code " + exitCode;
        };
    }

    // ... 其他命令的语义解释
}
```

---

## 4. Spring AI Tool 接口实现

**注意**：Spring AI 的 `Tool` 接口有多个版本，实现时请验证实际使用的 Spring AI 版本。

### 4.1 实现方案 A: 实现 `org.springframework.ai.tool.Tool` 接口

```java
@Component
public class BashTool implements Tool<BashRequest, BashResult> {

    private final BashExecutor executor;
    private final PermissionChecker permissionChecker;
    private final CommandSemantics commandSemantics;
    private final WorkingDirectoryManager wdManager;

    // 构造器注入，避免 @Component 和手动 new 的矛盾
    public BashTool(
            BashExecutor executor,
            PermissionChecker permissionChecker,
            CommandSemantics commandSemantics,
            WorkingDirectoryManager wdManager) {
        this.executor = executor;
        this.permissionChecker = permissionChecker;
        this.commandSemantics = commandSemantics;
        this.wdManager = wdManager;
    }

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return """
            Execute shell commands on the local system.
            Use this to run terminal commands, build tools, git operations, etc.
            Commands are executed in the project directory with appropriate timeout.
            """;
    }

    @Override
    public Class<BashRequest> getInputType() {
        return BashRequest.class;
    }

    @Override
    public ToolCallOutput doCall(BashToolInput input) {
        BashRequest request = BashRequest.from(input);

        // 1. 权限检查
        PermissionResult permission = permissionChecker.check(request);
        if (!permission.allowed()) {
            return ToolCallOutput.builder()
                .isError(true)
                .result(BashResult.denied(permission.reason()).toString())
                .build();
        }

        // 2. 执行命令
        BashResult result = executor.execute(request, wdManager.getCurrentDir());

        // 3. 解释退出码
        String interpretation = commandSemantics.interpret(
            request.command(), result.exitCode(), result.stdout()
        );

        // 4. 更新工作目录
        wdManager.trackAndValidate(result.stdout(), result.stderr());

        BashResult finalResult = new BashResult(
            result.stdout(),
            result.stderr(),
            result.exitCode(),
            result.interrupted(),
            interpretation,
            result.backgroundTaskId(),
            result.isImage()
        );

        return ToolCallOutput.builder()
            .result(finalResult.toString())
            .build();
    }
}
```

### 4.2 实现方案 B: 实现 `@Tool` 注解 + `ToolCallback` (更现代的方式)

```java
@Configuration
public class BashToolConfiguration {

    @Bean
    public ToolCallback bashToolCallback(
            BashExecutor executor,
            PermissionChecker permissionChecker,
            CommandSemantics commandSemantics,
            WorkingDirectoryManager wdManager) {

        return ToolCallback.builder()
            .name("bash")
            .description("Execute shell commands on the local system")
            .inputSchema("""
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "The shell command to execute"
                    },
                    "timeout": {
                      "type": "integer",
                      "description": "Timeout in milliseconds",
                      "default": 180000
                    },
                    "runInBackground": {
                      "type": "boolean",
                      "description": "Run in background",
                      "default": false
                    }
                  },
                  "required": ["command"]
                }
                """)
            .toolCallback((input) -> {
                // 执行逻辑同上
            })
            .build();
    }
}
```

### 4.3 后台任务支持 (BackgroundTaskManager)

```java
@Component
public class BackgroundTaskManager {

    private final Map<String, Process> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<BashResult>> taskFutures = new ConcurrentHashMap<>();

    public String submitBackgroundTask(BashRequest request, Process process, CompletableFuture<BashResult> future) {
        String taskId = UUID.randomUUID().toString();
        activeTasks.put(taskId, process);
        taskFutures.put(taskId, future);

        // 任务完成后自动清理
        future.thenRun(() -> {
            activeTasks.remove(taskId);
            taskFutures.remove(taskId);
        });

        return taskId;
    }

    public Optional<BashResult> getTaskResult(String taskId) {
        CompletableFuture<BashResult> future = taskFutures.get(taskId);
        if (future == null) return Optional.empty();
        if (!future.isDone()) return Optional.empty();
        return future.getNow(null);
    }

    public boolean cancelTask(String taskId) {
        Process process = activeTasks.get(taskId);
        if (process != null) {
            process.destroyForcibly();
            activeTasks.remove(taskId);
            return true;
        }
        return false;
    }

    public List<String> listActiveTasks() {
        return new ArrayList<>(activeTasks.keySet());
    }
}
```

---

## 5. 配置管理

### 5.1 BashToolProperties

```java
@ConfigurationProperties(prefix = "ai.tool.bash")
public class BashToolProperties {

    private Integer defaultTimeout = 180_000;  // 3分钟
    private Integer maxTimeout = 1_800_000;    // 30分钟
    private Integer maxConcurrentCommands = 4;  // 最大并发命令数
    private Boolean sandboxEnabled = true;
    private List<String> allowedCommands = List.of();  // 空 = 全部允许
    private List<String> blockedCommands = List.of();
    private Path projectRoot = Path.of(System.getProperty("user.dir"));
    private Set<String> sensitiveEnvVars = Set.of(
        "API_KEY", "SECRET_KEY", "TOKEN", "PASSWORD", "PRIVATE_KEY"
    );
}
```

### 5.2 自动配置（正确注入）

```java
@Configuration
@EnableConfigurationProperties(BashToolProperties.class)
public class BashToolAutoConfiguration {

    // 1. 创建共享的 BashExecutor（线程池只需一个）
    @Bean(destroyMethod = "shutdown")
    public BashExecutor bashExecutor(BashToolProperties props) {
        return new BashExecutor(props);
    }

    // 2. 创建 PermissionChecker
    @Bean
    public PermissionChecker permissionChecker(BashToolProperties props) {
        return new PermissionChecker(props);
    }

    // 3. 创建 CommandSemantics
    @Bean
    public CommandSemantics commandSemantics() {
        return new CommandSemantics();
    }

    // 4. 创建 WorkingDirectoryManager
    @Bean
    public WorkingDirectoryManager workingDirectoryManager(BashToolProperties props) {
        return new WorkingDirectoryManager(props.getProjectRoot());
    }

    // 5. 创建 BashTool，接收构造器注入的依赖
    @Bean
    public BashTool bashTool(
            BashExecutor executor,
            PermissionChecker permissionChecker,
            CommandSemantics commandSemantics,
            WorkingDirectoryManager workingDirectoryManager) {
        return new BashTool(executor, permissionChecker, commandSemantics, workingDirectoryManager);
    }

    // 6. 后台任务管理器（可选）
    @Bean
    public BackgroundTaskManager backgroundTaskManager() {
        return new BackgroundTaskManager();
    }
}
```

---

## 6. 与 Claude Code 的差异说明

| Claude Code 特性 | 本实现处理 | 原因 |
|-----------------|-----------|-----|
| Tree-sitter AST 解析 | 正则 + 字符串匹配 | 简化实现，本地开发无需太复杂 |
| 多级权限模式 (acceptEdits 等) | 本地单一模式 | 本地开发无需复杂权限分级 |
| MCP 服务器工具集成 | 不适用 | Spring AI 独立架构 |
| bwrap 沙箱 | 可选 (默认开启) | 本地开发可选择性使用 |
| Classifier 自动允许 | 规则文件替代 | 简化实现 |
| 长时间命令进度回调 | 简单实现 | 可后续增强 |
| 图片输出检测 | 简单扩展名检测 | 可后续增强 |
| 环境变量传递 | 敏感变量过滤 | 防止 API key 等泄漏 |

### 6.1 已知安全限制（本地开发版可接受）

1. **符号链接**：虽然使用了 `toRealPath()` 验证，但顶级目录的符号链接仍可能有风险
2. **内核参数**：无法限制内存、CPU 等资源
3. **网络访问**：无法限制网络请求（可配合 firewall rules）
4. **容器逃逸**：如果启用沙箱，bwrap 有已知逃逸方法

**建议**：本地开发版本主要用于个人工作流，生产环境需配合容器隔离。

---

## 7. 实现计划

### Phase 1: 核心实现
1. BashRequest / BashResult 数据对象
2. CommandParser 命令解析器
3. DangerousCommandFilter 危险命令检测
4. BashExecutor 基础执行

### Phase 2: 安全增强
5. PermissionChecker 权限检查
6. RuleMatcher 规则匹配
7. PathValidator 路径验证

### Phase 3: 高级特性
8. WorkingDirectoryManager 工作目录跟踪
9. CommandSemantics 退出码语义
10. TimeoutController 超时控制
11. 后台任务支持

### Phase 4: Spring AI 集成
12. 实现 Tool 接口
13. 自动配置
14. 测试验证

---

## 8. 验收标准

1. **命令执行**：能够执行 `ls`, `git status`, `npm test` 等常用命令
2. **权限检查**：危险命令 (`rm -rf /`) 被正确拦截
3. **超时控制**：长时间命令能在配置的超时时间后被终止
4. **工作目录**：cd 命令能够正确跟踪目录变化
5. **退出码解释**：git/grep 等命令的退出码被正确解释
6. **Spring AI 集成**：能够作为 Tool 被 Spring AI Agent 调用
7. **后台任务**：标记为 `runInBackground=true` 的命令能够后台执行
8. **资源管理**：线程池正确复用，无内存泄漏
9. **敏感变量**：API keys 等敏感环境变量不会传递给子进程

---

## 9. 审查修复记录

| 问题 | 修复方案 |
|-----|---------|
| ExecutorService 泄漏 | 使用共享 ThreadPoolExecutor，配置 destroyMethod |
| @Component + new 矛盾 | 统一使用构造器注入，Bean 手动创建依赖 |
| Spring AI Tool 接口不确定 | 提供两种实现方案，验证实际接口 |
| CommandSemantics 静态方法 | 改为 Spring @Service，支持 DI |
| 路径验证可绕过符号链接 | 使用 toRealPath() + normalize() |
| 后台任务生命周期未定义 | 添加 BackgroundTaskManager 组件 |
| 进程创建失败无处理 | 添加 IOException 处理 |
| 敏感环境变量泄漏 | 添加 filterSensitiveEnv() 方法 |
