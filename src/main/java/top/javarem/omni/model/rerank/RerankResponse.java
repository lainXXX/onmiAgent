package top.javarem.omni.model.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class RerankResponse {
    private String id;
    private List<RerankResult> results;
    private Meta meta;

    @Data
    public static class RerankResult {
        private Integer index;
        
        @JsonProperty("relevance_score")
        private Double relevanceScore;
        
        private Document document;
    }

    @Data
    public static class Document {
        private String text;
    }

    @Data
    public static class Meta {
        private Tokens tokens;
    }

    @Data
    public static class Tokens {
        @JsonProperty("input_tokens")
        private Integer inputTokens;
        @JsonProperty("output_tokens")
        private Integer outputTokens;
    }
}