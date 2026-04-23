package top.javarem.omni.tool.agent;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Git Worktree 管理器
 * 为子 Agent 提供隔离的 Git 工作树环境
 */
@Slf4j
@Component
public class WorktreeManager {

    // 记录活跃的 Worktree 上下文，方便后续管理和清理
    private final Map<String, WorktreeContext> activeWorktrees = new ConcurrentHashMap<>();
    private final Path worktreesRoot;

    /**
     * 封装 Worktree 的状态信息，方便其他服务调用
     */
    @Data
    public static class WorktreeContext {
        private final String taskId;
        private final Path worktreePath;
        private final Path basePath;      // 原始 Git 仓库路径
        private final String branchName;  // 生成的唯一分支名
    }

    public WorktreeManager(
            // 允许通过配置文件指定目录，如果没有配置，默认在系统临时目录下创建
            @Value("${agent.worktree.root-dir:#{null}}") String configRootDir) {
        try {
            if (configRootDir != null && !configRootDir.isBlank()) {
                this.worktreesRoot = Path.of(configRootDir);
                if (!Files.exists(this.worktreesRoot)) {
                    Files.createDirectories(this.worktreesRoot);
                }
            } else {
                this.worktreesRoot = Files.createTempDirectory("agent-worktrees");
            }
            log.info("[WorktreeManager] Worktrees根目录初始化完成: {}", worktreesRoot.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize worktrees root directory", e);
        }
    }

    /**
     * 创建隔离的 Git Worktree
     *
     * @param taskId     任务ID
     * @param branchName 期望的分支名/后缀
     * @param basePath   原始代码库路径
     * @return Worktree 路径，如果失败返回原始 basePath
     */
    public Path createWorktree(String taskId, String branchName, Path basePath) {
        try {
            // 检查原始仓库是否为 git 仓库
            if (!isGitRepository(basePath)) {
                log.warn("[WorktreeManager] 目录不是Git仓库，跳过worktree创建: {}", basePath);
                return basePath; // 返回原始路径，降级处理
            }

            Path worktreePath = worktreesRoot.resolve("task-" + taskId);

            // 生成唯一的分支名，防止多次尝试时冲突
            String actualBranchName = String.format("agent-%s-%s-%d",
                    taskId, branchName, System.currentTimeMillis());

            log.info("[WorktreeManager] 开始创建Worktree: taskId={}, branch={}, targetPath={}",
                    taskId, actualBranchName, worktreePath);

            // 构建并执行 Git 命令
            List<String> command = List.of(
                    "git", "worktree", "add",
                    worktreePath.toAbsolutePath().toString(),
                    "-b", actualBranchName
            );

            CommandResult result = executeCommand(command, basePath);

            if (result.isSuccess()) {
                // 注册上下文
                WorktreeContext context = new WorktreeContext(taskId, worktreePath, basePath, actualBranchName);
                activeWorktrees.put(taskId, context);
                log.info("[WorktreeManager] 创建Worktree成功: taskId={}", taskId);
                return worktreePath;
            } else {
                log.error("[WorktreeManager] 创建Worktree失败: taskId={}, output={}", taskId, result.getOutput());
                return basePath; // fallback 到原始路径
            }
        } catch (Exception e) {
            log.error("[WorktreeManager] 创建Worktree发生异常: taskId={}", taskId, e);
            return basePath;
        }
    }

    /**
     * 清理 Worktree
     *
     * @param taskId       任务ID
     * @param deleteBranch 是否删除关联的分支
     */
    public void cleanupWorktree(String taskId, boolean deleteBranch) {
        WorktreeContext context = activeWorktrees.remove(taskId);
        if (context == null) {
            log.debug("[WorktreeManager] 无活跃Worktree需要清理: taskId={}", taskId);
            return;
        }

        Path worktreePath = context.getWorktreePath();
        Path basePath = context.getBasePath();
        String branchName = context.getBranchName();

        log.info("[WorktreeManager] 开始清理Worktree: taskId={}, path={}", taskId, worktreePath);

        // 步骤 1: 使用 Git 命令移除 worktree
        try {
            CommandResult result = executeCommand(List.of(
                    "git", "worktree", "remove", worktreePath.toAbsolutePath().toString(), "--force"
            ), basePath);

            if (!result.isSuccess()) {
                log.warn("[WorktreeManager] Git remove worktree 执行失败 (可能已被手动删除): {}", result.getOutput());
            }
        } catch (Exception e) {
            log.error("[WorktreeManager] 执行 git worktree remove 异常", e);
        }

        // 步骤 2: 删除关联分支
        if (deleteBranch) {
            try {
                CommandResult result = executeCommand(List.of(
                        "git", "branch", "-D", branchName
                ), basePath);

                if (!result.isSuccess()) {
                    log.warn("[WorktreeManager] Git 删除分支失败: {}", result.getOutput());
                } else {
                    log.info("[WorktreeManager] 关联分支已删除: {}", branchName);
                }
            } catch (Exception e) {
                log.error("[WorktreeManager] 执行 git branch -D 异常", e);
            }
        }

        // 步骤 3: 强制清理物理目录 (防止Git锁定导致目录残留)
        try {
            if (Files.exists(worktreePath)) {
                // 使用 Spring 提供的工具类进行递归删除，比手写更安全
                FileSystemUtils.deleteRecursively(worktreePath);
                log.info("[WorktreeManager] 物理目录已清理: {}", worktreePath);
            }
        } catch (IOException e) {
            log.error("[WorktreeManager] 清理物理目录失败，可能存在文件占用: {}", worktreePath, e);
        }
    }

    // ================= 公共查询方法 (供其他服务调用) =================

    /**
     * 获取 Worktree 路径
     */
    public Path getWorktreePath(String taskId) {
        WorktreeContext context = activeWorktrees.get(taskId);
        return context != null ? context.getWorktreePath() : null;
    }

    /**
     * 获取该任务生成的实际分支名 (方便其他服务进行 commit/push 等操作)
     */
    public String getBranchName(String taskId) {
        WorktreeContext context = activeWorktrees.get(taskId);
        return context != null ? context.getBranchName() : null;
    }

    /**
     * 检查是否有活跃的 Worktree
     */
    public boolean hasWorktree(String taskId) {
        return activeWorktrees.containsKey(taskId);
    }

    /**
     * 获取活跃 Worktree 数量
     */
    public int getActiveCount() {
        return activeWorktrees.size();
    }

    // ================= 内部工具方法 =================

    private boolean isGitRepository(Path path) {
        return Files.exists(path.resolve(".git")) && Files.isDirectory(path.resolve(".git"));
    }

    /**
     * 内部封装的命令执行器，带超时控制和防阻塞机制
     */
    private CommandResult executeCommand(List<String> command, Path workingDirectory) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // 极其重要：设置命令的工作目录
            pb.directory(workingDirectory.toFile());
            // 合并标准错误和标准输出，防止缓冲区满导致进程阻塞
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 读取输出流
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // 设置 30 秒超时，防止 Git 进程挂起阻塞 Agent
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, "Command timed out after 30 seconds. Output so far: " + output);
            }

            return new CommandResult(process.exitValue() == 0, output);

        } catch (Exception e) {
            return new CommandResult(false, "Exception executing command: " + e.getMessage());
        }
    }

    @Data
    private static class CommandResult {
        private final boolean success;
        private final String output;
    }
}