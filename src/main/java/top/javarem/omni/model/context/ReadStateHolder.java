package top.javarem.omni.model.context;

import org.springframework.ai.chat.model.ToolContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * ReadState 持有器
 * 管理所有文件的读取状态，用于 dedup 机制
 */
public final class ReadStateHolder {

    private final Map<String, ReadState> stateMap = new ConcurrentHashMap<>();

    /**
     * 获取文件的读取状态
     */
    public ReadState get(String filePath) {
        return stateMap.get(normalizePath(filePath));
    }

    /**
     * 存储文件的读取状态
     */
    public void put(String filePath, ReadState state) {
        stateMap.put(normalizePath(filePath), state);
    }

    /**
     * 移除文件的读取状态
     */
    public void remove(String filePath) {
        stateMap.remove(normalizePath(filePath));
    }

    /**
     * 清除所有状态
     */
    public void clear() {
        stateMap.clear();
    }

    /**
     * 获取或创建 holder
     */
    public static ReadStateHolder fromContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return new ReadStateHolder();
        }

        Object existing = context.getContext().get(AdvisorContextConstants.READ_STATE_MAP);
        if (existing instanceof ReadStateHolder) {
            return (ReadStateHolder) existing;
        }

        ReadStateHolder holder = new ReadStateHolder();
        context.getContext().put(AdvisorContextConstants.READ_STATE_MAP, holder);
        return holder;
    }

    /**
     * 归一化路径（用于 consistent key）
     */
    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        // 统一使用正斜杠，移除尾部斜杠
        String normalized = path.replace("\\", "/");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
