package top.javarem.omni.chat.entity;

/**
 * 消息类型枚举
 */
public enum MessageType {
    user,       // 用户消息
    assistant,  // 助手消息
    tool,       // 工具调用结果
    system      // 系统消息
}