package top.javarem.omni.tool.bash;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 命令退出码语义解释器
 *
 * <p>不同命令的退出码含义不同，不能简单用 exitCode != 0 判断"命令失败"。</p>
 *
 * <h3>示例：</h3>
 * <ul>
 *   <li>grep exit 1 = "未找到匹配"，不是错误</li>
 *   <li>grep exit 0 = "找到匹配"</li>
 *   <li>git exit 1 = "有变更待提交"（不是错误）</li>
 *   <li>test/[ -z "" ] exit 1 = "条件为假"（不是错误）</li>
 * </ul>
 */
@Component
public class CommandSemantics {

    private static final Set<String> COMMANDS_WITH_CONTEXTUAL_EXIT_CODES = Set.of(
        "grep", "rg", "fgrep", "egrep",
        "git", "svn", "hg",
        "npm", "yarn", "pnpm", "bun",
        "docker", "docker-compose",
        "find", "xargs",
        "test", "[",
        "diff", "cmp", "comm",
        "python", "python3", "pip", "node",
        "systemctl", "service", "journalctl",
        "docker", "docker-compose", "kubectl",
        "awk", "sed", "grep", "cut", "sort", "uniq", "wc"
    );

    private final Map<String, BiFunction<Integer, String, String>> semantics;

    public CommandSemantics() {
        this.semantics = Map.ofEntries(
            Map.entry("grep", this::interpretGrep),
            Map.entry("rg", this::interpretRipgrep),
            Map.entry("fgrep", this::interpretGrep),
            Map.entry("egrep", this::interpretGrep),
            Map.entry("git", this::interpretGit),
            Map.entry("npm", this::interpretNpm),
            Map.entry("yarn", this::interpretNpm),
            Map.entry("pnpm", this::interpretNpm),
            Map.entry("docker", this::interpretDocker),
            Map.entry("docker-compose", this::interpretDocker),
            Map.entry("kubectl", this::interpretKubectl),
            Map.entry("find", this::interpretFind),
            Map.entry("test", this::interpretTest),
            Map.entry("[", this::interpretTest),
            Map.entry("diff", this::interpretDiff),
            Map.entry("python", this::interpretPython),
            Map.entry("python3", this::interpretPython),
            Map.entry("pip", this::interpretPip),
            Map.entry("node", this::interpretNode),
            Map.entry("systemctl", this::interpretSystemctl),
            Map.entry("service", this::interpretService),
            Map.entry("journalctl", this::interpretJournalctl),
            Map.entry("awk", this::interpretAwk),
            Map.entry("sed", this::interpretSed),
            Map.entry("kubectl", this::interpretKubectl)
        );
    }

    /**
     * 解释命令退出码语义
     *
     * @param command 完整命令（用于提取主命令名）
     * @param exitCode 退出码
     * @param output 命令输出（stdout + stderr）
     * @return 语义描述，或 null 表示使用通用描述
     */
    public String interpret(String command, int exitCode, String output) {
        String mainCmd = extractMainCommand(command);
        BiFunction<Integer, String, String> fn = semantics.get(mainCmd);
        if (fn == null) {
            return interpretGeneric(exitCode, output);
        }
        return fn.apply(exitCode, output);
    }

    /**
     * 是否支持此命令的语义解释
     */
    public boolean isSupported(String command) {
        String mainCmd = extractMainCommand(command);
        return semantics.containsKey(mainCmd);
    }

    private String extractMainCommand(String command) {
        if (command == null || command.isBlank()) return "";

        String trimmed = command.trim();

        // 移除管道前的命令（取第一个命令）
        int pipeIdx = trimmed.indexOf('|');
        if (pipeIdx > 0) {
            trimmed = trimmed.substring(0, pipeIdx).trim();
        }

        // 移除 sudo 前缀
        if (trimmed.startsWith("sudo ")) {
            trimmed = trimmed.substring(5).trim();
        }

        // 移除路径前缀（/usr/bin/git -> git）
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash >= 0) {
            trimmed = trimmed.substring(lastSlash + 1);
        }

