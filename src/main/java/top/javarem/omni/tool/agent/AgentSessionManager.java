package top.javarem.omni.tool.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话管理器
 * 管理子 Agent 的对话历史，支持 resume 机制
 */
@Slf4j
@Component
public class AgentSessionManager {

    /**
     * Agent 会话快照
     */
    public record AgentSession(
        String taskId,
        String agentType,
        String originalPrompt,
        List<Message> historyMessages,
        String lastOutput,
        int turnCount,
        long createdAt
    ) {}

    // taskId -> AgentSession
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    /**
     * 创建新会话
     */
    public void createSession(String taskId, String agentType, String prompt) {
        List<Message> initialMessages = new ArrayList<>();
        sessions.put(taskId, new AgentSession(
                taskId,
                agentType,
                prompt,
                initialMessages,
                null,
                0,
                System.currentTimeMillis()
        ));
        log.info("[AgentSessionManager] 创建会话: taskId={}, agentType={}", taskId, agentType);
    }

    /**
     * 添加用户消息到历史
     */
    public void addUserMessage(String taskId, String content) {
        AgentSession session = sessions.get(taskId);
        if (session != null) {
            List<Message> newHistory = new ArrayList<>(session.historyMessages());
            newHistory.add(new UserMessage(content));
            sessions.put(taskId, new AgentSession(
                    session.taskId(),
                    session.agentType(),
                    session.originalPrompt(),
                    newHistory,
                    session.lastOutput(),
                    session.turnCount(),
                    session.createdAt()
            ));
        }
    }

    /**
     * 添加助手消息到历史
     */
    public void addAssistantMessage(String taskId, String content) {
        AgentSession session = sessions.get(taskId);
        if (session != null) {
            List<Message> newHistory = new ArrayList<>(session.historyMessages());
            newHistory.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
            sessions.put(taskId, new AgentSession(
                    session.taskId(),
                    session.agentType(),
                    session.originalPrompt(),
                    newHistory,
                    content,
                    session.turnCount() + 1,
                    session.createdAt()
            ));
        }
    }

    /**
     * 更新最后输出
     */
    public void updateLastOutput(String taskId, String output) {
        AgentSession session = sessions.get(taskId);
        if (session != null) {
            sessions.put(taskId, new AgentSession(
                    session.taskId(),
                    session.agentType(),
                    session.originalPrompt(),
                    session.historyMessages(),
                    output,
                    session.turnCount(),
                    session.createdAt()
            ));
        }
    }

    /**
     * 获取会话
     */
    public AgentSession getSession(String taskId) {
        return sessions.get(taskId);
    }

    /**
     * 获取会话历史（用于恢复）
     */
    public List<Message> getHistory(String taskId) {
        AgentSession session = sessions.get(taskId);
        return session != null ? session.historyMessages() : new ArrayList<>();
    }

    /**
     * 构建恢复后的 prompt
     * 将历史消息与新的用户输入合并
     */
    public List<Message> buildResumePrompt(String taskId, String additionalPrompt) {
        List<Message> messages = new ArrayList<>();
        AgentSession session = sessions.get(taskId);

        if (session != null) {
            // 添加原始系统信息
            messages.add(new SystemMessage("[上下文恢复] 你正在继续一个未完成的任务。"));
            messages.add(new SystemMessage("原始任务: " + session.originalPrompt()));
            messages.add(new SystemMessage("当前进度: 已完成 " + session.turnCount() + " 轮对话"));

            // 添加历史消息
            messages.addAll(session.historyMessages());

            // 添加额外的用户输入
            if (additionalPrompt != null && !additionalPrompt.isBlank()) {
                messages.add(new UserMessage(additionalPrompt));
            }
        } else {
            // 找不到会话，返回额外输入作为新的用户消息
            if (additionalPrompt != null) {
                messages.add(new UserMessage(additionalPrompt));
            }
        }

        return messages;
    }

    /**
     * 删除会话
     */
    public void removeSession(String taskId) {
        sessions.remove(taskId);
        log.debug("[AgentSessionManager] 删除会话: taskId={}", taskId);
    }

    /**
     * 检查会话是否存在
     */
    public boolean sessionExists(String taskId) {
        return sessions.containsKey(taskId);
    }

    /**
     * 获取会话数量
     */
    public int getSessionCount() {
        return sessions.size();
    }
}
