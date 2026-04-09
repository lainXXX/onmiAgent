package top.javarem.omni.model.skill;

/**
 * Skill 调用遥测数据
 */
public record SkillTelemetry(
    Long id,
    String skillName,
    String source,
    String executionMode,
    Long invokedAt,
    Long durationMs,
    boolean success,
    Integer errorCode,
    String errorMessage,
    String argsHash
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String skillName;
        private String source;
        private String executionMode;
        private Long invokedAt;
        private Long durationMs;
        private boolean success;
        private Integer errorCode;
        private String errorMessage;
        private String argsHash;

        public Builder skillName(String skillName) {
            this.skillName = skillName;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder executionMode(String executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder invokedAt(Long invokedAt) {
            this.invokedAt = invokedAt;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorCode(Integer errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder argsHash(String argsHash) {
            this.argsHash = argsHash;
            return this;
        }

        public SkillTelemetry build() {
            return new SkillTelemetry(
                null, skillName, source, executionMode,
                invokedAt, durationMs, success, errorCode, errorMessage, argsHash
            );
        }
    }
}
