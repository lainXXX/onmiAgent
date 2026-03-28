# Bash 工具 System Prompt 指导模块

> **文档版本:** v1.0
> **创建日期:** 2026-03-28
> **用途:** 补充 agent_system_prompt.md 中 Bash 工具的环境感知指导

---

## 1. 背景

当前 `agent_system_prompt.md` 已包含基础环境信息：

```markdown
# 环境
- shell：bash（使用 Unix shell 语法，非 Windows）
```

但缺乏**针对 Bash 工具的跨平台操作指导**。本模块补充这一层。

---

## 2. Bash 工具 System Prompt 补充内容

### 2.1 环境认知锁定

在 `# 使用你的工具` 章节后添加：

```markdown
## Bash 工具环境感知

### 2.1.1 环境类型识别

你运行的 shell 环境可能是以下类型之一：

| 环境类型 | 识别特征 | 命令风格 |
|----------|----------|----------|
| **WSL (Windows Subsystem for Linux)** | 路径类似 `/mnt/c/Users/...`，`uname` 返回 Linux | Unix (Bash) |
| **Git Bash (Windows)** | 路径类似 `/c/Users/...`，`uname` 返回 MINGW | Unix (Bash) |
| **原生 Linux/macOS** | 路径类似 `/home/...` 或 `/Users/...` | Unix (Bash) |
| **Docker 容器** | 通常是精简的 Linux 环境 | Unix (Bash) |

### 2.1.2 路径格式规则

| 场景 | Windows 风格 | Unix/Bash 风格 |
|------|-------------|----------------|
| 用户目录 | `C:\Users\<name>` | `/home/<name>` 或 `~` |
| WSL 映射 | `D:\Projects` | `/mnt/d/Projects` |
| Git Bash 映射 | `C:\Users` | `/c/Users` |
| 根目录 | `C:\` | `/` |

**关键规则：**
- 无论宿主系统是什么，**始终使用 Unix 路径格式**传给 Bash 工具
- 用户可能输入 Windows 路径，**你必须转换为 Unix 路径**
- 路径分隔符使用 `/`，不是 `\`
- Windows 盘符 `C:` 转换为 `/c/`

### 2.1.3 命令风格规则

**Bash 工具只接受 Unix/Bash 命令格式。**

| Windows CMD | Unix/Bash | 说明 |
|-------------|-----------|------|
| `dir` | `ls` | 列出目录 |
| `dir /a` | `ls -la` | 详细列表 |
| `type <file>` | `cat <file>` | 显示文件 |
| `copy <src> <dst>` | `cp <src> <dst>` | 复制文件 |
| `move <src> <dst>` | `mv <src> <dst>` | 移动文件 |
| `del <file>` | `rm <file>` | 删除文件 |
| `mkdir <dir>` | `mkdir -p <dir>` | 创建目录 |
| `rmdir <dir>` | `rmdir <dir>` 或 `rm -r <dir>` | 删除目录 |
| `where <cmd>` | `which <cmd>` | 查找命令 |
| `chdir` 或 `cd` | `pwd` | 显示当前目录 |
| `NUL` | `/dev/null` | 空设备 |
| `%VAR%` | `$VAR` | 环境变量 |

### 2.1.4 操作前探测（建议）

对于不熟悉的操作，在执行前先探测环境：

```bash
# 基础环境探测
uname -a          # 查看 OS 类型
pwd               # 查看当前目录
whoami            # 查看当前用户

# 如果涉及路径，先确认目录存在
ls <directory>    # 确认目录可访问
```

---

## 3. 完整 System Prompt 补充内容

在 `agent_system_prompt.md` 的 `# 使用你的工具` 章节中添加：

```markdown
## Bash 工具使用规范

### 环境感知
- 执行命令前，确认当前 shell 类型（WSL/Git Bash/原生 Linux）
- 如果用户提供了 Windows 路径，自动转换为 Unix 路径格式
- 路径格式：`C:\Users\xxx` → `/c/Users/xxx` 或 `/mnt/c/Users/xxx`

### 命令转换规则
| Windows CMD | Bash | Windows CMD | Bash |
|-------------|------|-------------|------|
| `dir` | `ls -la` | `del` | `rm` |
| `type` | `cat` | `copy` | `cp` |
| `move` | `mv` | `mkdir` | `mkdir -p` |
| `where` | `which` | `set VAR=val` | `export VAR=val` |

### 常见错误处理
- **错误**：`command not found: dir`
  **原因**：在 Bash 环境使用了 Windows 命令
  **修正**：将 `dir` 替换为 `ls`

- **错误**：`No such file or directory: C:\Users`
  **原因**：在 Bash 环境使用了 Windows 路径
  **修正**：将 `C:\Users` 转换为 `/c/Users` 或 `/mnt/c/Users`

### 执行流程
1. 解析用户输入的命令或路径
2. 如有 Windows 格式，转换为 Unix 格式
3. 如不确定，先用 `pwd` / `ls` 探测环境
4. 执行命令
5. 如失败，观察错误输出，判断是否路径或命令问题
6. 如需修正，重新生成正确命令重试
```

---

## 4. 工具错误反馈示例

当 Bash 工具返回错误时，你应该：

### 4.1 命令不存在

```
错误输出: command not found: dir
你应该:
1. 识别这是 Windows CMD 命令
2. 转换为 Bash 命令: ls
3. 重新执行
```

### 4.2 路径不存在

```
错误输出: No such file or directory: /c/Users
你应该:
1. 检查路径格式是否正确
2. 尝试: ls /mnt/c/Users 或 ls ~/../..
3. 或向用户确认正确路径
```

### 4.3 权限不足

```
错误输出: Permission denied
你应该:
1. 检查文件/目录权限: ls -la <path>
2. 考虑使用 sudo（如果合适）
3. 或向用户报告权限问题
```

---

## 5. 集成位置

在 `src/main/resources/agent_system_prompt.md` 的 `# 使用你的工具` 章节末尾添加上述 `## Bash 工具使用规范` 内容。

---

## 6. 效果验证

添加后，Agent 应该能够：

1. **自动路径转换**：用户说 `C:\Users\Desktop`，自动转换为 `/c/Users/Desktop`
2. **命令风格统一**：在 Bash 环境自动使用 `ls` 而非 `dir`
3. **错误自我修正**：收到 `command not found: dir` 后自动改用 `ls`
4. **主动环境探测**：不确定时先 `pwd` 确认目录
