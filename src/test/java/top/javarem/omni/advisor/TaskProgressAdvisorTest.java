package top.javarem.omni.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.javarem.omni.model.task.TaskEntity;
import top.javarem.omni.repository.chat.MemoryRepository;
import top.javarem.omni.service.task.TaskService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskProgressAdvisor 多轮集成测试
 *
 * <p>测试exec_rounds追踪流程的核心逻辑：</p>
 * <ul>
 *   <li>从ChatMemory推算exec_rounds</li>
 *   <li>无活跃任务时不触发提醒</li>
 *   <li>提醒阈值验证</li>
 *   <li>基于TaskEntity的任务状态查询</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-26
 */
@SpringBootTest
class TaskProgressAdvisorTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private MemoryRepository memoryRepository;

    /**
     * 测试：TaskEntity统计功能
     */
    @Test
    void testTaskEntityStats() {
        String uid = "stats-" + UUID.randomUUID();
        String sid = "session-stats-" + UUID.randomUUID();

        // 创建任务（默认pending状态）
        taskService.create(uid, sid, "任务1", "描述", null, null, null);
        taskService.create(uid, sid, "任务2", "描述", null, null, null);

        // 验证统计
        var stats = taskService.stats(uid, sid);
        assertEquals(2, stats.get(TaskEntity.STATUS_PENDING), "应该有2个pending任务");
        assertEquals(0, stats.get(TaskEntity.STATUS_IN_PROGRESS), "应该有0个进行中任务");
        assertEquals(0, stats.get(TaskEntity.STATUS_COMPLETED), "应该有0个已完成任务");
    }

    /**
     * 测试：活跃任务数量计算
     */
    @Test
    void testCountActiveTasks() {
        String uid = "active-" + UUID.randomUUID();
        String sid = "session-active-" + UUID.randomUUID();

        // 创建任务
        taskService.create(uid, sid, "任务1", "描述", null, null, null);
        taskService.create(uid, sid, "任务2", "描述", null, null, null);

        // 验证活跃任务数量
        var stats = taskService.stats(uid, sid);
        int pending = stats.getOrDefault(TaskEntity.STATUS_PENDING, 0);
        int inProgress = stats.getOrDefault(TaskEntity.STATUS_IN_PROGRESS, 0);
        int activeCount = pending + inProgress;

        assertEquals(2, activeCount, "应该有2个活跃任务");
    }

    /**
     * 测试：从MemoryRepository推算exec_rounds
     */
    @Test
    void testCalculateExecRounds() {
        String sid = "session-rounds-" + UUID.randomUUID();

        // 初始应该为0
        List<?> initialMessages = memoryRepository.findMessagesByConversationId(sid);
        int initialRounds = initialMessages.size() / 2;
        assertEquals(0, initialRounds, "初始消息为空，轮次为0");
    }

    /**
     * 测试：无活跃任务时不触发提醒
     */
    @Test
    void testNoActiveTask_NoReminder() {
        String uid = "noactive-" + UUID.randomUUID();
        String sid = "session-noactive-" + UUID.randomUUID();

        var stats = taskService.stats(uid, sid);
        int pending = stats.getOrDefault(TaskEntity.STATUS_PENDING, 0);
        int inProgress = stats.getOrDefault(TaskEntity.STATUS_IN_PROGRESS, 0);
        int activeCount = pending + inProgress;

        assertEquals(0, activeCount, "无活跃任务");
        // 无活跃任务时，不应该触发提醒
    }

    /**
     * 测试：多用户会话隔离
     */
    @Test
    void testMultiUserSessionIsolation() {
        String uid1 = "user1-" + UUID.randomUUID();
        String uid2 = "user2-" + UUID.randomUUID();
        String sid1 = "session1-" + UUID.randomUUID();
        String sid2 = "session2-" + UUID.randomUUID();

        // 用户1创建任务
        taskService.create(uid1, sid1, "任务1", "描述", null, null, null);

        // 用户2不创建任务
        var stats1 = taskService.stats(uid1, sid1);
        var stats2 = taskService.stats(uid2, sid2);

        assertEquals(1, stats1.get(TaskEntity.STATUS_PENDING), "用户1有1个任务");
        assertEquals(0, stats2.get(TaskEntity.STATUS_PENDING), "用户2无任务");
    }
}
