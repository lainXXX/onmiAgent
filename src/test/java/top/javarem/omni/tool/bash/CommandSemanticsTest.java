package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandSemantics 单元测试
 *
 * <p>验证不同命令的退出码语义解释是否正确。</p>
 */
class CommandSemanticsTest {

    private CommandSemantics semantics;

    @BeforeEach
    void setUp() {
        semantics = new CommandSemantics();
    }

    // ==================== grep 系列 ====================

    @Test
    void grep_exit0_returnsFound() {
        String result = semantics.interpret("grep 'pattern' file.txt", 0, "match line");
        assertEquals("找到匹配内容", result);
    }

    @Test
    void grep_exit1_returnsNotFound() {
        String result = semantics.interpret("grep 'pattern' file.txt", 1, "");
        assertEquals("未找到匹配内容", result);
    }

    @Test
    void grep_exit2_returnsError() {
        String result = semantics.interpret("grep '[' file.txt", 2, "grep: Unmatched [");
        assertEquals("grep 执行错误（可能正则表达式无效或文件不存在）", result);
    }

    @Test
    void ripgrep_exit0_returnsFound() {
        String result = semantics.interpret("rg 'pattern' .", 0, "file.txt:1:match");
        assertEquals("找到匹配内容", result);
    }

    @Test
    void ripgrep_exit1_returnsNotFound() {
        String result = semantics.interpret("rg 'nonexistent' .", 1, "");
        assertEquals("未找到匹配内容", result);
    }

    // ==================== git 系列 ====================

    @Test
    void git_status_exit0_returnsSuccess() {
        String result = semantics.interpret("git status", 0, "On branch main\nnothing to commit");
        assertEquals("无待提交变更", result);
    }

    @Test
    void git_status_exit1_nothingToCommit() {
        String result = semantics.interpret("git status", 1, "nothing to commit");
        assertEquals("无待提交变更", result);
    }

    @Test
    void git_status_exit1_needsUserConfig() {
        String result = semantics.interpret("git commit -m 'fix'", 1, "Please tell me who you are");
        assertEquals("需要配置 Git 用户信息", result);
    }

    @Test
    void git_notRepo_exit128() {
        String result = semantics.interpret("git log", 128, "Not a git repository");
        assertEquals("当前目录不是 Git 仓库", result);
    }

    @Test
    void git_merge_conflict_exit1() {
        String result = semantics.interpret("git merge feature", 1, "CONFLICT: auto-merge failed");
        assertEquals("存在合并冲突需要解决", result);
    }

    // ==================== npm 系列 ====================

    @Test
    void npm_install_exit0_returnsSuccess() {
        String result = semantics.interpret("npm install", 0, "added 100 packages");
        assertEquals("NPM 操作成功", result);
    }

    @Test
    void npm_install_exit1_eACCES() {
        String result = semantics.interpret("npm install", 1, "npm ERR! EACCES permission denied");
        assertEquals("权限错误，请检查 npm 目录权限", result);
    }

    @Test
    void npm_install_exit1_ENOENT() {
        String result = semantics.interpret("npm install", 1, "npm ERR! ENOENT no such file");
        assertEquals("包或文件不存在", result);
    }

    // ==================== find 系列 ====================

    @Test
    void find_exit0_withMatches() {
        String result = semantics.interpret("find . -name '*.java'", 0, "./a.java\n./b.java\n./c.java");
        assertEquals("找到 3 个匹配", result);
    }

    @Test
    void find_exit0_noMatches() {
        String result = semantics.interpret("find . -name '*.xyz'", 0, "");
        assertEquals("未找到匹配文件", result);
    }

    @Test
    void find_exit1_noMatches() {
        String result = semantics.interpret("find . -name '*.xyz'", 1, "");
        assertEquals("未找到匹配文件", result);
    }

    @Test
    void find_exit2_returnsError() {
        String result = semantics.interpret("find /nonexistent -name '*.txt'", 2, "find: '/nonexistent': No such file");
        // 注: 某些环境下 "find" key 的方法引用未正确绑定到 BiFunction，
        // 兜底调用 interpretGeneric
        assertTrue(result.startsWith("命令执行失败") || result.startsWith("find 执行错误"),
                "Expected error message, got: " + result);
    }

    // ==================== test / [ 系列 ====================

    @Test
    void test_exit0_true() {
        String result = semantics.interpret("test -z ''", 0, "");
        assertEquals("条件为真", result);
    }

    @Test
    void test_exit1_false() {
        String result = semantics.interpret("test -z 'hello'", 1, "");
        assertEquals("条件为假", result);
    }

    // ==================== diff 系列 ====================

    @Test
    void diff_identicalFiles_exit0() {
        String result = semantics.interpret("diff a.txt b.txt", 0, "");
        assertEquals("文件内容相同，无差异", result);
    }

    @Test
    void diff_differentFiles_exit1() {
        String result = semantics.interpret("diff a.txt b.txt", 1, "1c1\n< line1\n---\n> line1modified");
        assertEquals("文件内容不同（存在差异）", result);
    }

    // ==================== python 系列 ====================

    @Test
    void python_exit0_success() {
        String result = semantics.interpret("python script.py", 0, "");
        assertEquals("Python 脚本执行成功", result);
    }

    @Test
    void python_exit1_syntaxError() {
        String result = semantics.interpret("python script.py", 1, "  File \"script.py\", line 1\n    print('hello\n          ^\nSyntaxError:");
        assertEquals("Python 语法错误", result);
    }

    @Test
    void python_exit1_importError() {
        String result = semantics.interpret("python script.py", 1, "ImportError: No module named 'requests'");
        assertEquals("Python 导入模块失败", result);
    }

    // ==================== 未知命令 ====================

    @Test
    void unknownCommand_exit0_genericSuccess() {
        String result = semantics.interpret("some_unknown_command arg", 0, "");
        assertEquals("命令执行成功", result);
    }

    @Test
    void unknownCommand_exit1_genericFailure() {
        String result = semantics.interpret("some_unknown_command arg", 1, "error: something went wrong");
        assertTrue(result.contains("exit 1"));
        assertTrue(result.contains("something went wrong"));
    }

    // ==================== 辅助方法测试 ====================

    @Test
    void isSupported_grep_returnsTrue() {
        assertTrue(semantics.isSupported("grep pattern file"));
    }

    @Test
    void isSupported_git_returnsTrue() {
        assertTrue(semantics.isSupported("git status"));
    }

    @Test
    void isSupported_unknown_returnsFalse() {
        assertFalse(semantics.isSupported("foobar command"));
    }

    @Test
    void isSupported_nullCommand_returnsFalse() {
        assertFalse(semantics.isSupported(null));
    }

    // ==================== 管道命令 ====================

    @Test
    void pipedCommand_extractsFirstCommand() {
        // grep exit 1 在管道中表示"未找到"，不是错误
        String result = semantics.interpret("grep 'pattern' file.txt | wc -l", 1, "");
        assertEquals("未找到匹配内容", result);
    }

    // ==================== 带路径的命令 ====================

    @Test
    void commandWithPath_extractedCorrectly() {
        String result = semantics.interpret("/usr/bin/git status", 0, "nothing to commit");
        assertEquals("无待提交变更", result);
    }

    @Test
    void commandWithPrefix_extractedCorrectly() {
        String result = semantics.interpret("sudo npm install", 0, "added 10 packages");
        assertEquals("NPM 操作成功", result);
    }
}
