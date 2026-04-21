package top.javarem.omni.tool;

/**
 * 工具接口
 */
public interface AgentTool {

    /**
     * 获取工具名称
     */
    String getName();

    /**
     * 是否可压缩
     * <p>
     * true: 工具结果可被清理（搜索/读取类：grep, read, glob 等）
     * false: 工具结果必须保留（写入类：bash, git, write 等）
     */
    default boolean isCompactable() {
        return true;
    }
}
