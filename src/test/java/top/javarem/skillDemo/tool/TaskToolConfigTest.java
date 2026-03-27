package top.javarem.skillDemo.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TaskToolConfigTest {

    @Autowired
    private TaskToolConfig tool;

    private final String userId = "test-user";
    private final String sessionId = UUID.randomUUID().toString();

    @Test
    void testCreateAndList() {
        // 创建任务
        String result = tool.taskCreate("测试任务", "这是一个测试", "high",
            null, null, userId, sessionId);
        assertTrue(result.contains("创建成功"));

        // 查询列表
        result = tool.taskList(userId, sessionId, null, 1, 20, "markdown");
        assertTrue(result.contains("测试任务"));
    }

}
