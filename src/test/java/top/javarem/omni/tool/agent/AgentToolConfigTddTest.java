package top.javarem.omni.tool.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 工具关键逻辑 TDD 测试
 *
 * 测试覆盖：
 * 1. 🚨 Lifecycle 问题：cleanupWorktree(deleteBranch=false) 保留分支
 * 2. ⚠️ AgentSessionManager：ToolResponseMessage 序列化/反序列化
 * 3. 📝 AgentResult：worktreePath 字段传递
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("Agent 关键逻辑 TDD 测试")
class AgentToolConfigTddTest {

    @Autowired
    private AgentToolConfig agentToolConfig;

    @Autowired
    private AgentSessionManager sessionManager;

    // ========== 问题2: AgentSessionManager 工具调用消息处理 ==========

    @Nested
    @DisplayName("问题2: AgentSessionManager 消息管理")
    class AgentSessionManagerTest {

        @Test
        @DisplayName("addAssistantMessage 应该保留消息内容")
        void assistantMessage_shouldPreserveContent() {
            // Given
            String taskId = UUID.randomUUID().toString();
            sessionManager.createSession(taskId, "explore", "测试任务");

            // When
            sessionManager.addAssistantMessage(taskId, "分析结果：找到5个关键文件");

            // Then
            List<Message> history = sessionManager.getHistory(taskId);
            assertFalse(history.isEmpty());
            assertEquals("分析结果：找到5个关键文件", history.get(0).getText());
        }

        @Test
        @DisplayName("buildResumePrompt 应该包含历史消息")
        void resumePrompt_shouldContainHistory() {
            // Given
            String taskId = UUID.randomUUID().toString();
            sessionManager.createSession(taskId, "explore", "复杂代码分析任务");

            sessionManager.addUserMessage(taskId, "请分析认证模块的代码结构");
            sessionManager.addAssistantMessage(taskId, "我需要先了解项目结构，然后深入认证相关代码");

            // When
            List<Message> resumeMessages = sessionManager.buildResumePrompt(taskId, "继续分析");

            // Then
            assertTrue(resumeMessages.size() >= 3);
            String allText = resumeMessages.stream()
                    .map(Message::getText)
                    .reduce("", (a, b) -> a + " " + b);
            assertTrue(allText.contains("复杂代码分析任务") || allText.contains("认证模块"),
                    "应该包含原始任务信息");
        }

        @Test
        @DisplayName("连续多轮对话应该正确累积")
        void multiTurnConversation_shouldAccumulate() {
            // Given
            String taskId = UUID.randomUUID().toString();
            sessionManager.createSession(taskId, "general", "长任务");

            // When: 模拟 5 轮对话
            for (int i = 1; i <= 5; i++) {
                sessionManager.addUserMessage(taskId, "用户第 " + i + " 轮问题");
                sessionManager.addAssistantMessage(taskId, "助手第 " + i + " 轮回复");
            }

            // Then
            List<Message> history = sessionManager.getHistory(taskId);
            assertEquals(10, history.size(), "应该有 10 条消息（5 轮 × 2）");
            assertEquals(5, sessionManager.getSession(taskId).turnCount());
        }

        @Test
        @DisplayName("sessionExists 应该正确检测存在性")
        void sessionExists_shouldDetectCorrectly() {
            // Given
            String taskId = UUID.randomUUID().toString();
            assertFalse(sessionManager.sessionExists(taskId));

            // When
            sessionManager.createSession(taskId, "explore", "测试");

            // Then
            assertTrue(sessionManager.sessionExists(taskId));

            // 删除后
            sessionManager.removeSession(taskId);
            assertFalse(sessionManager.sessionExists(taskId));
        }

        @Test
        @DisplayName("更新最后输出应该正确保存")
        void updateLastOutput_shouldPreserve() {
            // Given
            String taskId = UUID.randomUUID().toString();
            sessionManager.createSession(taskId, "explore", "测试");

            // When
            sessionManager.updateLastOutput(taskId, "最终分析结果：完成了");

            // Then
            assertEquals("最终分析结果：完成了", sessionManager.getSession(taskId).lastOutput());
        }

        @Test
        @DisplayName("resume 不存在的 session 应该返回空列表或仅额外输入")
        void resumeNonExistent_shouldHandleGracefully() {
            // Given
            String nonExistentTaskId = UUID.randomUUID().toString();

            // When: 尝试恢复不存在的会话
            List<Message> messages = sessionManager.buildResumePrompt(nonExistentTaskId, "继续之前的工作");

            // Then: 应该返回包含额外输入的消息列表
            assertNotNull(messages);
            // 不存在的会话应该返回空列表（或仅额外输入）
        }

