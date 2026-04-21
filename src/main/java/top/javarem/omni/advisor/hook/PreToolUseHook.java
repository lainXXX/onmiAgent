package top.javarem.omni.advisor.hook;

import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * 工具执行前 Hook
 *
 * <p>在每次工具调用前执行。
 * 可用于：
 * <ul>
 *   <li>取消工具调用</li>
 *   <li>修改工具输入</li>
 *   <li>记录日志</li>
 * </ul>
 */
public interface PreToolUseHook {

    /**
     * 在工具调用前执行
     *
     * @param request 当前请求
     * @return 修改后的请求（返回 null 表示取消）
     */
    ChatClientRequest before(ChatClientRequest request);
}
