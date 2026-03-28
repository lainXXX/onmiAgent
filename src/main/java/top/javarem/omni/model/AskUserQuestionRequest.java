package top.javarem.omni.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;
import java.util.Map;

/**
 * 工具请求参数包装
 *
 * @param questions 1~4 个问题
 * @param metadata  大模型传来的内部元数据（如 source: "remember"）
 */
public record AskUserQuestionRequest(
    @ToolParam(description = "问题数组，支持多个问题（1~4个）")
    @JsonProperty(required = true)
    List<Question> questions,

    @ToolParam(description = "内部元数据")
    Map<String, Object> metadata
) {}
