package top.javarem.omni.repository.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ChatMemoryRepositoryTest {

    @Autowired
    ChatMemoryRepository repository;

    @Autowired
    ObjectMapper objectMapper;

    private String sid;

    @BeforeEach
    void setUp() {
        sid = "test-repo-" + System.currentTimeMillis() + "-" + counter.getAndIncrement();
    }

    private static final AtomicLong counter = new AtomicLong(0);

    // ==================== 基础类型测试 ====================

    @Test
    void saveAndRestore_userMessage() {
        repository.saveUserMessage(sid, "user1", new UserMessage("你好，我是用户"), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(1, ctx.size());
        assertInstanceOf(UserMessage.class, ctx.get(0));
        assertEquals("你好，我是用户", ctx.get(0).getText());
    }

    @Test
    void saveAndRestore_assistantMessage() {
        repository.saveAssistantMessage(sid, "user1", new AssistantMessage("你好，我是 AI"), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(1, ctx.size());
        assertInstanceOf(AssistantMessage.class, ctx.get(0));
        assertEquals("你好，我是 AI", ctx.get(0).getText());
    }

    @Test
    void saveAndRestore_assistantMessageWithToolCalls() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_123", "req_456", "bash", "{\"command\":\"ls\"}");
        AssistantMessage msg = AssistantMessage.builder()
                .content("调用 bash 工具")
                .toolCalls(List.of(tc))
                .build();
        repository.saveAssistantMessage(sid, "assistant", msg, null);
        List<Message> ctx = repository.getCleanContext(sid);
        AssistantMessage restored = (AssistantMessage) ctx.get(0);
        assertNotNull(restored.getToolCalls());
        assertEquals(1, restored.getToolCalls().size());
        assertEquals("call_123", restored.getToolCalls().get(0).id());
        assertEquals("bash", restored.getToolCalls().get(0).name());
        assertEquals("{\"command\":\"ls\"}", restored.getToolCalls().get(0).arguments());
    }

    @Test
    void saveAndRestore_toolResponseMessage() {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                "call_123", "bash", "{ \"output\": \"hello\" }");
        ToolResponseMessage msg = ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build();
        repository.saveToolResponseMessage(sid, "tool", msg, null);
        List<Message> ctx = repository.getCleanContext(sid);
        ToolResponseMessage restored = (ToolResponseMessage) ctx.get(0);
        assertEquals(1, restored.getResponses().size());
        assertEquals("call_123", restored.getResponses().get(0).id());
        assertEquals("bash", restored.getResponses().get(0).name());
    }

    @Test
    void saveAndRestore_systemMessage() {
        repository.saveSystemMessage(sid, "system", new SystemMessage("你是一个有帮助的助手"), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertInstanceOf(SystemMessage.class, ctx.get(0));
        assertEquals("你是一个有帮助的助手", ctx.get(0).getText());
    }

    @Test
    void saveAndRestore_multipleMessages_orderedByTime() {
        repository.saveUserMessage(sid, "user1", new UserMessage("第1句"), null);
        repository.saveAssistantMessage(sid, "assistant", new AssistantMessage("第2句"), null);
        repository.saveUserMessage(sid, "user1", new UserMessage("第3句"), null);
        repository.saveAssistantMessage(sid, "assistant", new AssistantMessage("第4句"), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(4, ctx.size());
        assertEquals("第1句", ctx.get(0).getText());
        assertEquals("第2句", ctx.get(1).getText());
        assertEquals("第3句", ctx.get(2).getText());
        assertEquals("第4句", ctx.get(3).getText());
    }

    @Test
    void saveAndRestore_withUsage() {
        repository.saveAssistantMessage(sid, "assistant", new AssistantMessage("带 token 信息的回复"), new Usage() {
            @Override public Integer getPromptTokens() { return 100; }
            @Override public Integer getCompletionTokens() { return 50; }
            @Override public Integer getTotalTokens() { return 150; }
            @Override public Object getNativeUsage() { return null; }
        });
        List<Message> ctx = repository.getCleanContext(sid);
        AssistantMessage restored = (AssistantMessage) ctx.get(0);
        assertNotNull(restored.getMetadata());
        assertEquals(100, restored.getMetadata().get("promptTokens"));
        assertEquals(50, restored.getMetadata().get("completionTokens"));
        assertEquals(150, restored.getMetadata().get("totalTokens"));
    }

    @Test
    void undo_removeLastMessage() {
        repository.saveUserMessage(sid, "user1", new UserMessage("待撤销"), null);
        repository.saveAssistantMessage(sid, "assistant", new AssistantMessage("撤销后的回复"), null);
        assertEquals(2, repository.getCleanContext(sid).size());
        repository.undo(sid);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(1, ctx.size());
        assertEquals("待撤销", ctx.get(0).getText());
    }

    // ==================== 边界情况测试 ====================

    @Test
    void emptyContent_userMessage() {
        repository.saveUserMessage(sid, "user1", new UserMessage(""), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(1, ctx.size());
        assertEquals("", ctx.get(0).getText());
    }

    @Test
    void emptyContent_assistantMessage() {
        repository.saveAssistantMessage(sid, "assistant", new AssistantMessage(""), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(1, ctx.size());
        assertEquals("", ctx.get(0).getText());
    }

    @Test
    void specialCharacters_emojiAndChinese() {
        String emoji = "😀🎉🔥 Hello 你好 🌍";
        String special = "特殊字符: @#$%^&*()_+-=[]{}|;':\",./<>?`~";
        repository.saveUserMessage(sid, "user1", new UserMessage(emoji + "\n" + special), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(emoji + "\n" + special, ctx.get(0).getText());
    }

    @Test
    void veryLongContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是一条很长的消息内容，用于测试超长文本的存储和还原。");
        }
        String longContent = sb.toString();
        repository.saveAssistantMessage(sid, "assistant", new AssistantMessage(longContent), null);
        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(longContent, ctx.get(0).getText());
        assertTrue(ctx.get(0).getText().length() > 1000);
    }

    @Test
    void multipleToolCalls_inOneMessage() {
        AssistantMessage.ToolCall tc1 = new AssistantMessage.ToolCall("call_1", "req_1", "bash", "{\"cmd\":\"ls\"}");
        AssistantMessage.ToolCall tc2 = new AssistantMessage.ToolCall("call_2", "req_1", "read", "{\"path\":\"/tmp/a\"}");
        AssistantMessage.ToolCall tc3 = new AssistantMessage.ToolCall("call_3", "req_1", "write", "{\"path\":\"/tmp/b\",\"content\":\"hi\"}");
        AssistantMessage msg = AssistantMessage.builder()
                .content("我需要执行三个工具")
                .toolCalls(List.of(tc1, tc2, tc3))
                .build();
        repository.saveAssistantMessage(sid, "assistant", msg, null);
        List<Message> ctx = repository.getCleanContext(sid);
        AssistantMessage restored = (AssistantMessage) ctx.get(0);
        assertEquals(3, restored.getToolCalls().size());
        assertEquals("call_1", restored.getToolCalls().get(0).id());
        assertEquals("call_2", restored.getToolCalls().get(1).id());
        assertEquals("call_3", restored.getToolCalls().get(2).id());
    }

    @Test
    void toolResponseError() {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                "call_err", "bash", "{\"error\": \"command not found\", \"exitCode\": 127}");
        ToolResponseMessage msg = ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build();
        repository.saveToolResponseMessage(sid, "tool", msg, null);
        List<Message> ctx = repository.getCleanContext(sid);
        ToolResponseMessage restored = (ToolResponseMessage) ctx.get(0);
        assertTrue(restored.getResponses().get(0).responseData().contains("error"));
    }

    @Test
    void consecutiveUndo_multipleTimes() {
        repository.saveUserMessage(sid, "user1", new UserMessage("第1条"), null);
        repository.saveUserMessage(sid, "user1", new UserMessage("第2条"), null);
        repository.saveUserMessage(sid, "user1", new UserMessage("第3条"), null);
        repository.saveUserMessage(sid, "user1", new UserMessage("第4条"), null);
        assertEquals(4, repository.getCleanContext(sid).size());

        repository.undo(sid);
        assertEquals(3, repository.getCleanContext(sid).size());
        assertEquals("第3条", repository.getCleanContext(sid).get(2).getText());

        repository.undo(sid);
        assertEquals(2, repository.getCleanContext(sid).size());
        assertEquals("第2条", repository.getCleanContext(sid).get(1).getText());

        repository.undo(sid);
        assertEquals(1, repository.getCleanContext(sid).size());
        assertEquals("第1条", repository.getCleanContext(sid).get(0).getText());
    }

    @Test
    void undo_onEmptySession_noError() {
        repository.undo("non-existent-session-xyz");
    }

    @Test
    void getCleanContext_nonExistentSession_emptyList() {
        List<Message> ctx = repository.getCleanContext("non-existent-session-xyz");
        assertNotNull(ctx);
        assertTrue(ctx.isEmpty());
    }

    @Test
    void completeConversation_roundTrip() {
        repository.saveUserMessage(sid, "user1", new UserMessage("帮我查一下明天的天气"), null);
        repository.saveAssistantMessage(sid, "assistant",
                AssistantMessage.builder()
                        .content("我来帮你查询天气")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("tc_1", "req_1", "weather", "{\"city\":\"北京\"}")))
                        .build(), null);
        repository.saveToolResponseMessage(sid, "tool",
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("tc_1", "weather", "{\"temp\":15,\"condition\":\"晴\"}")))
                        .build(), null);
        repository.saveAssistantMessage(sid, "assistant",
                new AssistantMessage("明天北京天气晴，气温15度，非常适合出行！"), null);

        List<Message> ctx = repository.getCleanContext(sid);
        assertEquals(4, ctx.size());
        assertInstanceOf(UserMessage.class, ctx.get(0));
        assertEquals("帮我查一下明天的天气", ctx.get(0).getText());

        AssistantMessage aiWithTool = (AssistantMessage) ctx.get(1);
        assertEquals(1, aiWithTool.getToolCalls().size());
        assertEquals("weather", aiWithTool.getToolCalls().get(0).name());

        ToolResponseMessage toolResp = (ToolResponseMessage) ctx.get(2);
        assertEquals("tc_1", toolResp.getResponses().get(0).id());

        assertInstanceOf(AssistantMessage.class, ctx.get(3));
        assertEquals("明天北京天气晴，气温15度，非常适合出行！", ctx.get(3).getText());
    }

    @Test
    void assistantMessage_withToolCallId() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_abc", "req_xyz", "file_read", "{\"path\":\"/etc/hosts\"}");
        AssistantMessage msg = AssistantMessage.builder()
                .content("读取文件")
                .toolCalls(List.of(tc))
                .build();
        repository.saveAssistantMessage(sid, "assistant", msg, null);
        List<Message> ctx = repository.getCleanContext(sid);
        AssistantMessage restored = (AssistantMessage) ctx.get(0);
        assertEquals("call_abc", restored.getToolCalls().get(0).id());
        assertEquals("file_read", restored.getToolCalls().get(0).name());
    }

    @Test
    void sessionIsolation_completelySeparate() {
        String sid1 = "isolate-A-" + System.currentTimeMillis();
        String sid2 = "isolate-B-" + System.currentTimeMillis();

        repository.saveUserMessage(sid1, "user1", new UserMessage("会话A的消息"), null);
        repository.saveUserMessage(sid2, "user2", new UserMessage("会话B的消息"), null);
        repository.saveUserMessage(sid1, "user1", new UserMessage("会话A第二条"), null);

        List<Message> ctx1 = repository.getCleanContext(sid1);
        List<Message> ctx2 = repository.getCleanContext(sid2);

        assertEquals(2, ctx1.size());
        assertEquals(1, ctx2.size());
        assertEquals("会话A的消息", ctx1.get(0).getText());
        assertEquals("会话B的消息", ctx2.get(0).getText());

        repository.undo(sid1);
        assertEquals(1, repository.getCleanContext(sid1).size());
        assertEquals(1, repository.getCleanContext(sid2).size());
    }

    @Test
    void zeroTokens_Usage() {
        repository.saveAssistantMessage(sid, "assistant", new AssistantMessage("零token"), new Usage() {
            @Override public Integer getPromptTokens() { return 0; }
            @Override public Integer getCompletionTokens() { return 0; }
            @Override public Integer getTotalTokens() { return 0; }
            @Override public Object getNativeUsage() { return null; }
        });
        List<Message> ctx = repository.getCleanContext(sid);
        AssistantMessage restored = (AssistantMessage) ctx.get(0);
        assertNull(restored.getMetadata().get("promptTokens"));
    }
}