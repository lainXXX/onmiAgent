package top.javarem.omni.model.compression;

/**
 * Context Collapse 状态枚举
 *
 * <p>用于跟踪上下文折叠的状态机：
 * <ul>
 *   <li>NORMAL - 正常使用，小于 90% 阈值</li>
 *   <li>COMMIT - 达到 90%，进入提交模式，生成折叠视图</li>
 *   <li>BLOCK - 达到 95%，阻塞新 API 调用，等待 AutoCompact</li>
 * </ul>
 */
public enum CollapseState {

    /**
     * 正常模式 - 上下文使用量低于 90%
     */
    NORMAL,

    /**
     * 提交模式 - 上下文使用量超过 90%，开始生成折叠视图
     */
    COMMIT,

    /**
     * 阻塞模式 - 上下文使用量超过 95%，阻塞新 API 调用
     */
    BLOCK
}
