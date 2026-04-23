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
public class ChatSession {
    private String id;              // 会话ID
    private String userId;         // 用户ID
    private String headId;          // 当前链头ID
    private LocalDateTime createdAt; // 创建时间
}