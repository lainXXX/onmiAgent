package top.javarem.skillDemo.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rerank 重排序配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.rerank")
public class RerankProperties {

    /**
     * 是否启用 Rerank
     */
    private boolean enabled = false;

    /**
     * API Key
     */
    @JsonProperty("api_key")
    private String apiKey;

    /**
     * Base URL
     */
    @JsonProperty("base_url")
    private String baseUrl = "https://api.siliconflow.cn";

    /**
     * 模型名称
     */
    private String model = "BAAI/bge-reranker-v2-m3";

    /**
     * 返回 Top N 条结果
     */
    @JsonProperty("top_n") // 关键点：映射为 API 要求的下划线命名
    private Integer topN = 5;

    /**
     * 是否返回文档内容
     */
    @JsonProperty("return_documents")
    private boolean returnDocuments = true;

    /**
     * 每个文档最大 chunk 数
     */
    @JsonProperty("max_chunks_per_doc")
    private Integer maxChunksPerDoc;

    /**
     * 相邻 chunk 的 token 重叠数
     */
    @JsonProperty("overlap_tokens")
    private Integer overlapTokens;
}
