-- Skill 遥测表 (可选，由 skill.telemetry.persist 控制)
CREATE TABLE IF NOT EXISTS skill_telemetry (
    id              BIGSERIAL PRIMARY KEY,
    skill_name      VARCHAR(128) NOT NULL,
    source          VARCHAR(32) NOT NULL,
    execution_mode  VARCHAR(16) NOT NULL,
    invoked_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    duration_ms     BIGINT,
    success         BOOLEAN NOT NULL,
    error_code      INT,
    error_message   TEXT,
    args_hash       VARCHAR(64),

    INDEX idx_skill_name (skill_name),
    INDEX idx_invoked_at (invoked_at),
    INDEX idx_success (success)
) COMMENT 'Skill 调用遥测表';
