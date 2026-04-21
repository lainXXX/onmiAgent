package top.javarem.omni.chat.service;

public interface ContextCollapseService {

    // 检查是否需要折叠
    boolean shouldCollapse(String sessionId);

    // 执行折叠（插入摘要节点）
    void collapse(String sessionId, String summary);
}