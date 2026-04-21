package top.javarem.omni.model.compression;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 单层压缩结果 DTO
 */
@Data
@Builder
public class LayerResult {

    /**
     * 层序号 (1-4)
     */
    private final int layerOrder;

    /**
     * 层名称
     */
    private final String layerName;

    /**
     * 压缩后的消息列表
     */
    private final List<Message> messages;

    /**
     * 释放的 token 数
     */
    private final long tokensFreed;

    /**
     * 清理的消息数
     */
    private final int messagesCleared;

    /**
     * 是否真正执行了压缩（false 表示跳过了）
     */
    private final boolean didWork;

    /**
     * 执行耗时
     */
    private final Duration executionTime;

    /**
     * 额外元数据
     */
    private final Map<String, Object> metadata;

    /**
     * 创建一个表示"跳过"的结果
     */
    public static LayerResult noOp(int layerOrder, String layerName) {
        return LayerResult.builder()
                .layerOrder(layerOrder)
                .layerName(layerName)
                .didWork(false)
                .tokensFreed(0)
                .messagesCleared(0)
                .executionTime(Duration.ZERO)
                .build();
    }
}
