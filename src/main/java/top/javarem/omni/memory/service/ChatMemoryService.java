package top.javarem.omni.chat.service;

import top.javarem.omni.chat.entity.ChatMemory;
import top.javarem.omni.chat.entity.MessageType;

import java.util.List;

public interface ChatMemoryService {

    // 保存消息
    ChatMemory saveMessage(String sessionId, MessageType type, String content,
                           String toolCallId, String toolName,
                           Integer promptTokens, Integer completionTokens);

    // 查询上下文
    List<ChatMemory> getContext(String sessionId);

    // 获取当前链头
    ChatMemory getCurrentHead(String sessionId);

    // 累计 Token
    int sumTokens(String sessionId);

    // Undo
    void undo(String sessionId);
}