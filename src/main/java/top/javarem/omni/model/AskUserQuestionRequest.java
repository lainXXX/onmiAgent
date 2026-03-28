package top.javarem.omni.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * 工具请求参数包装
 */
public class AskUserQuestionRequest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @JsonProperty("questions")
    private List<Question> questions;

    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    // Jackson 需要
    public AskUserQuestionRequest() {}

    public AskUserQuestionRequest(
            @JsonProperty("questions") List<Question> questions,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.questions = questions;
        this.metadata = metadata;
    }

    public List<Question> questions() {
        return questions;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    /**
     * 从 JSON 字符串反序列化
     */
    public static AskUserQuestionRequest fromJson(String json) {
        try {
            return objectMapper.readValue(json, AskUserQuestionRequest.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse AskUserQuestionRequest from JSON: " + json, e);
        }
    }
}
