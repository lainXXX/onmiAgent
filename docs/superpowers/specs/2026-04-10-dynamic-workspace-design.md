# 动态 Workspace 设计方案

## 背景

当前 Bash 工具的路径安全限制基于**编译时配置的项目根目录**，所有命令只能访问项目根目录以内的路径。这在用户需要操作项目外的目录（如 `C:/Users/aaa/Desktop/test`）时会造成不便。

用户在前端侧边栏**显式设置 workspace**，相当于主动授权访问该目录。后端应信任这一授权，放开该目录的路径限制。

## 核心思路

用户在前端侧边栏显式设置 workspace → 后端用这个路径替代默认项目根目录做路径验证 → 既然是用户主动授权，不受项目根目录限制。

## 数据流

```
前端侧边栏设置 workspace (如 C:/Users/aaa/Desktop/test)
                          ↓
              随每次对话请求发送给后端
                          ↓
          ChatController.setWorkspaceToContext()
                          ↓
             RequestContextHolder.workspaceHolder
                          ↓
              BashExecutor.getWorkspace() 读取
                          ↓
         PathNormalizer 验证路径是否在 workspace 内
```

## 改动点

### 1. RequestContextHolder

增加 `workspace` holder：

```java
private static final InheritableThreadLocal<String> workspaceHolder = new InheritableThreadLocal<>();

public static void setWorkspace(String workspace) {
    workspaceHolder.set(workspace);
}

public static String getWorkspace() {
    return workspaceHolder.get();
}
```

### 2. ChatController

从请求参数读取 workspace 并写入 context：

```java
@PostMapping("/chat")
public ChatResponse chat(
        @RequestParam(required = false) String workspace,
        // 其他参数...
) {
    if (workspace != null && !workspace.isBlank()) {
        RequestContextHolder.setWorkspace(workspace);
    }
    // ...
}
```

同样在 GET 聊天历史等接口也支持传递。

### 3. RequestContextHolder 清理

在请求处理完毕后清理所有 holder：

```java
public static void clear() {
    emitterHolder.remove();
    conversationIdHolder.remove();
    dangerousCommandPendingHolder.remove();
    workspaceHolder.remove(); // 新增
}
```

### 4. BashExecutor — 增加 workspace 解析与校验

```java
private String resolveEffectiveWorkspace(String userWorkspace) {
    if (userWorkspace == null || userWorkspace.isBlank()) {
        return defaultWorkspace; // 降级到 @Value 注入的默认值
    }
    Path path = Paths.get(userWorkspace).toAbsolutePath().normalize();
    if (!Files.exists(path) || !Files.isDirectory(path)) {
        log.warn("[BashExecutor] 用户指定的 workspace 无效: {}, 降级到默认: {}",
                 userWorkspace, defaultWorkspace);
        return defaultWorkspace;
    }
    return path.toString().replace("\\", "/");
}
```

在 `execute()` 和 `executeBackground()` 执行命令前调用此方法，获取 effective workspace 并传给 `PathNormalizer`：

```java
String effectiveWorkspace = resolveEffectiveWorkspace(RequestContextHolder.getWorkspace());
pathNormalizer.validate(command, effectiveWorkspace);
```

### 5. PathNormalizer — validate 支持动态 workspace

当前使用 `@Value` 注入固定 workspace，改为 validate 方法接收 workspace 参数：

```java
public void validate(String command, String workspace) {
    String[] words = command.split("[\\s]+");
    for (String word : words) {
        if (word.contains("/") || word.contains("\\")) {
            validatePath(word, workspace);
        }
    }
}

// 重载：兼容无参调用
public void validate(String command) {
    validate(command, this.workspace);
}

private void validatePath(String pathCandidate, String workspace) {
    // 使用传入的 workspace 替代 this.workspace
    // 其余逻辑不变
}
```

### 6. BashToolProperties — 保留默认值配置

`application.yml` 中仍保留 `ai.tool.bash.workspace` 配置，作为默认值：

```yaml
ai:
  tool:
    bash:
      workspace: ${user.dir}  # 默认项目根目录
```

## Workspace 校验

| 用户输入 workspace | 结果 |
|------|------|
| `C:/Users/aaa/Desktop/test` | ✓ 存在且是目录 → 使用 |
| `C:/nonexistent` | ✗ 目录不存在 → 降级为默认 |
| `C:/Users/aaa/file.txt` | ✗ 是文件不是目录 → 降级为默认 |
| `null` / `""` | 默认值 |

校验在 `resolveEffectiveWorkspace()` 中完成，失败时打印 warn 日志并降级。

## 行为对比

| 场景 | 之前 | 之后 |
|------|------|------|
| `cd C:/Users/aaa/Desktop/test` | ❌ 超出项目根目录被拒绝 | ✓ 在用户指定的 workspace 内 |
| workspace=`C:/Users/aaa/Desktop/test` | — | `cd /c/Users/.../mall-user && npm install` ✓ |
| 未设置 workspace | 走默认项目根目录 | 走默认项目根目录（不变） |
| workspace=`C:/` | — | ✓ 完全开放（用户自担风险） |

## 前端协作

### 接口参数

对话接口增加可选参数：

```
POST /chat?workspace=C:/Users/aaa/Desktop/test
GET  /history?conversationId=xxx&workspace=D:/work
```

### UI 侧边栏

- 输入框让用户设置 workspace 路径
- 设置后显示当前 workspace 路径
- 提供「重置为默认」按钮

### 持久化

- 推荐 localStorage 存储用户偏好 workspace
- 每次对话请求自动带上（作为表单参数）

## 安全边界说明

| workspace 值 | 效果 | 风险等级 |
|------|------|------|
| `C:/Users/aaa/project` | 限制在项目目录内 | 低 |
| `C:/` 或 `/` | **完全开放**，等于关闭路径安全 | 高 |

> ⚠️ **风险告知**：用户设置 `workspace=C:/` 或 `workspace=/` 时，命令可以访问任意路径。应在 UI 中明确标注"完全开放，自担风险"。

## ThreadLocal vs InheritableThreadLocal

经评审确认，本项目使用**普通 Spring MVC**（非 WebFlux），每个 HTTP 请求固定一个线程，使用普通 `ThreadLocal` 即可：

```java
// ✅ 采用（普通 Spring MVC）
private static final ThreadLocal<String> workspaceHolder = ...;

// ❌ 不采用（仅 WebFlux/Async 才需要）
// private static final InheritableThreadLocal<String> workspaceHolder = ...;
```

使用 `ThreadLocal` 避免了线程池复用时跨请求泄漏的风险。

## SSE 流式请求

SSE 本质是 HTTP GET，workspace **只能通过 URL 参数传递**：

```
GET /chat/stream?workspace=C:/Users/aaa/Desktop/test&conversationId=xxx
```

推荐理由：
- 不需要额外的 Filter 解析 Header
- 前端更容易控制（localStorage 取出塞到 URL 参数）

## Conversation 级别持久化

- 前端 `localStorage` 保存用户偏好的 workspace
- 每次对话请求自动带上（URL 参数或请求体）
- 后端每次读取，不依赖服务端 session
- 用户刷新页面、切换标签页，workspace 偏好不丢失

## 实现顺序

1. ✅ `RequestContextHolder` 增加 workspace holder（`ThreadLocal`）
2. ✅ `PathNormalizer.validate(command, workspace)` 重载
3. ✅ `ChatController` 从请求参数读取 workspace
4. ✅ `BashExecutor` 增加 `resolveEffectiveWorkspace()` 校验逻辑
5. 🔲 测试验证
6. 🔲 前端 UI 对接
