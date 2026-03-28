package top.javarem.onmi.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import top.javarem.onmi.config.ChunkingProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class MarkdownHeaderSplitter {

    // Markdown标题正则: # ## ### 等
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    private final RecursiveTextSplitter recursiveSplitter;
    private final ChunkingProperties properties;

    public MarkdownHeaderSplitter(RecursiveTextSplitter recursiveSplitter, ChunkingProperties properties) {
        this.recursiveSplitter = recursiveSplitter;
        this.properties = properties;
    }

    /**
     * 分割文档，保留Breadcrumbs
     */
    public List<Document> split(Document document) {
        String text = document.getText();
        List<Document> result = new ArrayList<>();

        if (!properties.isEnableStructureAware()) {
            // 不启用结构化切分，直接返回原文档
            result.add(document);
            return result;
        }

        // 查找所有标题
        List<HeaderMatch> headers = findHeaders(text);

        if (headers.isEmpty()) {
            // 无标题，使用递归分割
            List<String> chunks = recursiveSplitter.split(text);
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = new Document(
                        document.getId() + "_" + i,
                        chunks.get(i),
                        document.getMetadata()
                );
                chunk.getMetadata().put("chunk_index", i);
                chunk.getMetadata().put("breadcrumbs", "");
                result.add(chunk);
            }
        } else {
            // 有标题，按标题分段
            result.addAll(splitByHeaders(document, headers));
        }

        return result;
    }

    /**
     * 查找所有标题
     */
    private List<HeaderMatch> findHeaders(String text) {
        List<HeaderMatch> headers = new ArrayList<>();
        Matcher matcher = HEADER_PATTERN.matcher(text);

        while (matcher.find()) {
            String level = matcher.group(1);
            String title = matcher.group(2);
            headers.add(new HeaderMatch(level.length(), title, matcher.start()));
        }

        return headers;
    }

    /**
     * 按标题分割
     */
    private List<Document> splitByHeaders(Document document, List<HeaderMatch> headers) {
        List<Document> result = new ArrayList<>();
        String text = document.getText();

        for (int i = 0; i < headers.size(); i++) {
            HeaderMatch header = headers.get(i);
            int nextHeaderPos = (i + 1 < headers.size()) ? headers.get(i + 1).getPosition() : text.length();

            // 提取当前标题下的内容
            String content = text.substring(header.getPosition(), nextHeaderPos);

            // 提取Breadcrumbs
            String breadcrumbs = extractBreadcrumbs(headers.subList(0, i + 1));

            // 判断内容长度，决定处理方式
            String cleanedContent = recursiveSplitter.cleanText(content);
            int tokenCount = recursiveSplitter.split(cleanedContent).size() > 0
                    ? recursiveSplitter.split(cleanedContent).size() * properties.getParentChunkSize() / 2
                    : cleanedContent.length() / 2;

            if (tokenCount <= properties.getParentChunkSize()) {
                // 短内容直接返回
                Document chunk = new Document(
                        document.getId() + "_" + i,
                        content,
                        document.getMetadata()
                );
                chunk.getMetadata().put("chunk_index", i);
                if (properties.isEnableBreadcrumbs()) {
                    chunk.getMetadata().put("breadcrumbs", breadcrumbs);
                }
                result.add(chunk);
            } else {
                // 长内容触发递归分割
                List<String> subChunks = recursiveSplitter.split(cleanedContent);

                for (int j = 0; j < subChunks.size(); j++) {
                    Document chunk = new Document(
                            document.getId() + "_" + i + "_" + j,
                            subChunks.get(j),
                            document.getMetadata()
                    );
                    chunk.getMetadata().put("chunk_index", i);
                    chunk.getMetadata().put("sub_chunk_index", j);
                    if (properties.isEnableBreadcrumbs()) {
                        chunk.getMetadata().put("breadcrumbs", breadcrumbs);
                    }
                    result.add(chunk);
                }
            }
        }

        return result;
    }

    /**
     * 提取Breadcrumbs路径
     */
    private String extractBreadcrumbs(List<HeaderMatch> headers) {
        StringBuilder sb = new StringBuilder();
        for (HeaderMatch header : headers) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(header.getTitle());
        }
        return sb.toString();
    }

    /**
     * 标题匹配内部类
     */
    private static class HeaderMatch {
        private final int level;
        private final String title;
        private final int position;

        public HeaderMatch(int level, String title, int position) {
            this.level = level;
            this.title = title;
            this.position = position;
        }

        public int getLevel() { return level; }
        public String getTitle() { return title; }
        public int getPosition() { return position; }
    }
}
