package top.javarem.omni.chat.service;

import top.javarem.omni.chat.entity.ChatSession;

import java.util.List;

public interface ChatSessionService {

    // 创建会话
    ChatSession createSession(String userId);

    // 获取会话
    ChatSession getSession(String sessionId);

    // 用户会话列表
    List<ChatSession> getUserSessions(String userId);
}