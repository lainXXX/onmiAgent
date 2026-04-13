package top.javarem.omni.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式输出的数据块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatChunk {
    /**
     * 块 ID，用于前端对应时间线节点（同一阶段保持一致）
     */
    private String id;

    /**
     * 文本内容
     */
    private String content;

    /**
     * 角色类型: thought, tool, message, error
     */
    private String role;

    /**
     * 工具名称（仅 tool 事件时使用）
     */
    private String toolName;

    /**
     * 是否为最后一块
     */
    private boolean done;
}
