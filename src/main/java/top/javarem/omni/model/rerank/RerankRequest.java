package top.javarem.omni.model.rerank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // 极其重要：不发送为 null 的字段
public class RerankRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("query")
    private String query;

    @JsonProperty("documents")
    private List<String> documents;

    @JsonProperty("top_n")
    private Integer topN;

    @JsonProperty("return_documents")
    private Boolean returnDocuments;

    /**
     * 注意：仅 Qwen 系列模型支持该字段
     */
    @JsonProperty("instruction")
    private String instruction;

    /**
     * 注意：仅 BAAI/bge-reranker-v2-m3 和 网易/bce-reranker 支持
     */
    @JsonProperty("max_chunks_per_doc")
    private Integer maxChunksPerDoc;

    /**
     * 注意：仅 BAAI/bge-reranker-v2-m3 和 网易/bce-reranker 支持
     * 范围必须 <= 80
     */
    @JsonProperty("overlap_tokens")
    private Integer overlapTokens;
}
