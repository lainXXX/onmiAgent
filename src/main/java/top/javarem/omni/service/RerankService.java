package top.javarem.onmi.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import top.javarem.onmi.config.RerankProperties;
import top.javarem.onmi.model.rerank.RerankRequest;
import top.javarem.onmi.model.rerank.RerankResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rerank 重排序服务
 * 用于对向量检索结果进行二次精排，提升相关性
 */
@Slf4j
@Service
public class RerankService {

    private final RestClient restClient;
    private final RerankProperties properties;

    // 优化1：注入 Spring 自动配置的 RestClient.Builder，继承全局 HTTP 配置（如超时、代理）
    @Autowired
    public RerankService(RerankProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 核心：执行重排序，提取出通用的 API 调用逻辑
     */
    private List<RerankResponse.RerankResult> callRerankApi(String query, List<String> documents) {
        if (!properties.isEnabled() || documents == null || documents.isEmpty()) {
            return null;
        }
        String model = properties.getModel();
        log.info("执行 Rerank 精排 - Query: [{}], 文档数: {}", query, documents.size());

        try {

            RerankRequest.RerankRequestBuilder requestBuilder = RerankRequest.builder()
                    .model(properties.getModel())
                    .query(query)
                    .documents(documents)
                    .topN(properties.getTopN())
                    // 强烈建议将 properties.isReturnDocuments() 设为 false 以节省网络带宽
                    .returnDocuments(properties.isReturnDocuments())
                    .maxChunksPerDoc(properties.getMaxChunksPerDoc())
                    .overlapTokens(properties.getOverlapTokens());

            // 根据文档：只有 BGE 和 BCE 模型支持分块参数
            if (model.contains("bge-reranker") || model.contains("bce-reranker")) {
                requestBuilder.maxChunksPerDoc(properties.getMaxChunksPerDoc());
                requestBuilder.overlapTokens(properties.getOverlapTokens());
            }

            // 根据文档：只有 Qwen 模型支持指令参数
            if (model.contains("Qwen")) {
                requestBuilder.instruction("请根据查询相关性对文档进行重排序。");
            }

            RerankResponse response = restClient.post()
                    .uri("/v1/rerank") // 视不同厂商API调整
                    .body(requestBuilder.build())
                    .retrieve()
                    .body(RerankResponse.class);

            if (response != null && response.getResults() != null) {
                return response.getResults();
            }
        } catch (RestClientResponseException e) {
            // 优化2：精准捕获 HTTP 异常，打印接口返回的真实错误信息
            log.error("Rerank API 请求失败, HTTP Status: {}, Body: {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Rerank 发生未知异常: {}", e.getMessage(), e);
        }
        return null; // 发生异常时返回 null，交由上层降级处理
    }

    /**
     * 对外接口 1：执行重排序，返回重排序后的纯文档列表
     * @return 排序后的内容列表（支持降级）
     */
    public List<String> rerankAndGetDocuments(String query, List<String> documents) {
        List<RerankDocument> scoredDocs = rerankWithScore(query, documents);
        return scoredDocs.stream()
                .map(RerankDocument::getText)
                .collect(Collectors.toList());
    }

    /**
     * 对外接口 2：执行重排序，返回带分数的结果 (核心推荐方法)
     * @return 包含文本和分数的对象列表
     */
    public List<RerankDocument> rerankWithScore(String query, List<String> originalDocs) {
        // 1. 调用 API
        List<RerankResponse.RerankResult> results = callRerankApi(query, originalDocs);

        // 2. 容灾降级：如果 API 未开启或请求失败，直接返回原始文档（保留原有顺序，分数给个默认值）
        if (results == null || results.isEmpty()) {
            log.warn("Rerank 未返回结果，触发降级保护，返回原始文档列表");
            return fallbackOriginalDocs(originalDocs);
        }

        // 3. 结果映射 (优化3：基于 index 映射，不再强制依赖 API 返回庞大的 document text)
        return results.stream()
                .map(result -> {
                    // 优先从 originalDocs 依据 index 获取原文（极速、省带宽）
                    // 假设你的 RerankResult 有 getIndex() 方法，业界标准都有
                    String text;
                    if (result.getIndex() != null && result.getIndex() >= 0 && result.getIndex() < originalDocs.size()) {
                        text = originalDocs.get(result.getIndex());
                    } else if (result.getDocument() != null && result.getDocument().getText() != null) {
                        // 兜底：如果 API 没返回 index，但返回了 text
                        text = result.getDocument().getText();
                    } else {
                        text = "";
                    }

                    Double score = result.getRelevanceScore() != null ? result.getRelevanceScore() : 0.0;
                    return new RerankDocument(text, score);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取配置的 Rerank TopN 值
     * @param maxDocs 文档数量上限
     * @return 返回配置的 topN 与 maxDocs 的较小值
     */
    public int getRerankTopN(int maxDocs) {
        int configuredTopN = properties.getTopN();
        if (configuredTopN <= 0) {
            return maxDocs;
        }
        return Math.min(configuredTopN, maxDocs);
    }

    /**
     * 降级处理逻辑：将原始文档转化为带分数的对象（倒序递减假分数，保持原有顺序优势）
     */
    private List<RerankDocument> fallbackOriginalDocs(List<String> originalDocs) {
        List<RerankDocument> fallbackList = new ArrayList<>();
        // 截断到 TopN，防止将几百个文档全喂给大模型
        int limit = Math.min(originalDocs.size(), properties.getTopN());

        for (int i = 0; i < limit; i++) {
            // 给一个逐步递减的模拟分数，表示原始向量检索的顺序
            double fakeScore = 0.99 - (i * 0.01);
            fallbackList.add(new RerankDocument(originalDocs.get(i), Math.max(0.0, fakeScore)));
        }
        return fallbackList;
    }

    /**
     * 内部 DTO：重排序后的文档（包含内容和分数）
     */
    @Data
    @AllArgsConstructor
    public static class RerankDocument {
        private String text;
        private Double score;
    }
}