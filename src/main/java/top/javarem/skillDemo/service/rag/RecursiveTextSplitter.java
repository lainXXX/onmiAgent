package top.javarem.skillDemo.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import top.javarem.skillDemo.config.ChunkingProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RecursiveTextSplitter {

    private final TokenCounter tokenCounter;
    private final ChunkingProperties properties;

    // 表格和代码块的正则
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "^\\|.*\\|$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```[\\s\\S]*?```", Pattern.MULTILINE);
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile(
            "`[^`]+`");

    public RecursiveTextSplitter(TokenCounter tokenCounter, ChunkingProperties properties) {
        this.tokenCounter = tokenCounter;
        this.properties = properties;
        log.info("RecursiveTextSplitterV2 initialized: parentSize={}, childSize={}, parentOverlap={}, childOverlap={}",
                properties.getParentChunkSize(), properties.getChildChunkSize(),
                properties.getParentOverlap(), properties.getChildOverlap());
    }

    /**
     * 递归分割文本 (母块) - 返回字符串列表
     */
    public List<String> split(String text) {
        String cleaned = cleanText(text);
        List<String> chunks = splitRecursive(cleaned, 0, properties.getParentChunkSize(),
                properties.getParentOverlap(), new ArrayList<>(), 0);
        // 后处理：智能合并小碎片，直接传入 maxSize 作为上限
        return mergeSmallChunks(chunks, properties.getParentChunkSize() / 4, properties.getParentChunkSize());
    }

    /**
     * 递归分割文本 (母块) - 返回Spring AI Document列表
     * @param text 待分割文本
     * @param sourceFile 来源文件
     */
    public List<Document> splitToDocuments(String text, String sourceFile) {
        List<String> chunkTexts = split(text);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunkTexts.size(); i++) {
            String content = chunkTexts.get(i);
            String id = UUID.randomUUID().toString();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sourceFile", sourceFile);
            metadata.put("chunkIndex", i);
            metadata.put("chunkType", "parent");

            Document doc = new Document(id, content, metadata);
            documents.add(doc);
        }

        log.debug("分割文本为 {} 个Document，来源: {}", documents.size(), sourceFile);
        return documents;
    }

    /**
     * 递归分割文本 (子块) - 返回字符串列表
     */
    public List<String> splitChild(String text) {
        String cleaned = cleanText(text);
        List<String> chunks = splitRecursive(cleaned, 0, properties.getChildChunkSize(),
                properties.getChildOverlap(), new ArrayList<>(), 0);
        return mergeSmallChunks(chunks, properties.getChildChunkSize() / 4, properties.getChildChunkSize());
    }

    /**
     * 递归分割文本 (子块) - 返回Spring AI Document列表
     * @param text 待分割文本
     * @param sourceFile 来源文件
     * @param parentId 父块ID
     */
    public List<Document> splitToChildDocuments(String text, String sourceFile, String parentId) {
        List<String> chunkTexts = splitChild(text);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunkTexts.size(); i++) {
            String content = chunkTexts.get(i);
            String id = UUID.randomUUID().toString();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sourceFile", sourceFile);
            metadata.put("parentId", parentId);
            metadata.put("chunkIndex", i);
            metadata.put("chunkType", "child");

            Document doc = new Document(id, content, metadata);
            documents.add(doc);
        }

        log.debug("分割文本为 {} 个子Document，父块: {}", documents.size(), parentId);
        return documents;
    }

    /**
     * 递归分割核心逻辑 (修复版：滑动窗口 Overlap + 消除双重标点)
     * @param text 待分割文本
     * @param separatorIndex 分隔符索引
     * @param maxSize 最大块大小
     * @param overlapSize Overlap 大小
     * @param result 结果列表
     * @param depth 递归深度
     * @return 分割后的文本列表
     */
    private List<String> splitRecursive(String text, int separatorIndex, int maxSize,
                                        int overlapSize, List<String> result, int depth) {
        // 1. 死循环保护与空值校验
        if (depth > properties.getMaxRecursionDepth()) {
            log.warn("达到最大递归深度，强制切断");
            if (!text.isEmpty()) {
                result.add(forceSplit(text, maxSize));
            }
            return result;
        }
        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        // 2. 整体长度达标，直接返回
        int textTokenCount = tokenCounter.count(text);
        if (textTokenCount <= maxSize) {
            result.add(text);
            return result;
        }

        // 3. 检测是否为原子性内容（表格、代码块）
        if (isAtomicContent(text)) {
            log.debug("检测到原子内容，作为独立块保留");
            result.add(text);
            return result;
        }

        // 4. 获取分隔符并切分（正则确保分隔符保留在片段末尾）
        String separator = properties.getSeparators().get(separatorIndex);
        String[] parts;
        if (separator.isEmpty()) {
            parts = text.split("");
        } else {
            // 使用正向肯定预查，确保分隔符保留在片段末尾，保留完整语义
            parts = text.split("(?<=" + Pattern.quote(separator) + ")");
        }

        // 5. 核心：带 Overlap 的滑动窗口合并
        List<String> currentChunkParts = new ArrayList<>();
        int currentTokens = 0;

        for (String part : parts) {
            if (part.isEmpty()) continue;
            int partTokens = tokenCounter.count(part);

            // 场景 A：遇到了一个巨型单句（本身就超过 maxSize）
            if (partTokens > maxSize) {
                // 先把手里攒的正常 chunk 结算掉
                if (!currentChunkParts.isEmpty()) {
                    result.add(String.join("", currentChunkParts));
                    currentChunkParts.clear();
                    currentTokens = 0;
                }

                // 对这个巨型单句进行下钻递归
                if (separatorIndex < properties.getSeparators().size() - 1) {
                    splitRecursive(part, separatorIndex + 1, maxSize, overlapSize, result, depth + 1);
                } else {
                    result.add(forceSplit(part, maxSize)); // 保底强制切断
                }
                continue;
            }

            // 场景 B：当前 chunk 加上新 part 会超标 -> 触发结算与 Overlap
            if (currentTokens + partTokens > maxSize && !currentChunkParts.isEmpty()) {
                // 1. 结算当前 chunk
                result.add(String.join("", currentChunkParts)); // 因为 part 自带标点，直接空字符 join 即可

                // 2. 🌟 真正的 Overlap 处理（滑动窗口）🌟
                // 不断从当前 chunk 的头部丢弃最早的片段，直到剩下的内容长度 <= overlapSize
                while (currentTokens > overlapSize && currentChunkParts.size() > 1) {
                    String removedPart = currentChunkParts.remove(0);
                    currentTokens -= tokenCounter.count(removedPart);
                }
                // 此时，currentChunkParts 里剩下的就是完美的 Overlap 内容！
                // 下一次循环时，新的 part 会直接追加在这个 Overlap 的后面。
            }

            // 场景 C：正常累加片段
            currentChunkParts.add(part);
            currentTokens += partTokens;
        }

        // 6. 结算最后剩余的残留数据
        if (!currentChunkParts.isEmpty()) {
            result.add(String.join("", currentChunkParts));
        }

        return result;
    }

    /**
     * 检测是否为原子性内容（表格、代码块）- 不应拆分
     * @param text 待检测文本
     * @return 是否为原子性内容
     */
    private boolean isAtomicContent(String text) {
        // 检查是否为纯表格内容
        if (TABLE_PATTERN.matcher(text).find()) {
            // 进一步检查：如果主要是表格内容（超过50%是表格行）
            String[] lines = text.split("\n");
            int tableLines = 0;
            for (String line : lines) {
                if (line.trim().startsWith("|")) {
                    tableLines++;
                }
            }
            if (tableLines > lines.length * 0.5) {
                return true;
            }
        }

        // 检查代码块
        if (CODE_BLOCK_PATTERN.matcher(text).find()) {
            return true;
        }

        return false;
    }

    /**
     * 智能合并小碎片
     * 智能合并小碎片 (性能优化版)
     * 如果某个块小于minTokens，则与前一个块合并
     * 注意：合并后不能超过maxSize
     * @param chunks 待合并的块列表
     * @param minTokens 最小 Token 数，小于该值的块将被合并
     * @param maxSize 最大块大小，超过该值的块将被强制切断
     * @return 合并后的块列表
     */
    private List<String> mergeSmallChunks(List<String> chunks, int minTokens, int maxSize) {
        if (chunks.size() <= 1 || minTokens <= 0) {
            return chunks;
        }

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder(chunks.get(0));
        // 缓存当前的 Token 总数，避免每次都重新计算
        int currentTokens = tokenCounter.count(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int chunkTokens = tokenCounter.count(chunk);

            // 如果当前块太小，且合并后不超过 maxSize，则合并
            if (chunkTokens < minTokens && currentTokens + chunkTokens <= maxSize) {
                // 加个空格作为分隔符
                current.append(" ").append(chunk);
                currentTokens += chunkTokens + 1; // +1 是算上 separator 的 token
            } else {
                // 当前块足够大，或是合并后会超标 -> 保存并开始新的
                merged.add(current.toString());
                current = new StringBuilder(chunk);
                currentTokens = chunkTokens;
            }
        }

        // 添加最后一个块
        if (current.length() > 0) {
            merged.add(current.toString());
        }

        return merged;
    }


    /**
     * 强制切断（死循环保护最后手段）
     * @param text 待切断文本
     * @param maxSize 最大块大小
     * @return 切断后的文本
     */
    private String forceSplit(String text, int maxSize) {
        StringBuilder sb = new StringBuilder();
        int currentTokens = 0;

        for (char c : text.toCharArray()) {
            sb.append(c);
            currentTokens += tokenCounter.count(String.valueOf(c));

            if (currentTokens >= maxSize) {
                break;
            }
        }

        return sb.toString();
    }

    /**
     * 文本清洗
     * @param text 待清洗文本
     * @return 清洗后的文本
     */
    public String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String cleaned = text;

        // 去除空字符串和不可见字符
        cleaned = cleaned.replaceAll("[\\x00-\\x1F\\x7F]", "");

        // 去除连续空行
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");

        // 去除页码
        cleaned = cleaned.replaceAll("(页\\s*\\d+\\s*页|Page\\s*\\d+|-\\s*\\d+\\s*-|第\\d+页)", "");
        cleaned = cleaned.replaceAll("^\\s*\\d+\\s*$", "");

        // 去除多余空白
        cleaned = cleaned.replaceAll("[ \t]+", " ");

        return cleaned.trim();
    }
}
