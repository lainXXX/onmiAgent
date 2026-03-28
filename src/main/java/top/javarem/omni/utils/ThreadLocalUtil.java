package top.javarem.onmi.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用的 ThreadLocal 泛型上下文工具类
 */
public class ThreadLocalUtil {

    // 使用 withInitial 初始化一个 HashMap，避免 get 时报 NPE (空指针异常)
    private static final ThreadLocal<Map<String, Object>> THREAD_LOCAL = ThreadLocal.withInitial(HashMap::new);

    /**
     * 存入数据
     *
     * @param key   键
     * @param value 值 (泛型 T，支持任意类型)
     * @param <T>   泛型标识
     */
    public static <T> void set(String key, T value) {
        THREAD_LOCAL.get().put(key, value);
    }

    /**
     * 获取数据 (自动类型转换)
     *
     * @param key 键
     * @param <T> 泛型标识，接收方是什么类型，就会自动强转为什么类型
     * @return 返回对应的值，如果不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        Map<String, Object> map = THREAD_LOCAL.get();
        return (T) map.get(key);
    }

    /**
     * 获取数据并带有默认值，防止返回 null
     *
     * @param key          键
     * @param defaultValue 如果为空时的默认值
     * @param <T>          泛型标识
     * @return 实际值或默认值
     */
    @SuppressWarnings("unchecked")
    public static <T> T getOrDefault(String key, T defaultValue) {
        Map<String, Object> map = THREAD_LOCAL.get();
        Object value = map.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 移除单个键值对
     *
     * @param key 键
     */
    public static void remove(String key) {
        THREAD_LOCAL.get().remove(key);
    }

    /**
     * 🔥【极其重要】清空当前线程的所有数据
     * 在 Web 环境（Tomcat/Spring Boot）中，由于线程池复用，
     * 请求结束后如果不调用此方法，会导致内存泄漏和数据串位！
     */
    public static void clear() {
        THREAD_LOCAL.remove();
    }
}