-- H2 schema for testing
CREATE TABLE chat_memory (
    id              VARCHAR(64) NOT NULL PRIMARY KEY,
    parent_id       VARCHAR(64),
    session_id      VARCHAR(64) NOT NULL,
    user_id         VARCHAR(32),
    message_type    VARCHAR(32) NOT NULL,
    content         TEXT,
    tool_call_id    VARCHAR(64),
    tool_name       VARCHAR(64),
    prompt_tokens   INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    total_tokens    INT DEFAULT 0,
    error_code      VARCHAR(32),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_session (
    id              VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id         VARCHAR(32),
    head_id         VARCHAR(64),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_session_parent ON chat_memory(session_id, parent_id);
CREATE INDEX idx_session_type ON chat_memory(session_id, message_type);
CREATE INDEX idx_tool_call ON chat_memory(tool_call_id);
