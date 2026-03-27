package top.javarem.skillDemo.tool.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.skillDemo.tool.AgentTool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebFetch 网页内容萃取工具
 * 深入阅读网络搜索返回的特定链接
 * 提取核心正文转为 Markdown 格式
 */
@Component
@Slf4j
public class WebFetchToolConfig implements AgentTool {

    /**
     * 最大获取内容长度
     */
    private static final int MAX_CONTENT_LENGTH = 50000;

    private final HttpClient httpClient;

    public WebFetchToolConfig() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 获取网页内容
     *
     * @param url 目标网页链接
     * @return 网页正文内容（Markdown 格式）
     */
    @Tool(name = "web_fetch", description = "获取网页正文。适用：深入阅读搜索结果链接。返回：Markdown格式")
    public String webFetch(
            @ToolParam(description = "网页URL。必须https://或http://开头") String url) {
        log.info("[web_fetch] 开始执行: url={}", url);
        // 1. 参数校验
        if (url == null || url.trim().isEmpty()) {
            log.error("[web_fetch] 失败: URL为空");
            return buildErrorResponse("URL 不能为空", "请提供要获取的网页链接");
        }

        String normalizedUrl = url.trim();

        // 2. 验证 URL 格式
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            log.error("[web_fetch] 失败: 无效的URL格式 url={}", normalizedUrl);
            return buildErrorResponse("无效的 URL 格式",
                    "URL 必须以 http:// 或 https:// 开头");
        }