        // 取第一个单词作为主命令
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            trimmed = trimmed.substring(0, spaceIdx);
        }

        return trimmed.toLowerCase();
    }

    private String interpretGeneric(int exitCode, String output) {
        if (exitCode == 0) {
            return "命令执行成功";
        }
        if (output != null && !output.isEmpty()) {
            String firstLine = output.split("\n")[0];
            if (firstLine.length() > 100) {
                firstLine = firstLine.substring(0, 100) + "...";
            }
            return "命令执行失败 (exit " + exitCode + "): " + firstLine;
        }
        return "命令执行失败 (exit " + exitCode + ")";
    }

    private String interpretGrep(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "找到匹配内容";
            case 1 -> "未找到匹配内容";
            case 2 -> "grep 执行错误（可能正则表达式无效或文件不存在）";
            default -> "grep 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretRipgrep(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "找到匹配内容";
            case 1 -> "未找到匹配内容";
            case 2 -> "rg 执行错误";
            default -> "rg 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretGit(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> {
                if (output.contains("nothing to commit")) yield "无待提交变更";
                if (output.contains("Already up to date")) yield "已是最新";
                if (output.contains("up to date")) yield "已是最新";
                if (output.contains("Switched to a new branch")) yield "已切换到新分支";
                if (output.contains("Switched to branch")) yield "已切换分支";
                yield "Git 操作成功";
            }
            case 1 -> {
                if (output.contains("nothing to commit")) yield "无待提交变更";
                if (output.contains("Please tell me who you are")) yield "需要配置 Git 用户信息";
                if (output.contains("would be overwritten")) yield "本地变更会被覆盖，请先拉取或stash";
                if (output.contains("CONFLICT")) yield "存在合并冲突需要解决";
                if (output.contains("error:")) yield "Git 执行错误: " + firstErrorLine(output);
                yield "Git 操作返回非零状态";
            }
            case 2 -> "Git 致命错误: " + firstErrorLine(output);
            case 128 -> {
                if (output.contains("Not a git repository")) yield "当前目录不是 Git 仓库";
                yield "Git 仓库错误 (exit 128)";
            }
            default -> "Git 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretNpm(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "NPM 操作成功";
            case 1 -> {
                if (output.contains("EACCES")) yield "权限错误，请检查 npm 目录权限";
                if (output.contains("ENOENT")) yield "包或文件不存在";
                if (output.contains("ERR_")) yield "NPM 执行错误: " + firstErrorLine(output);
                yield "NPM 操作失败";
            }
            case 2 -> "npm 命令行参数错误";
            default -> "npm 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretDocker(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "Docker 操作成功";
            case 1 -> {
                if (output.contains("not found")) yield "Docker 未找到对应资源";
                if (output.contains("No such container")) yield "容器不存在";
                if (output.contains("Conflict")) yield "资源冲突（如容器名已占用）";
                if (output.contains("ERR_")) yield "Docker 执行错误";
                yield "Docker 操作失败";
            }
            case 125 -> "Docker daemon 未运行或无权限";
            case 127 -> "docker 命令未找到";
            default -> "Docker 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretFind(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> {
                if (output == null || output.isEmpty()) yield "未找到匹配文件";
                yield "找到 " + output.split("\n").length + " 个匹配";
            }
            case 1 -> "未找到匹配文件";
            case 2 -> "find 执行错误（路径不存在或权限不足）";
            default -> "find 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretTest(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "条件为真";
            case 1 -> "条件为假";
            case 2 -> "test 命令参数错误";
            default -> "test 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretDiff(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "文件内容相同，无差异";
            case 1 -> "文件内容不同（存在差异）";
            case 2 -> "diff 执行错误";
            default -> "diff 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretPython(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "Python 脚本执行成功";
            case 1 -> {
                if (output.contains("SyntaxError")) yield "Python 语法错误";
                if (output.contains("ImportError")) yield "Python 导入模块失败";
                if (output.contains("ModuleNotFoundError")) yield "Python 模块未找到";
                if (output.contains("NameError")) yield "Python 变量未定义";
                if (output.contains("TypeError")) yield "Python 类型错误";
                if (output.contains("ValueError")) yield "Python 值错误";
                if (output.contains("Exception")) yield "Python 异常: " + firstErrorLine(output);
                yield "Python 脚本执行失败";
            }
            case 2 -> "Python 命令行参数错误";
            default -> "Python 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretPip(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "pip 操作成功";
            case 1 -> {
                if (output.contains("ERROR:")) yield "pip 执行错误: " + firstErrorLine(output);
                yield "pip 操作失败";
            }
            default -> "pip 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretNode(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "Node.js 脚本执行成功";
            case 1 -> {
                if (output.contains("SyntaxError")) yield "JavaScript 语法错误";
                if (output.contains("ReferenceError")) yield "JavaScript 引用错误";
                if (output.contains("TypeError")) yield "JavaScript 类型错误";
                if (output.contains("Error:")) yield "JavaScript 错误: " + firstErrorLine(output);
                yield "Node.js 脚本执行失败";
            }
            default -> "Node.js 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretSystemctl(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "服务操作成功";
            case 1 -> {
                if (output.contains("not found")) yield "服务不存在";
                if (output.contains("failed")) yield "服务执行失败: " + firstErrorLine(output);
                if (output.contains("active (exited)")) yield "服务已运行（正常）";
                if (output.contains("inactive")) yield "服务已停止";
                yield "systemctl 操作失败";
            }
            case 3 -> "服务不存在或未运行";
            case 4 -> "服务状态未知";
            default -> "systemctl 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretService(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "服务操作成功";
            case 1 -> "服务操作失败: " + firstErrorLine(output);
            case 2 -> "服务脚本不存在";
            default -> "service 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretJournalctl(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "日志查询成功";
            case 1 -> "日志参数错误或无匹配结果";
            case 2 -> "journalctl 执行错误";
            default -> "journalctl 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretKubectl(int exitCode, String output) {
        if (output == null) output = "";
        return switch (exitCode) {
            case 0 -> "Kubernetes 操作成功";
            case 1 -> {
                if (output.contains("NotFound")) yield "资源不存在";
                if (output.contains("AlreadyExists")) yield "资源已存在";
                if (output.contains("Unauthorized")) yield "Kubernetes 认证失败";
                if (output.contains("Forbidden")) yield "权限不足";
                if (output.contains("Connection refused")) yield "Kubectl 无法连接到集群";
                if (output.contains("error:")) yield "Kubectl 执行错误: " + firstErrorLine(output);
                yield "Kubectl 操作失败";
            }
            case 2 -> " kubectl 命令行参数错误";
            default -> "Kubectl 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretAwk(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "AWK 脚本执行成功";
            case 1 -> {
                if (output != null && !output.isEmpty()) yield "AWK 执行完成（部分记录无匹配）";
                yield "AWK 无匹配结果";
            }
            case 2 -> "AWK 脚本语法错误";
            default -> "AWK 异常退出 (exit " + exitCode + ")";
        };
    }

    private String interpretSed(int exitCode, String output) {
        return switch (exitCode) {
            case 0 -> "sed 执行成功";
            case 1 -> "sed 执行失败（可能正则无效或文件不可写）";
            case 4 -> "sed 致命错误";
            default -> "sed 异常退出 (exit " + exitCode + ")";
        };
    }

    private String firstErrorLine(String output) {
        if (output == null || output.isEmpty()) return "";
        String[] lines = output.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed;
            }
        }
        return "";
    }
}
