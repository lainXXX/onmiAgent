package top.javarem.omni.model;

public enum AgentFinishStatus {
    TOOL_TOOLS("tool_calls"),
    STOP("stop"),
    LENGTH("length"),
    UNKNOWN("unknown");

    private final String value;
    AgentFinishStatus(String value) { this.value = value; }

    public static AgentFinishStatus from(String reason) {
        for (AgentFinishStatus status : values()) {
            if (status.value.equalsIgnoreCase(reason)) return status;
        }
        return UNKNOWN;
    }
}