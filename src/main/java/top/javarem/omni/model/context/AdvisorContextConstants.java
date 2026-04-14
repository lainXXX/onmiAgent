package top.javarem.omni.model.context;

public final class AdvisorContextConstants {

    public static final String SESSION_ID = "chat_memory_conversation_id";

    // 私有化构造函数，防止被实例化
    private AdvisorContextConstants() {}

    public static final String ENABLE_SKILL = "enable_skill";

    public static final String TOOL_CALL_PHASE = "advisor_tool_calling_phase";

    public static final String USER_ID = "userId";

    public static final String WORKSPACE = "workspace";

    /**
     * Read 工具的 dedup 状态存储
     * Key: 文件路径, Value: ReadState
     */
    public static final String READ_STATE_MAP = "read_state_map";

    /**
     * 标识消息是否为系统注入（非用户直接产生）
     * 用于区分普通对话上下文和系统注入的上下文
     */
    public static final String OMNI_INJECTED = "omni_injected";

    public enum Phase {
        INITIAL_REQUEST,    // 1. 初始请求阶段（第一次进来，还没问模型）
        MODEL_THINKING,     // 2. 模型思考中（已发出请求，等待模型决定是否要调工具）
        TOOL_EXECUTING,     // 3. 工具执行中（模型给出了指令，正在跑 Java 代码）
        FINALIZING,         // 4. 结果汇总阶段（模型拿到了所有结果，正在组织语言）
        COMPLETED           // 5. 终止状态（逻辑结束，准备返回结果给用户）
    }

}