        // 3. 获取网页内容
        try {
            String html = fetchHtml(normalizedUrl);

            // 4. 转换为 Markdown
            String markdown = convertToMarkdown(html, normalizedUrl);

            log.info("[web_fetch] 完成: url={}, contentLength={}", normalizedUrl, markdown.length());
            return buildSuccessResponse(normalizedUrl, markdown);

        } catch (Exception e) {
            log.error("[web_fetch] 失败: url={}, error={}", normalizedUrl, e.getMessage(), e);
            return buildErrorResponse("网页获取失败: " + e.getMessage(),
                    "请检查 URL 是否正确，以及网络是否可用");
        }
    }

    /**
     * 获取 HTML 内容
     */
    private String fetchHtml(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP 请求失败，状态码: " + response.statusCode());
        }

        return response.body();
    }

    /**
     * 将 HTML 转换为 Markdown
     */
    private String convertToMarkdown(String html, String sourceUrl) {
        String content = html;

        // 1. 移除脚本和样式
        content = removeTags(content, "script");
        content = removeTags(content, "style");
        content = removeTags(content, "noscript");

        // 2. 移除注释
        content = regexReplace(content, "<!--.*?-->", "", Pattern.DOTALL);

        // 3. 处理标题
        content = processHeadings(content);

        // 4. 处理段落
        content = processParagraphs(content);

        // 5. 处理列表
        content = processLists(content);

        // 6. 处理代码块
        content = processCodeBlocks(content);

        // 7. 处理链接和图片
        content = processLinks(content);

        // 8. 处理强调
        content = processEmphasis(content);

        // 9. 清理多余空白
        content = cleanWhitespace(content);

        // 10. 限制长度
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
            content += "\n\n... [内容已截断]";
        }

        return content;
    }

    /**
     * 带标志的正则替换
     */
    private String regexReplace(String content, String pattern, String replacement, int flags) {
        Pattern p = Pattern.compile(pattern, flags);
        Matcher m = p.matcher(content);
        return m.replaceAll(replacement);
    }

    /**
     * 移除指定标签及其内容
     */
    private String removeTags(String content, String tagName) {
        // 移除开始标签到结束标签
        String pattern = "<" + tagName + "[^>]*>.*?</" + tagName + ">";
        content = regexReplace(content, pattern, "", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        // 移除自闭合标签
        pattern = "<" + tagName + "[^>]*/>";
        content = regexReplace(content, pattern, "", Pattern.CASE_INSENSITIVE);

        return content;
    }

    /**
     * 处理标题
     */
    private String processHeadings(String content) {
        for (int i = 1; i <= 6; i++) {
            String pattern = "<h" + i + "[^>]*>(.*?)</h" + i + ">";
            content = regexReplace(content, pattern, "\n\n#$1\n\n", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        }
        return content;
    }

    /**
     * 处理段落
     */
    private String processParagraphs(String content) {
        content = regexReplace(content, "<p[^>]*>(.*?)</p>", "\n\n$1\n\n", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        content = regexReplace(content, "<br[^>]*/?>", "\n", Pattern.CASE_INSENSITIVE);
        return content;
    }

    /**
     * 处理列表
     */
    private String processLists(String content) {
        // 无序列表
        content = regexReplace(content, "<li[^>]*>(.*?)</li>", "\n- $1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        content = regexReplace(content, "<ul[^>]*>", "\n", Pattern.CASE_INSENSITIVE);
        content = regexReplace(content, "</ul>", "\n", Pattern.CASE_INSENSITIVE);

        // 有序列表
        content = regexReplace(content, "<ol[^>]*>", "\n", Pattern.CASE_INSENSITIVE);
        content = regexReplace(content, "</ol>", "\n", Pattern.CASE_INSENSITIVE);

        return content;
    }

    /**
     * 处理代码块
     */
    private String processCodeBlocks(String content) {
        // 行内代码
        content = regexReplace(content, "<code[^>]*>(.*?)</code>", "`$1`", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        // 代码块
        content = regexReplace(content, "<pre[^>]*>(.*?)</pre>", "\n```\n$1\n```\n", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        return content;
    }

    /**
     * 处理链接
     */
    private String processLinks(String content) {
        // 提取链接文本和 URL
        content = regexReplace(content, "<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", "[$2]($1)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        // 处理图片
        content = regexReplace(content, "<img[^>]*src=[\"']([^\"']+)[\"'][^>]*alt=[\"']([^\"']+)[\"'][^>]*>", "![$2]($1)", Pattern.CASE_INSENSITIVE);
        content = regexReplace(content, "<img[^>]*src=[\"']([^\"']+)[\"'][^>]*>", "![]($1)", Pattern.CASE_INSENSITIVE);

        return content;
    }

    /**
     * 处理强调
     */
    private String processEmphasis(String content) {
        // 粗体
        content = regexReplace(content, "<strong[^>]*>(.*?)</strong>", "**$1**", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        content = regexReplace(content, "<b[^>]*>(.*?)</b>", "**$1**", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        // 斜体
        content = regexReplace(content, "<em[^>]*>(.*?)</em>", "*$1*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        content = regexReplace(content, "<i[^>]*>(.*?)</i>", "*$1*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        return content;
    }

    /**
     * 清理空白
     */
    private String cleanWhitespace(String content) {
        // 移除所有 HTML 标签
        content = content.replaceAll("<[^>]+>", "");

        // 解码 HTML 实体
        content = content.replaceAll("&nbsp;", " ");
        content = content.replaceAll("&amp;", "&");
        content = content.replaceAll("&lt;", "<");
        content = content.replaceAll("&gt;", ">");
        content = content.replaceAll("&quot;", "\"");
        content = content.replaceAll("&#39;", "'");
        content = content.replaceAll("&[a-z]+;", "");

        // 规范化空白
        content = content.replaceAll("[ \t]+", " ");
        content = content.replaceAll("\n{3,}", "\n\n");
        content = content.replaceAll(" \n", "\n");
        content = content.replaceAll("\n ", "\n");

        return content.trim();
    }

    /**
     * 构建成功响应
     */
    private String buildSuccessResponse(String url, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("📄 网页内容: ").append(url).append("\n\n");
        sb.append("─────────────────────────────────────────\n\n");
        sb.append(content);

        return sb.toString();
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String error, String suggestion) {
        return "❌ " + error + "\n\n" +
                "💡 建议: " + suggestion;
    }
}
