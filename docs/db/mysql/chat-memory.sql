-- ============================================================================
-- Chat Memory 表 - 链表式消息存储（类 Git Commit 树）
-- ============================================================================
CREATE TABLE chat_memory (
    id              VARCHAR(64) NOT NULL COMMENT '消息唯一ID (UUID)',
    parent_id       VARCHAR(64) COMMENT '链表指针，指向上一条消息 (NULL=根节点)',
    session_id      VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id         VARCHAR(32) COMMENT '用户ID (多租户)',
    message_type    VARCHAR(32) NOT NULL COMMENT '消息类型: user/assistant/tool/system',
    content         TEXT COMMENT '消息内容',
    tool_call_id    VARCHAR(64) COMMENT '关联 tool_call 和 tool_result',
    tool_name       VARCHAR(64) COMMENT '工具名称',
    prompt_tokens   INT DEFAULT 0 COMMENT '输入 token 数',
    completion_tokens INT DEFAULT 0 COMMENT '输出 token 数',
    total_tokens    INT DEFAULT 0 COMMENT '本次总 token 数',
    error_code      VARCHAR(32) COMMENT '错误码',
    created_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_session_parent (session_id, parent_id) COMMENT '链表遍历',
    INDEX idx_session_type (session_id, message_type) COMMENT '按类型筛选',
    INDEX idx_session_created (session_id, created_at) COMMENT '时间排序',
    INDEX idx_tool_call (tool_call_id) COMMENT 'tool_call 关联'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='链表式消息存储，支持 Undo/Branch/Sidechain';

-- ============================================================================
-- Chat Session 表 - 会话状态 (记录当前 HEAD)
-- ============================================================================
CREATE TABLE chat_session (
    id              VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id         VARCHAR(32) COMMENT '用户ID',
    head_id         VARCHAR(64) NOT NULL COMMENT '当前链表头 (最后一条消息ID)',
    created_at      DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_user (user_id) COMMENT '按用户查会话列表'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话状态表，记录当前 HEAD';
