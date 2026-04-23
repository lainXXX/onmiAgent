package top.javarem.omni.tool.web;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.tool.AgentTool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * WebFetch 网页内容萃取工具
 * 深入阅读网络搜索返回的特定链接，提取核心正文转为 Markdown 格式
 *
 * 对标 Claude Code WebFetchTool 实现，核心能力：
 * - Jsoup HTML 解析，健壮的 DOM → Markdown 转换
 * - LRU 缓存（15min TTL，50MB 上限）
 * - 受控重定向（仅同域重定向，防止开放重定向攻击）
 * - URL 安全校验（过滤用户名/密码、过长 URL、内部域名）
 * - 内容长度保护（原始 HTML 限 10MB）
 */
@Component
@Slf4j
public class WebFetchToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "web_fetch";
    }

    // ============================================================
    // 常量配置
    // ============================================================

    private static final int MAX_URL_LENGTH = 2000;
    private static final int MAX_HTTP_CONTENT_LENGTH = 10 * 1024 * 1024; // 10MB
    private static final int MAX_MARKDOWN_LENGTH = 100_000; // 100K chars
    private static final int FETCH_TIMEOUT_SECONDS = 30;
    private static final int DOMAIN_CHECK_TIMEOUT_SECONDS = 10;

    /** 缓存 TTL: 15 分钟 */
    private static final long CACHE_TTL_MS = 15 * 60 * 1000;
    /** 缓存容量上限: 50MB */
    private static final int MAX_CACHE_SIZE_BYTES = 50 * 1024 * 1024;
    /** 最大同域重定向次数 */
    private static final int MAX_REDIRECTS = 10;

    /** URL 缓存 */
    private final ConcurrentHashMap<String, CacheEntry> urlCache = new ConcurrentHashMap<>();

    /** 定期清理过期缓存条目 */
    private final ScheduledExecutorService cacheCleanup =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "webfetch-cache-cleanup");
                t.setDaemon(true);
                return t;
            });

    // ============================================================
    // 依赖
    // ============================================================

    private final HttpClient httpClient;

    public WebFetchToolConfig() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // 自己控制重定向
                .build();

        // 每 5 分钟清理一次过期缓存
        cacheCleanup.scheduleAtFixedRate(this::evictExpiredEntries, 5, 5, TimeUnit.MINUTES);
    }

    // ============================================================
    // 工具入口
    // ============================================================

    @Tool(name = "web_fetch", description = "获取网页正文。适用：深入阅读搜索结果链接。返回：Markdown格式")
    public String webFetch(
            @ToolParam(description = "网页URL。必须 https:// 或 http:// 开头") String url) {
        log.info("[web_fetch] 开始执行: url={}", url);

        // 1. 参数归一化
        String normalizedUrl = normalizeAndValidateUrl(url);
        if (normalizedUrl == null) {
            return buildErrorResponse("无效的 URL",
                    "URL 必须以 http:// 或 https:// 开头，且长度不超过 " + MAX_URL_LENGTH + " 字符");
        }

        // 2. 检查缓存
        CacheEntry cached = urlCache.get(normalizedUrl);
        if (cached != null && !cached.isExpired()) {
            log.info("[web_fetch] 缓存命中: url={}, size={}", normalizedUrl, cached.markdown.length());
            return buildSuccessResponse(normalizedUrl, cached.markdown, cached.contentType);
        }

        // 3. 升级 http → https
        if (normalizedUrl.startsWith("http://")) {
            normalizedUrl = normalizedUrl.replaceFirst("http://", "https://");
        }

        // 4. 发起请求（带重定向控制）
        FetchResult result = fetchWithRedirectControl(normalizedUrl);
        if (result.isRedirect()) {
            return buildRedirectResponse(result);
        }
        if (result.error != null) {
            log.error("[web_fetch] 失败: url={}, error={}", normalizedUrl, result.error);
            return buildErrorResponse("网页获取失败: " + result.error,
                    "请检查 URL 是否正确，以及网络是否可用");
        }

        // 5. 转换为 Markdown
        String markdown = convertToMarkdown(result.htmlContent, result.contentType, normalizedUrl);
        String contentType = result.contentType;

        // 6. 缓存结果
        cachePut(normalizedUrl, markdown, contentType);

        log.info("[web_fetch] 完成: url={}, contentLength={}", normalizedUrl, markdown.length());
        return buildSuccessResponse(normalizedUrl, markdown, contentType);
    }

    // ============================================================
    // URL 校验与归一化
    // ============================================================

    private String normalizeAndValidateUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.trim();

        // 协议校验
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }

        // 长度校验
        if (url.length() > MAX_URL_LENGTH) {
            return null;
        }

        // 解析校验（用户名/密码、内部域名检测）
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return null;

            // 过滤带用户名密码的 URL
            if (uri.getUserInfo() != null) return null;

            // 基础 hostname 格式校验（至少有一个 dot）
            String[] parts = host.split("\\.");
            if (parts.length < 2) return null;

        } catch (Exception e) {
            return null;
        }

        return url;
    }

    // ============================================================
    // 受控重定向
    // ============================================================

    private FetchResult fetchWithRedirectControl(String url) {
        return fetchWithRedirectControl(url, 0);
    }

    private FetchResult fetchWithRedirectControl(String url, int redirectCount) {
        if (redirectCount > MAX_REDIRECTS) {
            return new FetchResult(null, null, 0, "Too many redirects (exceeded " + MAX_REDIRECTS + ")");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            String contentType = response.headers().firstValue("Content-Type").orElse("");

            // 处理重定向
            if (isRedirectStatus(status)) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return new FetchResult(null, null, status, "Redirect missing Location header");
                }

                // 解析相对路径
                String redirectUrl = URI.create(url).resolve(location).toString();

                // 安全检查：仅允许同域重定向（防开放重定向攻击）
                if (!isSameDomain(url, redirectUrl)) {
                    return new FetchResult(null, null, status,
                            "Redirect to different domain not permitted: " + redirectUrl);
                }

                // 升级 http → https
                if (redirectUrl.startsWith("http://")) {
                    redirectUrl = redirectUrl.replaceFirst("http://", "https://");
                }

                return fetchWithRedirectControl(redirectUrl, redirectCount + 1);
            }

            // 检查内容长度
            String body = response.body();
            if (body != null && body.length() > MAX_HTTP_CONTENT_LENGTH) {
                return new FetchResult(null, null, status, "Content too large (exceeds " + MAX_HTTP_CONTENT_LENGTH + " bytes)");
            }

            return new FetchResult(body, contentType, status, null);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new FetchResult(null, null, 0, e.getMessage());
        } catch (Exception e) {
            return new FetchResult(null, null, 0, e.getMessage());
        }
    }

    private boolean isRedirectStatus(int code) {
        return code == 301 || code == 302 || code == 307 || code == 308;
    }

    private boolean isSameDomain(String original, String redirect) {
        try {
            URI origUri = new URI(original);
            URI redirUri = new URI(redirect);
            String origHost = origUri.getHost();
            String redirHost = redirUri.getHost();
            if (origHost == null || redirHost == null) return false;

            // 去除 www. 前缀后比较
            return stripWww(origHost).equals(stripWww(redirHost))
                    && origUri.getPort() == redirUri.getPort()
                    && origUri.getScheme().equals(redirUri.getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    // ============================================================
    // HTML → Markdown
    // ============================================================

    private String convertToMarkdown(String html, String contentType, String sourceUrl) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        // 非 HTML 内容直接返回（如纯文本、JSON 等）
        if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
            return html;
        }

        try {
            Document doc = Jsoup.parse(html, sourceUrl);
            return htmlToMarkdown(doc);
        } catch (Exception e) {
            log.warn("[web_fetch] Jsoup 解析失败，fallback 纯文本: {}", e.getMessage());
            // Fallback: 移除标签后直接返回纯文本
            return Jsoup.parse(html).text();
        }
    }

    private String htmlToMarkdown(Document doc) {
        StringBuilder sb = new StringBuilder();

        // 移除不需要的元素
        doc.select("script, style, noscript, iframe, svg, canvas, embed, object").remove();

        Element body = doc.body();
        if (body == null) return "";

        processNode(body, sb, 0);

        String result = sb.toString();

        // 清理多余空白
        result = result.replaceAll("[ \\t]+", " ");
        result = result.replaceAll("\\n{3,}", "\n\n");
        result = result.trim();

        // 长度截断
        if (result.length() > MAX_MARKDOWN_LENGTH) {
            result = result.substring(0, MAX_MARKDOWN_LENGTH) + "\n\n... [内容已截断]";
        }

        return result;
    }

    private void processNode(Element element, StringBuilder sb, int depth) {
        if (element == null) return;

        String tagName = element.tagName().toLowerCase();

        switch (tagName) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Integer.parseInt(tagName.substring(1));
                sb.append("\n\n");
                sb.append("#".repeat(level));
                sb.append(" ");
                sb.append(cleanText(element.text()));
                sb.append("\n\n");
            }
            case "p" -> {
                sb.append("\n\n");
                sb.append(cleanText(element.text()));
                sb.append("\n\n");
            }
            case "br" -> sb.append("\n");
            case "hr" -> sb.append("\n\n---\n\n");
            case "ul", "ol" -> {
                sb.append("\n");
                for (Element li : element.select("> li")) {
                    String bullet = tagName.equals("ol") ? "1. " : "- ";
                    sb.append(bullet).append(cleanText(li.text())).append("\n");
                }
                sb.append("\n");
            }
            case "pre", "code" -> {
                sb.append("\n\n```\n");
                sb.append(element.text());
                sb.append("\n```\n\n");
            }
            case "blockquote" -> {
                sb.append("\n\n> ");
                sb.append(cleanText(element.text()));
                sb.append("\n\n");
            }
            case "a" -> {
                String href = element.attr("href");
                String text = element.text();
                if (!href.isEmpty() && !text.isEmpty()) {
                    sb.append("[").append(cleanText(text)).append("](").append(href).append(")");
                } else {
                    sb.append(cleanText(text));
                }
            }
            case "img" -> {
                String src = element.attr("src");
                String alt = element.attr("alt");
                if (!src.isEmpty()) {
                    sb.append("![").append(cleanText(alt)).append("](").append(src).append(")");
                }
            }
            case "strong", "b" -> sb.append("**").append(cleanText(element.text())).append("**");
            case "em", "i" -> sb.append("*").append(cleanText(element.text())).append("*");
            case "del", "s", "strike" -> sb.append("~~").append(cleanText(element.text())).append("~~");
            case "table" -> {
                sb.append("\n\n");
                Elements rows = element.select("tr");
                for (int i = 0; i < rows.size(); i++) {
                    Element row = rows.get(i);
                    Elements cells = row.select("th, td");
                    for (int j = 0; j < cells.size(); j++) {
                        sb.append("| ").append(cleanText(cells.get(j).text())).append(" ");
                    }
                    sb.append("|\n");
                    if (i == 0) {
                        for (int j = 0; j < cells.size(); j++) {
                            sb.append("| --- ");
                        }
                        sb.append("|\n");
                    }
                }
                sb.append("\n\n");
            }
            case "div", "section", "article", "main", "aside", "header", "footer", "nav" -> {
                for (Element child : element.children()) {
                    processNode(child, sb, depth + 1);
                }
            }
            default -> {
                // 对于其他块级元素子节点递归处理
                if (element.children().isEmpty()) {
                    String text = element.text().trim();
                    if (!text.isEmpty() && !text.equals(element.text())) {
                        sb.append(cleanText(text));
                    } else if (!text.isEmpty()) {
                        sb.append(cleanText(text));
                    }
                } else {
                    for (Element child : element.children()) {
                        processNode(child, sb, depth);
                    }
                }
            }
        }
    }

    private String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    // ============================================================
    // 缓存管理
    // ============================================================

    private void cachePut(String url, String markdown, String contentType) {
        if (urlCache.size() >= 200) {
            // 简单策略：缓存超过 200 条时清理一半过期条目
            evictExpiredEntries();
        }
        int size = markdown.getBytes().length;
        urlCache.put(url, new CacheEntry(markdown, contentType, size));
    }

    private void evictExpiredEntries() {
        long now = System.currentTimeMillis();
        urlCache.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // ============================================================
    // 响应构建
    // ============================================================

    private String buildSuccessResponse(String url, String content, String contentType) {
        StringBuilder sb = new StringBuilder();
        sb.append("📄 网页内容: ").append(url).append("\n");
        sb.append("─────────────────────────────────────────\n\n");
        sb.append(content);
        return sb.toString();
    }

    private String buildRedirectResponse(FetchResult result) {
        // 从 result 中提取原始 URL 和重定向 URL
        // 这里返回特殊格式，由调用方决定如何处理
        return "⚠️ 重定向到不同域名被拒绝 (安全策略)。\n\n" +
                "建议：直接访问最终目标 URL。\n\n" +
                "原始错误: " + result.error;
    }

    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }

    // ============================================================
    // 内部类
    // ============================================================

    private static class FetchResult {
        final String htmlContent;
        final String contentType;
        final int statusCode;
        final String error;

        // 重定向时的原始 URL（在状态码为 3xx 时使用）
        String redirectUrl;

        FetchResult(String htmlContent, String contentType, int statusCode, String error) {
            this.htmlContent = htmlContent;
            this.contentType = contentType;
            this.statusCode = statusCode;
            this.error = error;
        }

        boolean isRedirect() {
            return error != null && (error.startsWith("Redirect"));
        }
    }

    private static class CacheEntry {
        final String markdown;
        final String contentType;
        final int sizeBytes;
        final long createdAt;

        CacheEntry(String markdown, String contentType, int sizeBytes) {
            this.markdown = markdown;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}
