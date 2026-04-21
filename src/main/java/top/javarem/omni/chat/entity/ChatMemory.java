package top.javarem.omni.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMemory {
    private String id;              // UUID
    private String parentId;        // 链表指针
    private String sessionId;       // 会话ID
    private String userId;         // 用户ID
    private MessageType messageType; // 消息类型
    private String content;        // 消息内容
    private String toolCallId;     // 关联 tool_call
    private String toolName;       // 工具名称
    private Integer promptTokens;   // 输入 token
    private Integer completionTokens; // 输出 token
    private Integer totalTokens;   // 总 token
    private String errorCode;      // 错误码
    private LocalDateTime createdAt; // 创建时间
}