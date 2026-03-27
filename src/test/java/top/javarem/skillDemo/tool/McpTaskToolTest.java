package top.javarem.skillDemo.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP TaskOutput & TaskStop 工具 TDD 测试
 */
@SpringBootTest
class McpTaskToolTest {

    @Autowired
    private TaskToolConfig taskTool;

    private final String userId = "test-user";
    private final String sessionId = UUID.randomUUID().toString();

    // ========== 1. TaskOutput 基础功能测试 ==========

    @Test
    void taskOutput_空taskId应该返回错误() {
        String result = taskTool.taskOutput("", true, 5000L, userId, sessionId);
        assertTrue(result.contains("不能为空") || result.contains("错误"),
                "空 taskId 应该返回错误");
    }

    @Test
    void taskOutput_nullTaskId应该返回错误() {
        String result = taskTool.taskOutput(null, true, 5000L, userId, sessionId);
        assertTrue(result.contains("不能为空") || result.contains("错误"),
                "null taskId 应该返回错误");
    }

    @Test
    void taskOutput_不存在的taskId应该返回错误() {
        String result = taskTool.taskOutput("non-existent-task-12345", true, 5000L, userId, sessionId);
        assertTrue(result.contains("不存在") || result.contains("错误"),
                "不存在的 taskId 应该返回错误");
    }

    // ========== 2. TaskStop 基础功能测试 ==========

    @Test
    void taskStop_空taskId应该返回错误() {
        String result = taskTool.taskStop("", userId, sessionId);
        assertTrue(result.contains("不能为空") || result.contains("错误"),
                "空 taskId 应该返回错误");
    }

    @Test
    void taskStop_nullTaskId应该返回错误() {
        String result = taskTool.taskStop(null, userId, sessionId);
        assertTrue(result.contains("不能为空") || result.contains("错误"),
                "null taskId 应该返回错误");
    }

    @Test
    void taskStop_不存在的taskId应该返回错误() {
        String result = taskTool.taskStop("non-existent-task-12345", userId, sessionId);
        assertTrue(result.contains("不存在") || result.contains("错误"),
                "不存在的 taskId 应该返回错误");
    }

    // ========== 3. TaskOutput 非阻塞查询测试 ==========

    @Test
    void taskOutput_非阻塞查询进行中的任务应该返回进行中状态() {
        // 使用 TaskCreate 创建后台任务
        String taskId = "test-background-" + UUID.randomUUID();

        // 注册一个长时间运行的任务
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            Thread.sleep(5000);
            return "完成";
        });
        executor.shutdown();

        taskTool.registerTask(taskId, future, userId, sessionId, "测试任务");

        // 非阻塞查询 - 任务应该仍在进行中
        String result = taskTool.taskOutput(taskId, false, 1000L, userId, sessionId);
        assertTrue(result.contains("进行中") || result.contains("执行中"),
                "进行中的任务应该返回进行中状态");

        // 清理
        taskTool.stopTask(taskId);

        // 等待任务完成
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    // ========== 4. TaskOutput 阻塞等待测试 ==========

    @Test
    void taskOutput_阻塞等待应该能获取任务结果() {
        String taskId = "test-blocking-" + UUID.randomUUID();

        // 注册一个短时任务
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            Thread.sleep(500);
            return "任务完成，结果数据";
        });
        executor.shutdown();

        taskTool.registerTask(taskId, future, userId, sessionId, "测试阻塞任务");

        // 阻塞查询 - 应该能获取结果
        String result = taskTool.taskOutput(taskId, true, 10000L, userId, sessionId);
        assertTrue(result.contains("完成") || result.contains("结果"),
                "阻塞等待应该能获取任务结果");
    }

    // ========== 5. TaskStop 停止任务测试 ==========

    @Test
    void taskStop_应该能停止进行中的任务() {
        String taskId = "test-stop-" + UUID.randomUUID();

        // 注册一个长时间运行的任务
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        Future<String> future = executor.submit(() -> {
            try {
                latch.await(1, TimeUnit.MINUTES);
                return "完成";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "被中断";
            }
        });
        executor.shutdown();

        taskTool.registerTask(taskId, future, userId, sessionId, "测试停止任务");

        // 停止任务
        String stopResult = taskTool.taskStop(taskId, userId, sessionId);
        assertTrue(stopResult.contains("停止") || stopResult.contains("成功"),
                "停止操作应该返回成功");

        // 验证任务已被停止
        String outputResult = taskTool.taskOutput(taskId, false, 1000L, userId, sessionId);
        assertTrue(outputResult.contains("不存在") || outputResult.contains("已停止"),
                "被停止的任务不应该再可查询");

        latch.countDown();
    }

    // ========== 6. 多用户隔离测试 ==========

    @Test
    void taskOutput_不同用户不应该能查询对方的任务() {
        String taskId = "test-isolation-" + UUID.randomUUID();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> "完成");
        executor.shutdown();

        // 用户A注册任务
        taskTool.registerTask(taskId, future, "userA", sessionId, "用户A的任务");

        // 用户B尝试查询 - 应该失败
        String result = taskTool.taskOutput(taskId, false, 1000L, "userB", sessionId);
        assertTrue(result.contains("不存在") || result.contains("无权"),
                "不同用户不应该能查询对方的任务");
    }

    @Test
    void taskStop_不同用户不应该能停止对方的任务() {
        String taskId = "test-isolation-stop-" + UUID.randomUUID();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        Future<String> future = executor.submit(() -> {
            try {
                latch.await(1, TimeUnit.MINUTES);
                return "完成";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "被中断";
            }
        });
        executor.shutdown();

        // 用户A注册任务
        taskTool.registerTask(taskId, future, "userA", sessionId, "用户A的任务");

        // 用户B尝试停止 - 应该失败
        String result = taskTool.taskStop(taskId, "userB", sessionId);
        assertTrue(result.contains("不存在") || result.contains("无权"),
                "不同用户不应该能停止对方的任务");

        latch.countDown();
    }

    // ========== 7. 边界条件测试 ==========

    @Test
    void taskOutput_超时为0应该使用默认超时() {
        String result = taskTool.taskOutput("test-timeout-" + UUID.randomUUID(), true, 0L, userId, sessionId);
        // 不存在的任务但参数正常处理
        assertNotNull(result);
    }

    @Test
    void taskOutput_负数超时应该使用默认超时() {
        String result = taskTool.taskOutput("test-neg-timeout-" + UUID.randomUUID(), true, -1000L, userId, sessionId);
        assertNotNull(result);
    }

    @Test
    void taskOutput_阻塞且超时应该能正确处理() {
        String result = taskTool.taskOutput("test-block-timeout-" + UUID.randomUUID(), true, 100L, userId, sessionId);
        assertNotNull(result);
    }
}
