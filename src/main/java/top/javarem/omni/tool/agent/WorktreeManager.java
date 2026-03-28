package top.javarem.omni.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Git Worktree 管理器
 * 为子 Agent 提供隔离的 Git 工作树环境
 */
@Slf4j
@Component
public class WorktreeManager {

    private final Map<String, Path> activeWorktrees = new ConcurrentHashMap<>();
    private final Path worktreesRoot;

    public WorktreeManager() {
        try {
            // 在系统临时目录下创建 agent worktrees 目录
            this.worktreesRoot = Files.createTempDirectory("agent-worktrees");
            log.info("[WorktreeManager] Worktrees根目录: {}", worktreesRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create worktrees root directory", e);
        }
    }

    /**
     * 创建隔离的 Git Worktree
     *
     * @param taskId 任务ID
     * @param branchName 分支名
     * @param basePath 原始代码库路径
     * @return Worktree 路径，如果失败返回 null
     */
    public Path createWorktree(String taskId, String branchName, Path basePath) {
        try {
            Path worktreePath = worktreesRoot.resolve("task-" + taskId);

            // 检查原始仓库是否为 git 仓库
            if (!isGitRepository(basePath)) {
                log.warn("[WorktreeManager] 目录不是Git仓库，跳过worktree创建: {}", basePath);
                return basePath; // 返回原始路径
            }

            // 创建 worktree
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "worktree", "add",
                    worktreePath.toString(),
                    "-b", "agent-" + taskId + "-" + branchName,
                    basePath.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                activeWorktrees.put(taskId, worktreePath);
                log.info("[WorktreeManager] 创建Worktree成功: taskId={}, path={}", taskId, worktreePath);
                return worktreePath;
            } else {
                String output = new String(process.getInputStream().readAllBytes());
                log.error("[WorktreeManager] 创建Worktree失败: taskId={}, exitCode={}, output={}", taskId, exitCode, output);
                return basePath; // fallback 到原始路径
            }
        } catch (Exception e) {
            log.error("[WorktreeManager] 创建Worktree异常: taskId={}", taskId, e);
            return basePath;
        }
    }

    /**
     * 清理 Worktree
     *
     * @param taskId 任务ID
     * @param deleteBranch 是否删除关联的分支
     */
    public void cleanupWorktree(String taskId, boolean deleteBranch) {
        Path worktreePath = activeWorktrees.remove(taskId);
        if (worktreePath == null) {
            log.debug("[WorktreeManager] 无活跃Worktree需要清理: taskId={}", taskId);
            return;
        }

        try {
            // 先移除 worktree
            ProcessBuilder pbRemove = new ProcessBuilder(
                    "git", "worktree", "remove",
                    worktreePath.toString(),
                    "--force"
            );
            pbRemove.redirectErrorStream(true);
            Process processRemove = pbRemove.start();
            processRemove.waitFor();

            // 删除关联分支
            if (deleteBranch) {
                String branchName = "agent-" + taskId + "-*";
                ProcessBuilder pbBranch = new ProcessBuilder(
                        "git", "branch", "-D",
                        branchName.replace("*", "")
                );
                pbBranch.redirectErrorStream(true);
                Process processBranch = pbBranch.start();
                processBranch.waitFor();
            }

            // 清理目录
            if (Files.exists(worktreePath)) {
                deleteDirectory(worktreePath.toFile());
            }

            log.info("[WorktreeManager] 清理Worktree完成: taskId={}, path={}", taskId, worktreePath);
        } catch (Exception e) {
            log.error("[WorktreeManager] 清理Worktree异常: taskId={}", taskId, e);
        }
    }

    /**
     * 获取 Worktree 路径
     */
    public Path getWorktreePath(String taskId) {
        return activeWorktrees.get(taskId);
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

    private boolean isGitRepository(Path path) {
        return Files.exists(path.resolve(".git"));
    }

    private void deleteDirectory(java.io.File dir) {
        if (dir.exists()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
