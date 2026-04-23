package top.javarem.omni.model.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReadState 持有器
 * 管理所有文件的读取状态，用于 dedup 机制
 *
 * <p>支持两种存储方式：
 * <ul>
 *   <li>ToolContext Map 存储（默认）</li>
 *   <li>ThreadLocal 存储（当 ToolContext 不可变时降级使用）</li>
 * </ul>
 */
@Slf4j
public final class ReadStateHolder {

    // ThreadLocal 存储：解决 ToolContext 不可变时无法跨调用共享的问题
    private static final ThreadLocal<ReadStateHolder> THREAD_LOCAL_HOLDER = new ThreadLocal<>();

    private final Map<String, ReadState> stateMap = new ConcurrentHashMap<>();

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

    // ==================== ThreadLocal 管理 ====================

    /**
     * 设置当前线程的 ReadStateHolder
     * 在 SubAgentFactory 创建 ReAct 循环前调用
     */
    public static void setThreadLocal(ReadStateHolder holder) {
        THREAD_LOCAL_HOLDER.set(holder);
    }

    /**
     * 获取当前线程的 ReadStateHolder
     */
    public static ReadStateHolder getThreadLocal() {
        return THREAD_LOCAL_HOLDER.get();
    }

    /**
     * 清除当前线程的 ReadStateHolder
     * 在 ReAct 循环结束后调用
     */
    public static void clearThreadLocal() {
        THREAD_LOCAL_HOLDER.remove();
    }

    // ==================== 工厂方法 ====================

    /**
     * 获取或创建 holder
     *
     * <p>优先级：
     * <ol>
     *   <li>ThreadLocal（最高优先级，确保跨调用共享）</li>
     *   <li>ToolContext Map（次优先级）</li>
     *   <li>新建实例（兜底，保证工具可用）</li>
     * </ol>
     */
    public static ReadStateHolder fromContext(ToolContext context) {
        // 1. 优先从 ThreadLocal 获取（最高优先级）
        ReadStateHolder threadLocalHolder = THREAD_LOCAL_HOLDER.get();
        if (threadLocalHolder != null) {
            log.debug("[ReadStateHolder] 使用 ThreadLocal holder");
            return threadLocalHolder;
        }

        // 2. 从 ToolContext Map 获取（次优先级）
        if (context != null && context.getContext() != null) {
            Map<String, Object> contextMap = context.getContext();
            Object existing = contextMap.get(AdvisorContextConstants.READ_STATE_MAP);
            if (existing instanceof ReadStateHolder) {
                log.debug("[ReadStateHolder] 使用 ToolContext holder");
                return (ReadStateHolder) existing;
            }
        }

        // 3. 创建新的 holder（兜底）
        ReadStateHolder holder = new ReadStateHolder();

        // 4. 尝试存入 ToolContext（可能因不可变 Map 而失败）
        if (context != null && context.getContext() != null) {
            try {
                context.getContext().put(AdvisorContextConstants.READ_STATE_MAP, holder);
                log.debug("[ReadStateHolder] 新 holder 已存入 ToolContext");
            } catch (UnsupportedOperationException e) {
                log.warn("[ReadStateHolder] ToolContext Map 不可变，无法存入，依赖 ThreadLocal 方案");
            } catch (Exception e) {
                log.error("[ReadStateHolder] 存入 ToolContext 时发生异常", e);
            }
        }

        return holder;
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
