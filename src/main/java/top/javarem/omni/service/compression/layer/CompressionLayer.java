package top.javarem.omni.service.compression.layer;

import top.javarem.omni.model.compression.LayerResult;
import top.javarem.omni.service.compression.context.CompactionContext;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 压缩层接口
 *
 * <p>所有压缩层都实现此接口，按 order 顺序执行。
 * 压缩层应该是无状态的，每次调用都是独立的。
 */
public interface CompressionLayer {

    /**
     * 获取层序号 (1-4)
     */
    int getOrder();

    /**
     * 获取层名称
     */
    String getName();

    /**
     * 是否启用此层
     *
     * @param ctx 压缩上下文
     * @return true 启用，false 禁用
     */
    default boolean isEnabled(CompactionContext ctx) {
        return true;
    }

    /**
     * 快速检查是否应该跳过此层
     *
     * <p>用于避免不必要开销的快速检查。
     * 如果返回 true，则跳过此层，不执行 compress()。
     *
     * @param ctx 压缩上下文
     * @param messages 当前消息列表
     * @return true 跳过，false 执行
     */
    default boolean shouldSkip(CompactionContext ctx, List<Message> messages) {
        return false;
    }

    /**
     * 执行压缩
     *
     * @param ctx 压缩上下文
     * @param messages 当前消息列表
     * @return 压缩结果
     */
    LayerResult compress(CompactionContext ctx, List<Message> messages);
}
