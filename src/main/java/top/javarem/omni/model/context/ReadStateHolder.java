package top.javarem.omni.model.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReadState 持有器
 * 管理所有文件的读取状态，用于 dedup 机制
 *
 * <p>利用"不可变容器本身包含可变引用"的特性：
 * 在 SubAgentChatClientFactory.execute() 中预先创建 ConcurrentHashMap 作为容器，
 * 放入 toolContext 的 "READ_STATE_CONTAINER" key 下。
 * 即使 Spring AI 将 toolContext 封装为不可变 Map，
 * 我们依然可以通过容器引用修改内部状态。
 */
@Slf4j
public final class ReadStateHolder {

    private static final String STATE_CONTAINER_KEY = "READ_STATE_CONTAINER";

    private final Map<String, ReadState> stateMap;

    /**
     * 使用指定的状态 Map 创建 holder
     */
    public ReadStateHolder(Map<String, ReadState> stateMap) {
        this.stateMap = stateMap;
    }

    /**
     * 创建空 holder（兜底用）
     */
    public ReadStateHolder() {
        this.stateMap = new ConcurrentHashMap<>();
    }

    public ReadState get(String filePath) {
        return stateMap.get(normalizePath(filePath));
    }

    public void put(String filePath, ReadState state) {
        stateMap.put(normalizePath(filePath), state);
    }

    public void remove(String filePath) {
        stateMap.remove(normalizePath(filePath));
    }

    public void clear() {
        stateMap.clear();
    }

    // ==================== 工厂方法 ====================

    /**
     * 从 ToolContext 获取或创建 holder
     *
     * <p>实现逻辑：
     * <ol>
     *   <li>从 toolContext 中获取预创建的 stateContainer</li>
     *   <li>如果不存在（降级场景），创建新的 ConcurrentHashMap 作为兜底</li>
     * </ol>
     */
    public static ReadStateHolder fromContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            log.debug("[ReadStateHolder] context 为空，返回空 holder");
            return new ReadStateHolder();
        }

        Map<String, Object> contextMap = context.getContext();

        // 1. 获取预创建的 stateContainer
        @SuppressWarnings("unchecked")
        Map<String, ReadState> stateMap = (Map<String, ReadState>) contextMap.get(STATE_CONTAINER_KEY);

        if (stateMap != null) {
            log.debug("[ReadStateHolder] 使用 stateContainer，size={}", stateMap.size());
            return new ReadStateHolder(stateMap);
        }

        // 2. 降级：创建新的 Map 作为兜底
        log.debug("[ReadStateHolder] stateContainer 不存在，创建兜底 holder");
        Map<String, ReadState> fallbackMap = new ConcurrentHashMap<>();
        return new ReadStateHolder(fallbackMap);
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace("\\", "/");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
