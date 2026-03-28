package top.javarem.omni.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 任务管理表初始化器
 * 在应用启动时创建 ai_tasks 表（如果不存在）
 */
@Slf4j
@Component
public class TaskTableInitializer {

    @Autowired
    @Qualifier("mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @PostConstruct
    public void init() {
        log.info("[TaskTableInitializer] 检查并创建 ai_tasks 表...");
        try {
            // 检查表是否存在
            String checkSql = "SHOW TABLES LIKE 'ai_tasks'";
            boolean tableExists = mysqlJdbcTemplate.queryForList(checkSql).size() > 0;

            if (tableExists) {
                log.info("[TaskTableInitializer] ai_tasks 表已存在，检查并添加新列...");
                migrateTable();
            } else {
                log.info("[TaskTableInitializer] ai_tasks 表不存在，开始创建...");
                createTable();
            }
        } catch (Exception e) {
            log.error("[TaskTableInitializer] 初始化 ai_tasks 表失败: {}", e.getMessage(), e);
        }
    }

    private void createTable() {
        mysqlJdbcTemplate.execute("CREATE TABLE ai_tasks (" +
                "id CHAR(36) PRIMARY KEY," +
                "user_id VARCHAR(64) NOT NULL," +
                "session_id VARCHAR(64) NOT NULL," +
                "subject VARCHAR(256) NOT NULL," +
                "description TEXT," +
                "status VARCHAR(32) DEFAULT 'pending'," +
                "active_form VARCHAR(128)," +
                "owner VARCHAR(64)," +
                "result TEXT," +
                "last_heartbeat DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "loop_count INT DEFAULT 0," +
                "remaining_work TEXT," +
                "metadata TEXT," +
                "blocks TEXT," +
                "blocked_by TEXT," +
                "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "INDEX idx_user_session (user_id, session_id)," +
                "INDEX idx_status (status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        log.info("[TaskTableInitializer] ai_tasks 表创建成功");
    }

    private void migrateTable() {
        try {
            // 添加新列（如果不存在）
            String alterActiveForm = "ALTER TABLE ai_tasks ADD COLUMN IF NOT EXISTS active_form VARCHAR(128)";
            String alterOwner = "ALTER TABLE ai_tasks ADD COLUMN IF NOT EXISTS owner VARCHAR(64)";
            String alterBlocks = "ALTER TABLE ai_tasks ADD COLUMN IF NOT EXISTS blocks TEXT";
            String alterBlockedBy = "ALTER TABLE ai_tasks ADD COLUMN IF NOT EXISTS blocked_by TEXT";

            mysqlJdbcTemplate.execute(alterActiveForm);
            mysqlJdbcTemplate.execute(alterOwner);
            mysqlJdbcTemplate.execute(alterBlocks);
            mysqlJdbcTemplate.execute(alterBlockedBy);

            log.info("[TaskTableInitializer] 表结构迁移完成");
        } catch (Exception e) {
            log.warn("[TaskTableInitializer] 表迁移失败或列已存在: {}", e.getMessage());
        }
    }
}
