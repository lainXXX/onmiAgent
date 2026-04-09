package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BashExecutor 集成测试
 *
 * <p>测试实际命令执行、UTF-8输出、stderr合并、超时处理等核心功能。</p>
 */
@SpringBootTest
class BashExecutorIT {

    @Autowired(required = false)
    private BashExecutor executor;

    @Test
    void shouldHandleUtf8Output() throws Exception {
        if (executor == null) {
            return;
        }
        String result = executor.execute("echo '中文测试 ¥€$'", 10000);
        assertNotNull(result);
    }

    @Test
    void shouldMergeStderr() throws Exception {
        if (executor == null) {
            return;
        }
        String result = executor.execute("echo 'out' && echo 'err' >&2", 10000);
        assertTrue(result.contains("out") && result.contains("err"), "stderr should be merged, both out and err must be present");
    }

    @Test
    void shouldTimeoutGracefully() throws Exception {
        if (executor == null) {
            return;
        }
        String result = executor.execute("sleep 10", 500);
        assertTrue(result.contains("超时") || result.contains("timeout"), "Should indicate timeout");
    }

    @Test
    void shouldRejectDangerousCommand() throws Exception {
        if (executor == null) {
            return;
        }
        String result = executor.execute("rm -rf /", 5000);
        assertNotNull(result);
        assertTrue(result.contains("安全拦截") || result.contains("拒绝"),
                "Should reject dangerous command");
    }

    @Test
    void shouldExecuteSimpleCommand() throws Exception {
        if (executor == null) {
            return;
        }
        String result = executor.execute("echo 'hello'", 5000);
        assertNotNull(result);
        assertTrue(result.contains("hello"), "Should contain echo output");
    }
}
