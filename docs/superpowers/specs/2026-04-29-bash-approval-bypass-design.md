# 危险命令审批绕过开关设计

## 需求背景

用户希望在前端界面设置一个开关，开启后可以跳过危险命令的审批流程，但 rm -rf / 这类直接拒绝的命令始终拦截。

## 绕过逻辑

| 危险等级 | 绕过模式 = OFF | 绕过模式 = ON |
|---------|---------------|---------------|
| `DENY`（直接拒绝） | 拦截 | **始终拦截** |
| `REQUIRE_APPROVAL` | 创建待审批票根 | **直接放行** |
| `WARNING` | 放行 + 警告信息 | 放行 + 警告信息 |
| `ALLOW` | 放行 | 放行 |

## 前端改动

### 1. ChatInput 组件增加开关

**位置**：工作目录输入框右侧

**形态**：
- 图标按钮：`🔓`（开启）/ `🔒`（关闭）
- Hover 显示 tooltip
- 开启时按钮高亮（text-amber-400）

### 2. 状态持久化

bypassApproval 状态随 Conversation 数据保存到 localStorage，刷新后保持。

### 3. 视觉反馈

当命令因免审批被自动执行时，在聊天流执行回显中显示 🔓 图标。

## 后端改动

### 1. SecurityInterceptor.check() 增加 bypassApproval 参数

```java
public CheckResult check(String command, String workspace, boolean acceptEdits, boolean bypassApproval)
```

### 2. 绕过逻辑

```java
if (patternResult == DangerousPatternValidator.Result.REQUIRE_APPROVAL) {
    if (bypassApproval) {
        log.warn("[Bypass] Command '{}' requires approval, but was bypassed by user session.", command);
        return new CheckResult(CheckResult.Type.ALLOW, null, "命令允许执行（免审批模式）");
    }
    return requireApproval(command, "⚠️ 危险命令（需用户审批）: " + command);
}
```

### 3. 审计日志

DENY 级别的命令始终拦截，无论是否开启绕过模式，绕过模式下 REQUIRE_APPROVAL 转 ALLOW 时打印警告日志。

## 接口改动

### ChatController

chat 接口增加可选参数 `bypassApproval`，传递给 BashExecutor。

## 文件清单

| 文件 | 改动 |
|------|------|
| `SecurityInterceptor.java` | 增加 bypassApproval 参数，调整绕过逻辑 |
| `BashExecutor.java` | 透传 bypassApproval 参数 |
| `ChatController.java` | 接口增加 bypassApproval 参数 |
| `ChatInput.tsx` | 新增开关 UI 组件 |
| `CommandApprovalInline.tsx` | 视觉反馈：显示 🔓 图标 |
| `types/index.ts` | Conversation 增加 bypassApproval 字段 |