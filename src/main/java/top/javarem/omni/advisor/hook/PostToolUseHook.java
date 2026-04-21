package top.javarem.omni.advisor.hook;

import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * 工具执行后 Hook
 *
 * <p>在每次工具调用后执行。
 * 可用于：
 * <ul>
 *   <li>记录日志</li>
 *   <li>修改响应</li>
 *   <li>触发后续操作</li>
 * </ul>
 */
public interface PostToolUseHook {

    /**
     * 在工具调用后执行
     *
     * @param response 当前响应
     * @return 修改后的响应
     */
    ChatClientResponse after(ChatClientResponse response);
}
