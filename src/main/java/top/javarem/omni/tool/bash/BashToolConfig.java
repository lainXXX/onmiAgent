package top.javarem.omni.tool.bash;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.tool.AgentTool;

import java.util.Map;

/**
 * Bash 命令执行工具
 */
@Component
@Slf4j
public class BashToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public boolean isCompactable() {
        return false;
    }

    @Resource
    private BashExecutor executor;

    @Tool(name = "bash", description = """
            以下是原版英文指令未经结构改动、完全对照原文格式直接翻译的 Markdown 文本：
                
            执行给定的 bash 命令并返回其输出。
                
            工作目录在命令之间保持不变，但 shell 状态不会保留。shell 环境从用户的配置文件（bash 或 zsh）初始化。
                
            重要提示：避免使用此工具运行 `find`、`grep`、`cat`、`head`、`tail`、`sed`、`awk` 或 `echo` 命令，除非明确收到指示，或者在你确认专用工具无法完成你的任务之后。相反，请使用相应的专用工具，因为这将为用户提供好得多的体验：
                
             - 文件搜索：使用 Glob（而不是 find 或 ls）
             - 内容搜索：使用 Grep（而不是 grep 或 rg）
             - 读取文件：使用 Read（而不是 cat/head/tail）
             - 编辑文件：使用 Edit（而不是 sed/awk）
             - 写入文件：使用 Write（而不是 echo >/cat <<EOF）
             - 沟通交流：直接输出文本（而不是 echo/printf）
            虽然 Bash 工具可以执行类似操作，但最好使用内置工具，因为它们提供更好的用户体验，并使用户更容易审查工具调用和授予权限。
                
            # 指令
             - 如果你的命令将创建新目录或文件，请首先使用此工具运行 `ls`，以验证父目录是否存在且位于正确的位置。
             - 始终在命令中使用双引号将包含空格的文件路径括起来（例如，cd "path with spaces/file.txt"）
             - 尽量在整个会话期间通过使用绝对路径并避免使用 `cd` 来保持当前工作目录不变。如果用户明确要求，你可以使用 `cd`。
             - 你可以指定一个可选的超时时间（以毫秒为单位，最高可达 600000ms / 10 分钟）。默认情况下，你的命令将在 120000ms（2 分钟）后超时。
             - 你可以使用 `run_in_background` 参数在后台运行命令。仅当你不需要立即获得结果，并且接受稍后在命令完成时收到通知时，才使用此参数。你无需立即检查输出——当它完成时你会收到通知。使用此参数时，无需在命令末尾添加 '&'。
             - 为你的命令执行的操作写出清晰、简洁的描述。对于简单的命令，保持简短（5-10个词）。对于复杂的命令（管道命令、晦涩的标志，或一眼难以理解的任何内容），请包含足够的上下文，以便用户能够理解你的命令将做什么。
             - 当发出多个命令时：
              - 如果命令是独立的并且可以并行运行，请在单条消息中进行多次 Bash 工具调用。示例：如果你需要运行 "git status" 和 "git diff"，请发送一条包含两个并行 Bash 工具调用的消息。
              - 如果命令相互依赖且必须按顺序运行，请使用单个 Bash 调用并使用 '&&' 将它们链接在一起。
              - 仅当你需要按顺序运行命令但不关心前面的命令是否失败时，才使用 ';'。
              - 请勿使用换行符分隔命令（引号字符串中的换行符是可以的）。
             - 对于 git 命令：
              - 倾向于创建一个新的提交，而不是修改（amending）现有的提交。
              - 在运行破坏性操作（例如，git reset --hard, git push --force, git checkout --）之前，考虑是否有更安全的替代方案可以达到相同的目标。仅在破坏性操作确实是最佳方法时才使用它们。
              - 永远不要跳过钩子（--no-verify）或绕过签名（--no-gpg-sign, -c commit.gpgsign=false），除非用户明确要求这样做。如果钩子失败，请调查并修复根本问题。
             - 避免不必要的 `sleep` 命令：
              - 不要在可以立即运行的命令之间休眠——直接运行它们。
              - 如果你的命令需要长时间运行，并且你希望在它完成时收到通知——使用 `run_in_background`。不需要休眠。
              - 不要在休眠循环中重试失败的命令——请诊断根本原因。
              - 如果在等待你使用 `run_in_background` 启动的后台任务，当它完成时你会收到通知——不要去轮询。
              - 如果你必须轮询外部进程，请使用检查命令（例如 `gh run view`）而不是先休眠。
              - 如果必须休眠，请保持短暂的时长（1-5 秒），以避免阻塞用户。
                
            # 使用 git 提交更改
                
            仅在用户请求时创建提交。如果不清楚，请先提问。当用户要求你创建一个新的 git 提交时，请仔细遵循以下步骤：
                
            Git 安全协议：
            - 永远不要更新 git config
            - 永远不要运行破坏性的 git 命令（push --force, reset --hard, checkout ., restore ., clean -f, branch -D），除非用户明确请求这些操作。采取未经授权的破坏性操作毫无帮助，并且可能导致工作丢失，因此最好仅在收到直接指示时才运行这些命令。
            - 永远不要跳过钩子（--no-verify, --no-gpg-sign 等），除非用户明确请求
            - 永远不要对 main/master 运行强制推送（force push），如果用户提出请求，请警告他们
            - 关键：始终创建新的提交（NEW commits）而不是修改（amending），除非用户明确请求 git amend。当预提交钩子（pre-commit hook）失败时，提交并没有发生——因此 --amend 将修改前一个提交，这可能导致破坏工作或丢失以前的更改。相反，在钩子失败后，修复问题，重新暂存，并创建一个新提交
            - 暂存文件时，倾向于按名称添加特定文件，而不是使用 "git add -A" 或 "git add ."，后者可能会意外包含敏感文件（.env、凭据）或大型二进制文件
            - 永远不要提交更改，除非用户明确要求你这样做。仅在明确要求时才提交是非常重要的，否则用户会觉得你过于主动
                
            1. 你可以在单次响应中调用多个工具。当请求多条独立信息且所有命令都可能成功时，并行运行多个工具调用以获得最佳性能。并行运行以下 bash 命令，每个命令使用 Bash 工具：
              - 运行 git status 命令查看所有未追踪的文件。重要提示：永远不要使用 -uall 标志，因为它可能会在大型仓库上引起内存问题。
              - 运行 git diff 命令查看将被提交的暂存和未暂存更改。
              - 运行 git log 命令查看最近的提交消息，以便你可以遵循该仓库的提交消息风格。
            2. 分析所有已暂存的更改（包括先前已暂存和新添加的）并起草提交消息：
              - 总结更改的性质（例如：新功能、现有功能的增强、错误修复、重构、测试、文档等）。确保消息准确反映更改及其目的（即“add”表示全新的功能，“update”表示对现有功能的增强，“fix”表示错误修复等）。
              - 不要提交可能包含机密的文件（.env、credentials.json 等）。如果用户特别要求提交这些文件，请警告他们
              - 起草一条简洁的（1-2句）提交消息，侧重于“为什么”而不是“是什么”
              - 确保它准确反映了更改及其目的
            3. 你可以在单次响应中调用多个工具。当请求多条独立信息且所有命令都可能成功时，并行运行多个工具调用以获得最佳性能。运行以下命令：
               - 将相关的未追踪文件添加到暂存区。
               - 使用消息创建提交。
               - 提交完成后运行 git status 以验证是否成功。
               注意：git status 依赖于提交的完成，因此在提交之后按顺序运行它。
            4. 如果由于预提交钩子（pre-commit hook）导致提交失败：修复问题并创建一个新提交
                
            重要说明：
            - 除了 git bash 命令之外，永远不要运行额外的命令来读取或探索代码
            - 永远不要使用 TodoWrite 或 Agent 工具
            - 不要推送到远程仓库，除非用户明确要求你这样做
            - 重要提示：永远不要使用带有 -i 标志的 git 命令（如 git rebase -i 或 git add -i），因为它们需要交互式输入，而这是不受支持的。
            - 重要提示：不要在 git rebase 命令中使用 --no-edit，因为 --no-edit 标志不是 git rebase 的有效选项。
            - 如果没有要提交的更改（即没有未追踪的文件也没有修改），请不要创建空提交
            - 为了确保良好的格式，始终通过 HEREDOC 传递提交消息，如下例所示：
            <example>
            git commit -m "$(cat <<'EOF'
               此处填写提交消息。
               EOF
               )"
            </example>
                
            # 创建合并请求（pull requests）
            通过 Bash 工具使用 gh 命令执行所有与 GitHub 相关的任务，包括处理 issues、合并请求、检查（checks）和发布（releases）。如果给定了 Github URL，请使用 gh 命令获取所需信息。
                
            重要提示：当用户要求你创建合并请求时，请仔细遵循以下步骤：
                
            1. 你可以在单次响应中调用多个工具。当请求多条独立信息且所有命令都可能成功时，并行运行多个工具调用以获得最佳性能。使用 Bash 工具并行运行以下 bash 命令，以便了解分支自偏离 main 分支以来的当前状态：
               - 运行 git status 命令查看所有未追踪的文件（永远不要使用 -uall 标志）
               - 运行 git diff 命令查看将被提交的暂存和未暂存的更改
               - 检查当前分支是否跟踪远程分支并与远程同步，以便知道是否需要推送到远程
               - 运行 git log 命令和 `git diff [base-branch]...HEAD` 以了解当前分支的完整提交历史（从它偏离基准分支时起）
            2. 分析将包含在合并请求中的所有更改，确保查看所有相关的提交（不仅仅是最新提交，而是将包含在合并请求中的所有提交！！！），并起草合并请求标题和摘要：
               - 保持 PR 标题简短（少于 70 个字符）
               - 使用描述/正文部分提供详细信息，而不是标题
            3. 你可以在单次响应中调用多个工具。当请求多条独立信息且所有命令都可能成功时，并行运行多个工具调用以获得最佳性能。并行运行以下命令：
               - 如果需要，创建新分支
               - 如果需要，使用 -u 标志推送到远程
               - 使用如下格式通过 gh pr create 创建 PR。使用 HEREDOC 传递正文以确保正确的格式。
            <example>
            gh pr create --title "pr标题" --body "$(cat <<'EOF'
            ## 总结
            <1-3 个项目符号点>
                
            ## 测试计划
            [测试该合并请求的待办事项 Markdown 检查列表...]
            EOF
            )"
            </example>
                
            重要提示：
            - 不要使用 TodoWrite 或 Agent 工具
            - 完成后返回 PR 的 URL，以便用户可以看到它
                
            # 其他常见操作
    """)
    public String bash(
            @ToolParam(description = "完整 Shell 命令。路径有空格需加双引号；建议使用绝对路径") String command,
            @ToolParam(description = "对命令行为的简洁描述（5-10字），便于审核日志", required = false) String description,
            @ToolParam(description = "超时毫秒数。默认120000ms (2分钟)，最大600000ms (10分钟)", required = false) Long timeout,
            @ToolParam(description = "是否后台运行。适用于耗时较长且不需要立即获取输出的任务", required = false) Boolean runInBackground,
            @ToolParam(description = "危险选项：是否禁用沙箱。通常用于需要突破限制的操作", required = false) Boolean dangerouslyDisableSandbox,
            ToolContext toolContext
            ) {

        // 从 ToolContext 获取 workspace 和 acceptEdits（由 ChatController 设置）
        String workspace = extractWorkspace(toolContext);
        boolean acceptEdits = extractAcceptEdits(toolContext);

        log.info("[BashToolConfig] 执行命令: {} | workspace: {} | 描述: {} | 后台: {} | acceptEdits: {}",
                command, workspace, description, runInBackground, acceptEdits);

        // 1. 参数校验
        if (command == null || command.trim().isEmpty()) {
            return "❌ 命令不能为空";
        }

        // 2. 沙箱检查 — 始终拒绝
        if (Boolean.TRUE.equals(dangerouslyDisableSandbox)) {
            log.warn("[BashToolConfig] 沙箱禁用请求被拒绝: {}", command);
            return "❌ 沙箱禁用选项不被支持，拒绝执行危险操作: " + command;
        }

        // 3. 超时处理
        long timeoutMs = normalizeTimeout(timeout);

        // 4. 执行命令
        try {
            if (Boolean.TRUE.equals(runInBackground)) {
                return executor.executeBackground(command, workspace);
            }
            return executor.execute(command, timeoutMs, workspace, acceptEdits);
        } catch (Exception e) {
            log.error("[BashToolConfig] 执行异常: {}", e.getMessage(), e);
            return "❌ 命令执行异常: " + e.getMessage();
        }
    }

    private String extractWorkspace(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object workspaceObj = toolContext.getContext().get(AdvisorContextConstants.WORKSPACE);
        if (workspaceObj != null) {
            String workspace = workspaceObj.toString();
            if (!workspace.isBlank()) {
                return workspace;
            }
        }
        return null;
    }

    private boolean extractAcceptEdits(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return false;
        }
        Object acceptEditsObj = toolContext.getContext().get("acceptEdits");
        if (acceptEditsObj != null) {
            return Boolean.TRUE.equals(acceptEditsObj) ||
                   "true".equalsIgnoreCase(acceptEditsObj.toString());
        }
        return false;
    }

    private long normalizeTimeout(Long timeout) {
        if (timeout == null || timeout < 1) {
            return BashConstants.DEFAULT_TIMEOUT_MS;
        }
        return Math.min(timeout, BashConstants.MAX_TIMEOUT_MS);
    }
}
