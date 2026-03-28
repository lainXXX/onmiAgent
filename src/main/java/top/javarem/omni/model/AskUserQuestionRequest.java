package top.javarem.omni.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 工具请求参数包装
 */
public class AskUserQuestionRequest {

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
}
