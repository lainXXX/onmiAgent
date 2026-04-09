package top.javarem.omni.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.javarem.omni.model.skill.SkillError;
import top.javarem.omni.model.skill.SkillTelemetry;

/**
 * Skill 遥测服务
 * 混合模式: 日志 + 可选数据库
 */
@Slf4j
@Service
public class TelemetryService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${skill.telemetry.enabled:true}")
    private boolean enabled;

    @Value("${skill.telemetry.persist:false}")
    private boolean persist;

    @Value("${skill.telemetry.log-detail:full}")
    private String logDetail;

    public TelemetryService(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录 Skill 调用
     */
    public void record(String skillName, long startTime, boolean success, Integer errorCode) {
        long duration = System.currentTimeMillis() - startTime;

        if ("full".equals(logDetail)) {
            log.info("[SkillTelemetry] skill={}, duration={}ms, success={}, errorCode={}",
                skillName, duration, success, errorCode);
        } else if ("simple".equals(logDetail)) {
            log.debug("[SkillTelemetry] skill={}, {}ms, {}",
                skillName, duration, success ? "OK" : "FAIL");
        }

        if (persist && enabled) {
            persistTelemetry(skillName, duration, success, errorCode, null);
        }
    }

    /**
     * 记录错误
     */
    public void recordError(String skillName, long startTime, SkillError error) {
        long duration = System.currentTimeMillis() - startTime;

        if ("full".equals(logDetail)) {
            log.error("[SkillTelemetry] skill={}, duration={}ms, error={}",
                skillName, duration, error.toLogString());
        } else {
            log.error("[SkillTelemetry] skill={}, {}ms, FAIL",
                skillName, duration);
        }

        if (persist && enabled) {
            persistTelemetry(skillName, duration, false,
                error != null ? error.code() : SkillError.INTERNAL_ERROR,
                error != null ? error.message() : null);
        }
    }

    private void persistTelemetry(String skillName, long durationMs,
                                  boolean success, Integer errorCode, String errorMessage) {
        try {
            String sql = """
                INSERT INTO skill_telemetry
                (skill_name, source, execution_mode, invoked_at, duration_ms, success, error_code, error_message)
                VALUES (?, ?, ?, NOW(), ?, ?, ?, ?)
                """;

            jdbcTemplate.update(sql,
                skillName, "UNKNOWN", "INLINE",
                durationMs, success, errorCode, errorMessage);

        } catch (Exception e) {
            log.warn("[TelemetryService] 遥测数据写入失败", e);
        }
    }
}
