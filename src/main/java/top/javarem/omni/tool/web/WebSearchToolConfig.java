package top.javarem.omni.tool.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import top.javarem.omni.tool.AgentTool;

import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具
 * 按照 Spring AI Tool 开发规范实现 (Tavily 搜索引擎)
 */
@Component
@Slf4j
public class WebSearchToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Value("${spring.ai.tool.search.api-key:}")
    private String apiKey;

    @Value("${spring.ai.tool.search.max-results:5}")
    private int maxResults;

    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int MAX_TOTAL_LENGTH = 3000;

    // 优化 1：复用 RestClient 实例，避免每次调用都重新构建
    private final RestClient restClient;

    public WebSearchToolConfig(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.tavily.com")
                .build();
    }

    @Tool(name = "WebSearch", description = "在互联网上搜索实时信息、新闻、技术文档或事实。当本地知识库无法回答、需要最新信息或实时数据时调用此工具。")
    public String webSearch(
            @ToolParam(description = "搜索关键词或问题内容。建议使用具体的关键词而非完整句子，以获得更精准的结果") String query,
            @ToolParam(description = "最大返回结果数，防止返回过多内容。默认 5 条，最大 10 条", required = false) Integer maxResults,
            ToolContext context) {

        // 1. 归一化输入：剥离 Markdown 反引号
        String normalizedQuery = query != null ? query.replaceAll("`", "").trim() : "";

        // 优化 2：把 throw Exception 改为 return 字符串。
        // 让 Agent 能够“看见”报错，从而自主向用户解释，而不是让整个程序崩溃。
        if (normalizedQuery.isBlank()) {
            return "❌ 工具执行失败：搜索关键词不能为空。请重新思考并提供有效的搜索关键词。";
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.error("Tavily API Key 未配置！");
            return "❌ 工具执行失败：搜索引擎服务未配置 (缺少 API Key)。请直接告诉用户搜索功能暂时不可用。";
        }

        int limit = (maxResults != null && maxResults > 0) ? Math.min(maxResults, 10) : this.maxResults;

        try {
            log.info("[web_search] 开始执行: query={}", normalizedQuery);

            // 优化 3：使用 ParameterizedTypeReference 优雅处理复杂 JSON 响应，消除 Unchecked 警告
            Map<String, Object> response = restClient.post()
                    .uri("/search")
                    .body(Map.of(
                            "api_key", apiKey,
                            "query", normalizedQuery,
                            "search_depth", "basic",
                            "max_results", limit
                    ))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response == null || !response.containsKey("results")) {
                log.info("[web_search] 完成: query={}, 结果=0", normalizedQuery);
                return "⚠️ 搜索完成，但没有找到相关结果。建议尝试其他搜索词。";
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> results = (List<Map<String, String>>) response.get("results");

            if (results == null || results.isEmpty()) {
                log.info("[web_search] 完成: query={}, 结果=0", normalizedQuery);
                return "⚠️ 未找到与 '" + normalizedQuery + "' 相关的网络结果。";
            }

            String formattedResults = formatSearchResults(results);
            log.info("[web_search] 完成: query={}, 结果={}", normalizedQuery, results.size());

            return "🌐 互联网搜索结果（共 " + results.size() + " 条）：\n\n" + formattedResults;

        } catch (RestClientException e) {
            log.error("[web_search] 失败: query={}, error={}", normalizedQuery, e.getMessage(), e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "未知网络错误";

            if (errorMsg.contains("401")) {
                return "❌ 工具执行失败：搜索 API 认证失败。请告诉用户由于认证问题无法完成搜索。";
            }
            if (errorMsg.contains("timeout") || errorMsg.contains("Timeout")) {
                return "❌ 工具执行失败：搜索请求超时。建议重新尝试或精简搜索词。";
            }
            return "❌ 工具执行失败：网络错误 (" + errorMsg + ")。";

        } catch (Exception e) {
            log.error("联网搜索未知异常", e);
            return "❌ 工具执行失败：发生了未知错误，搜索中断。";
        }
    }

    /**
     * 格式化搜索结果（带截断）
     */
    private String formatSearchResults(List<Map<String, String>> results) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < results.size(); i++) {
            Map<String, String> res = results.get(i);
            String title = res.getOrDefault("title", "无标题");
            String url = res.getOrDefault("url", "");
            String content = res.getOrDefault("content", "");

            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "...";
            }

            sb.append(String.format("%d. **[%s](%s)**\n%s\n\n",
                    i + 1, title, url, content));

            if (sb.length() > MAX_TOTAL_LENGTH) {
                sb.append("[注：为防止内容过长，后续搜索结果已截断...]");
                break;
            }
        }

        return sb.toString();
    }
}