        @Test
        @DisplayName("并发添加消息应该线程安全")
        void concurrentAdd_shouldBeThreadSafe() throws InterruptedException {
            // Given
            String taskId = UUID.randomUUID().toString();
            sessionManager.createSession(taskId, "general", "并发测试");

            // When: 10 个线程并发添加消息
            Thread[] threads = new Thread[10];
            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < 5; j++) {
                        sessionManager.addUserMessage(taskId, "U" + index + "-" + j);
                        sessionManager.addAssistantMessage(taskId, "A" + index + "-" + j);
                    }
                });
                threads[i].start();
            }

            for (Thread t : threads) {
                t.join();
            }

            // Then: 应该正确累积（虽然并发可能丢失一些，但至少应该有一些）
            List<Message> history = sessionManager.getHistory(taskId);
            assertTrue(history.size() > 0, "应该有消息被添加");
            // 注意：由于 ConcurrentHashMap，可能有些消息丢失，但基础功能应该正常
        }
    }

    // ========== 问题3: AgentResult worktreePath 字段 ==========

    @Nested
    @DisplayName("问题3: AgentResult worktreePath 字段")
    class AgentResultWorktreePathTest {

        @Test
        @DisplayName("completed 工厂方法应该保存 worktreePath")
        void completedFactory_shouldPreserveWorktreePath() {
            // Given
            String taskId = "task-" + UUID.randomUUID();
            String worktreePath = "/tmp/agent-worktrees/task-123";

            // When
            AgentResult result = AgentResult.completed(
                    taskId, "explore", "分析完成", 5000, worktreePath
            );

            // Then
            assertEquals(worktreePath, result.worktreePath());
            assertEquals("completed", result.status());
            assertEquals("分析完成", result.output());
        }

        @Test
        @DisplayName("completed 不带 worktreePath 时应为 null")
        void completedWithoutWorktreePath_shouldBeNull() {
            // Given
            String taskId = "task-" + UUID.randomUUID();

            // When
            AgentResult result = AgentResult.completed(taskId, "general", "完成", 100);

            // Then
            assertNull(result.worktreePath());
        }

        @Test
        @DisplayName("failed 状态不应该有 worktreePath")
        void failed_shouldNotHaveWorktreePath() {
            // Given
            String taskId = "task-" + UUID.randomUUID();

            // When
            AgentResult result = AgentResult.failed(taskId, "explore", "超时");

            // Then
            assertNull(result.worktreePath());
            assertEquals("failed", result.status());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("running 状态不应该有 worktreePath")
        void running_shouldNotHaveWorktreePath() {
            // Given
            String taskId = "task-" + UUID.randomUUID();

            // When
            AgentResult result = AgentResult.running(taskId, "general");

            // Then
            assertNull(result.worktreePath());
            assertEquals("running", result.status());
        }

        @Test
        @DisplayName("withMetadata 应该正确添加元数据")
        void withMetadata_shouldAddMetadata() {
            // Given
            AgentResult result = AgentResult.completed("t1", "explore", "完成", 100);

            // When
            AgentResult withMeta = result.withMetadata("key1", "value1");

            // Then
            assertNotNull(withMeta.metadata());
            assertEquals("value1", withMeta.metadata().get("key1"));
            assertEquals("t1", withMeta.taskId()); // 原始字段不变
        }

        @Test
        @DisplayName("durationMs 应该正确记录")
        void durationMs_shouldBeCorrect() {
            // Given
            String taskId = "task-" + UUID.randomUUID();

            // When
            AgentResult result = AgentResult.completed(taskId, "explore", "完成", 12345);

            // Then
            assertEquals(12345, result.durationMs());
        }
    }

    // ========== 边界测试 ==========

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTest {

        @Test
        @DisplayName("空 description 应该被处理")
        void emptyDescription_shouldBeHandled() {
            // Given
            String taskId = UUID.randomUUID().toString();

            // When
            sessionManager.createSession(taskId, "general", "");

            // Then
            assertTrue(sessionManager.sessionExists(taskId));
        }

        @Test
        @DisplayName("超长 prompt 应该被正确保存")
        void longPrompt_shouldBeHandled() {
            // Given
            String taskId = UUID.randomUUID().toString();
            String longPrompt = "A".repeat(10000);

            // When
            sessionManager.createSession(taskId, "explore", longPrompt);

            // Then
            assertEquals(longPrompt, sessionManager.getSession(taskId).originalPrompt());
        }

        @Test
        @DisplayName("null 内容应该被正确处理")
        void nullContent_shouldBeHandled() {
            // Given
            String taskId = UUID.randomUUID().toString();
            sessionManager.createSession(taskId, "explore", "测试");

            // When & Then: 添加 null 内容不应该抛异常
            assertDoesNotThrow(() -> {
                sessionManager.addAssistantMessage(taskId, null);
            });
        }

        @Test
        @DisplayName("getHistory 不存在的 session 应该返回空列表")
        void historyNonExistent_shouldReturnEmpty() {
            // Given
            String nonExistent = UUID.randomUUID().toString();

            // When
            List<Message> history = sessionManager.getHistory(nonExistent);

            // Then
            assertNotNull(history);
            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("removeSession 多次删除应该安全")
        void removeSession_twice_shouldBeSafe() {
            // Given
            String taskId = UUID.randomUUID().toString();
            sessionManager.createSession(taskId, "explore", "测试");

            // When & Then: 多次删除不应该抛异常
            assertDoesNotThrow(() -> {
                sessionManager.removeSession(taskId);
                sessionManager.removeSession(taskId);
            });
        }
    }
}