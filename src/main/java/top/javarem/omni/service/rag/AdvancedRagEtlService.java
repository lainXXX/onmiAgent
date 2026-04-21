package top.javarem.omni.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import top.javarem.omni.model.rag.EtlProcessReport;
import top.javarem.omni.repository.rag.RagChunkRepository;
import top.javarem.omni.repository.rag.RagFileRepository;
import top.javarem.omni.service.RerankService;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 高级RAG ETL服务 - 基于Spring AI Document的母子嵌套递归分块
 *
 * 核心设计：
 * 1. 使用 RecursiveTextSplitter 的 Document 方法
 * 2. 多线程优化：并行处理多个文件
 * 3. 内存管理：分批提交向量库
 * 4. 母子块关联：通过parent_id维护关系
 */
@Slf4j
@Service
public class AdvancedRagEtlService {

    private static final int BATCH_SIZE = 100;
    private static final String DEFAULT_RESOURCE_PATH = "classpath:elt_test/*";

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourcePatternResolver;
    private final RecursiveTextSplitter splitter;
    private final TokenCounter tokenCounter;
    private final ExecutorService executorService;
    private final CustomDocxReader customDocxReader;
    private final JdbcTemplate pgVectorJdbcTemplate; // PostgreSQL: 向量存储
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final RerankService rerankService;
    private final RagFileRepository ragFileRepository;
    private final RagChunkRepository ragChunkRepository;

    public AdvancedRagEtlService(VectorStore vectorStore,
                                 ResourcePatternResolver resourcePatternResolver,
                                 RecursiveTextSplitter splitter,
                                 TokenCounter tokenCounter, CustomDocxReader customDocxReader,
                                 NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                                 @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                 ObjectMapper objectMapper,
                                 RerankService rerankService,
                                 RagFileRepository ragFileRepository,
                                 RagChunkRepository ragChunkRepository) throws SQLException {
        this.vectorStore = vectorStore;
        this.resourcePatternResolver = resourcePatternResolver;
        this.splitter = splitter;
        this.tokenCounter = tokenCounter;
        this.customDocxReader = customDocxReader;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
        this.rerankService = rerankService;
        this.ragFileRepository = ragFileRepository;
        this.ragChunkRepository = ragChunkRepository;

        int threadCount = Runtime.getRuntime().availableProcessors();
        this.executorService = Executors.newFixedThreadPool(Math.min(threadCount, 4));
        log.info("当前查询的库 URL: {}", pgVectorJdbcTemplate.getDataSource().getConnection().getMetaData().getURL());
    }

    /**
     * 解析单个文档 - 返回Document列表
     */
    private DocumentParseResult parseDocument(Resource resource, String filename) {
        try {
            log.info("Parsing document: {}", filename);
            // 1. 智能路由提取文档内容 (Extract)

            List<Document> documents = extractContentByFormat(resource, filename);

            if (documents.isEmpty()) {
                log.warn("No content extracted from: {}", filename);
                return null;
            }

            StringBuilder fullText = new StringBuilder();
            for (Document doc : documents) {
                fullText.append(doc.getText()).append("\n");
            }

            String text = fullText.toString();
            int totalTokens = tokenCounter.count(text);

            log.info("Extracted {} chars, {} tokens from {}", text.length(), totalTokens, filename);

            // 使用 splitter 直接生成母块 Document
            List<Document> parentDocs = splitter.splitToDocuments(text, filename);

            // 为每个母块生成子块 Document
            List<Document> allChildDocs = new ArrayList<>();
            int parentCount = 0;

            for (Document parentDoc : parentDocs) {
                String parentId = parentDoc.getId();
                String parentContent = parentDoc.getText();

                // 生成子块
                List<Document> childDocs = splitter.splitToChildDocuments(parentContent, filename, parentId);
                allChildDocs.addAll(childDocs);
                parentCount++;
            }

            log.info("Generated {} parent docs, {} child docs from {}",
                    parentCount, allChildDocs.size(), filename);

            return new DocumentParseResult(filename, parentDocs, allChildDocs, totalTokens);

        } catch (Exception e) {
            log.error("Failed to parse document {}: {}", filename, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 处理指定目录下的所有文件
     * @param resourcePath 文件路径模式
     * @param kbId 知识库ID (可选，用于关联文件)
     * @return 处理报告
     */
    public EtlProcessReport processDirectory(String resourcePath, String kbId) throws IOException {
        long startTime = System.currentTimeMillis();

        if (kbId == null || kbId.isEmpty()) kbId = "default";

        // 1. 初始化文件记录 (每个文件一个记录)
        Long fileId = null;
        String filename = extractFilename(resourcePath);
        fileId = initFileRecord(kbId, filename);
        updateFileStatus(fileId, "processing", null); // 更新状态为处理中

        String pattern = resourcePath != null && !resourcePath.isEmpty()
                ? resourcePath
                : DEFAULT_RESOURCE_PATH;

        Resource[] resources = resourcePatternResolver.getResources(pattern);
        log.info("Found {} files to process", resources.length);

        List<Future<DocumentParseResult>> futures = new ArrayList<>();

        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                log.warn("Skipping unreadable resource: {}", resource);
                continue;
            }

            String resourceName = resource.getFilename();
            if (resourceName == null || resourceName.startsWith(".")) {
                continue;
            }

            futures.add(executorService.submit(() -> parseDocument(resource, resourceName)));
        }

        List<Document> allChildDocs = new ArrayList<>();
        List<Document> allParentDocs = new ArrayList<>();
        int processedFileCount = 0;
        long totalTokens = 0;

        for (Future<DocumentParseResult> future : futures) {
            try {
                DocumentParseResult result = future.get(30, TimeUnit.SECONDS);
                if (result != null) {
                    // 为母块添加 kbId 元数据
                    for (Document parent : result.parentDocs) {
                        parent.getMetadata().put("kbId", kbId);
                        parent.getMetadata().put("fileId", fileId);
                    }
                    // 为子块添加 kbId 元数据
                    for (Document child : result.childDocs) {
                        child.getMetadata().put("kbId", kbId);
                        child.getMetadata().put("fileId", fileId);
                    }

                    allParentDocs.addAll(result.parentDocs);
                    allChildDocs.addAll(result.childDocs);
                    processedFileCount++;
                    totalTokens += result.totalTokens;
                    log.info("File {} processed: {} parent docs, {} child docs",
                            result.filename, result.parentDocs.size(), result.childDocs.size());
                }
            } catch (Exception e) {
                log.error("Failed to process document: {}", e.getMessage());
                // 更新文件状态为失败
                if (fileId != null) {
                    updateFileStatus(fileId, "failed", null);
                }
            }
        }

        try {
            // 2. 存储子块到向量库 (带 kbId 元数据)
            storeToVectorStore(allChildDocs);

            // 3. 存储母块到数据库
            if (fileId != null) {
                batchSaveParents(allParentDocs, fileId);
            }

            // 4. 更新文件状态为完成
            if (fileId != null) {
                updateFileStatus(fileId, "done", allParentDocs.size());
            }
        } catch (Exception e) {
            log.error("Failed to store documents: {}", e.getMessage());
            if (fileId != null) {
                updateFileStatus(fileId, "failed", null);
            }
            throw e;
        }

        long processTime = System.currentTimeMillis() - startTime;
        int parentCount = allParentDocs.size();
        int childCount = allChildDocs.size();

        double avgParentTokens = parentCount > 0 ? (double) totalTokens / parentCount : 0;
        double avgChildTokens = childCount > 0 ? (double) totalTokens / childCount : 0;

        printDocumentPreview(allParentDocs, allChildDocs);

        return new EtlProcessReport(
                processedFileCount,
                parentCount,
                childCount,
                avgParentTokens,
                avgChildTokens,
                totalTokens,
                processTime
        );
    }

    /**
     * 从路径中提取文件名
     */
    private String extractFilename(String resourcePath) {
        if (resourcePath == null) return "unknown";
        int lastSlash = resourcePath.lastIndexOf('/');
        int lastBackslash = resourcePath.lastIndexOf('\\');
        int lastIndex = Math.max(lastSlash, lastBackslash);
        return lastIndex >= 0 ? resourcePath.substring(lastIndex + 1) : resourcePath;
    }

    /**
     * 存储到向量库 (分批提交，内存优化)
     */
    private void storeToVectorStore(List<Document> documents) {
        List<Document> batch = new ArrayList<>();

        for (Document doc : documents) {
            batch.add(doc);

            if (batch.size() >= BATCH_SIZE) {
                vectorStore.accept(batch);
                log.debug("Submitted batch of {} documents to vector store", batch.size());
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            vectorStore.accept(batch);
            log.debug("Submitted final batch of {} documents to vector store", batch.size());
        }

        log.info("All {} documents stored to vector store", documents.size());
    }

    /**
     * 打印文档预览
     */
    private void printDocumentPreview(List<Document> parentDocs, List<Document> childDocs) {
        log.info("========== Document 预览 ==========");

        int displayCount = Math.min(3, parentDocs.size());
        for (int i = 0; i < displayCount; i++) {
            Document parent = parentDocs.get(i);
            log.info("--- 母块 {} ---", i + 1);
            log.info("ID: {}", parent.getId());
            log.info("内容预览: {}", truncate(parent.getText(), 200));
        }

        if (parentDocs.size() > displayCount) {
            log.info("... 还有 {} 个母块未展示", parentDocs.size() - displayCount);
        }

        log.info("===================================");
    }

    /**
     * 截断字符串
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 处理单个上传文件
     * @param file 上传的文件
     * @param kbId 知识库ID
     * @return 处理报告
     */
    public EtlProcessReport processFile(MultipartFile file, String kbId) throws IOException {
        long startTime = System.currentTimeMillis();
        if (kbId == null || kbId.isEmpty()) kbId = "default";
        String filename = file.getOriginalFilename();

        // 1. 初始化文件记录
        Long fileId = initFileRecord(kbId, filename);
        updateFileStatus(fileId, "processing", null);

        Resource resource = file.getResource();

        try {
            DocumentParseResult result = parseDocument(resource, filename);

            if (result == null) {
                updateFileStatus(fileId, "failed", null);
                return new EtlProcessReport(0, 0, 0, 0, 0, 0, 0);
            }

            // 添加 kbId 和 fileId 元数据
            for (Document parent : result.parentDocs) {
                parent.getMetadata().put("kbId", kbId);
                parent.getMetadata().put("fileId", fileId);
            }
            for (Document child : result.childDocs) {
                child.getMetadata().put("fileId", fileId);
                child.getMetadata().put("kbId", kbId);
            }

            // 2. 存储子块到向量库
            storeToVectorStore(result.childDocs);

            // 3. 存储母块到数据库
            batchSaveParents(result.parentDocs, fileId);

            // 4. 更新文件状态
            updateFileStatus(fileId, "done", result.parentDocs.size());

            long processTime = System.currentTimeMillis() - startTime;

            return new EtlProcessReport(
                    1,
                    result.parentDocs.size(),
                    result.childDocs.size(),
                    result.parentDocs.size() > 0 ? (double) result.totalTokens / result.parentDocs.size() : 0,
                    result.childDocs.size() > 0 ? (double) result.totalTokens / result.childDocs.size() : 0,
                    result.totalTokens,
                    processTime
            );

        } catch (Exception e) {
            log.error("处理文件失败: {}", e.getMessage());
            updateFileStatus(fileId, "failed", null);
            throw e;
        }
    }

    /**
     * 根据文件后缀，将提取任务路由给最合适的阅读器
     */
    private List<Document> extractContentByFormat(Resource resource, String filename) {
        String lowerCaseName = filename.toLowerCase();

        try {
            // 路由 A: Word 文档 (包含 .doc 和 .docx)
            if (lowerCaseName.endsWith(".doc") || lowerCaseName.endsWith(".docx")) {
                log.info("路由 -> 使用 CustomDocxReader 处理 Word 文档");
                // CustomDocxReader 内部已经通过 FileMagic 处理了 .doc 和 .docx 的兼容，
                // 并且能完美将表格转为 Markdown，并预留了图片 OCR 接口。
                return customDocxReader.read(resource);
            }

            // 预留路由 B: PPT 文档 (待你未来实现 CustomPptxReader 时解开注释)
            /*
            else if (lowerCaseName.endsWith(".ppt") || lowerCaseName.endsWith(".pptx")) {
                log.info("路由 -> 使用 CustomPptxReader 处理演示文稿");
                return customPptxReader.read(resource);
            }
            */

            // 路由 C: 默认兜底方案 (PDF, TXT, HTML 等)
            else {
                log.info("路由 -> 使用 Spring AI 默认 TikaDocumentReader 处理通用格式");
                // 注意：Spring AI 1.0 的 TikaDocumentReader 返回的直接是 List<Document>
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                return reader.get(); // 根据你提供的源码，调用的是 get() 方法
            }
        } catch (Exception e) {
            log.error("提取器在读取流时发生异常: {}", e.getMessage());
            throw new RuntimeException("文档流提取失败", e);
        }
    }

    /**
     * 初始化文件记录 (上传时调用)
     * 返回 fileId 供后续使用
     */
    public Long initFileRecord(String kbId, String filename) {
        Long kbIdLong;
        try {
            kbIdLong = Long.parseLong(kbId);
        } catch (NumberFormatException e) {
            // 如果不是数字，使用默认值 0
            kbIdLong = 0L;
        }
        Long fileId = ragFileRepository.insert(kbIdLong, filename);
        log.info("初始化文件记录: fileId={}, kbId={}, filename={}", fileId, kbId, filename);
        return fileId;
    }

    /**
     * 更新文件状态
     */
    public void updateFileStatus(Long fileId, String status, Integer totalChunks) {
        ragFileRepository.updateStatus(fileId, status, totalChunks != null ? totalChunks : 0);
        log.info("更新文件状态: fileId={}, status={}, total_chunks={}", fileId, status, totalChunks);
    }

    /**
     * 批量存储母块到数据库
     */
    public void batchSaveParents(List<Document> parentDocs, Long fileId) {
        if (parentDocs.isEmpty()) return;

        // 为每个 doc 添加 file_id
        for (Document doc : parentDocs) {
            doc.getMetadata().put("file_id", fileId);
        }
        ragChunkRepository.batchInsert(parentDocs);
        log.info("批量存储 {} 个母块到数据库, fileId={}", parentDocs.size(), fileId);
    }

    /**
     * 根据 fileId 查询所有母块
     */
    public List<Document> getParentChunksByFileId(Long fileId) {
        return ragChunkRepository.findByFileId(fileId).stream()
                .map(ragChunkRepository::toDocument)
                .toList();
    }

    public List<Document> getParentChunksByKbId(String kbId) {
        // 将 kbId 转换为 Long 类型，确保与数据库类型一致
        Long kbIdLong;
        try {
            kbIdLong = Long.parseLong(kbId);
        } catch (NumberFormatException e) {
            // 如果不是数字，使用默认值 0
            kbIdLong = 0L;
        }

        // 建议：如果表名在 schema 内部，请使用 "rag"."rag_parent_chunks"
        String sql = """
            SELECT c.id, c.content, c.metadata, c.chunk_index, c.token_count, f.kb_id
            FROM rag_parent_chunks c
            JOIN kb_file f ON c.file_id = f.id
            WHERE f.kb_id = ?
            ORDER BY f.id, c.chunk_index
            """;

        // 使用 pgVectorJdbcTemplate
        return pgVectorJdbcTemplate.query(sql, (rs, rowNum) -> {
            String id = rs.getString("id");
            String content = rs.getString("content");

            // PostgreSQL 中 JSONB 读取为 String 即可，JDBC 驱动会自动转换
            String metadataJson = rs.getString("metadata");
            int chunkIndex = rs.getInt("chunk_index");
            int tokenCount = rs.getInt("token_count");

            // 使用 ObjectMapper 安全解析
            Map<String, Object> metadata = safeParseJson(metadataJson);

            // 覆盖或合并元数据
            metadata.put("chunkIndex", chunkIndex);
            metadata.put("token_count", tokenCount);
            metadata.put("kb_id", kbId);

            return new Document(id, content, metadata);
        }, kbIdLong);
    }

    /**
     * 安全解析 JSON 字符串
     */
    private Map<String, Object> safeParseJson(String json) {
        try {
            if (json == null || json.isEmpty()) return new HashMap<>();

            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>(){});

        } catch (Exception e) {
            log.warn("Metadata JSON 解析失败: {}", json, e);
            return new HashMap<>();
        }
    }


    /**
     * 高级检索：子块检索 -> Rerank重排 -> 母块回扫 -> 顺序输出
     *
     * @param query 用户的提问
     * @param topK  初步召回的数量（建议 30-50）
     * @param kbId  知识库ID
     * @return 最终聚合了母块上下文的 Document 列表
     */
    public List<Document> retrieveContext(String query, int topK, String kbId) {
        // 1. 向量搜索：初始召回子块
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(StringUtils.hasText(kbId) ? "kbId == '" + kbId + "'" : null)
                .similarityThreshold(0.5)
                .build();

        List<Document> childDocs = vectorStore.similaritySearch(searchRequest);
        if (CollectionUtils.isEmpty(childDocs)) {
            return Collections.emptyList();
        }

        // 2. Rerank 重排序逻辑
        List<Document> rerankedChildDocs = performRerank(query, childDocs);

        // 3. 提取 ParentId 并保持 Rerank 后的原始顺序
        // 使用 LinkedHashSet 保证去重且有序
        Set<String> orderedParentIds = rerankedChildDocs.stream()
                .map(doc -> (String) doc.getMetadata().get("parentId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (orderedParentIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. 批量从数据库查询母块记录
        // 注意：metadata::text 强制转换为字符串，解决 PGobject 强转 String 报错问题
        // 动态生成 IN 占位符
        StringBuilder placeholders = new StringBuilder();
        List<Object> params = new ArrayList<>();
        List<String> parentIdList = new ArrayList<>(orderedParentIds);
        for (int i = 0; i < parentIdList.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
            params.add(parentIdList.get(i));
        }

        String sql = """
            SELECT id, content, metadata::text as metadata_json,
                   file_id, chunk_index, token_count
            FROM rag_parent_chunks
            WHERE id IN (""" + placeholders + """
            )
            """;

        List<Map<String, Object>> rows = pgVectorJdbcTemplate.queryForList(sql, params.toArray());

        // 5. 将母块记录转换为 Spring AI Document (严格处理 Null 和类型转换)
        Map<String, Document> idToParentDocMap = convertRowsToDocuments(rows, kbId);

        // 6. 按照 Rerank 确定的 ParentId 顺序组装最终结果
        return orderedParentIds.stream()
                .map(idToParentDocMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 执行 Rerank 重排，并优化查找 Document 的性能
     */
    private List<Document> performRerank(String query, List<Document> childDocs) {
        if (rerankService == null) return childDocs;

        try {
            // 将文本内容映射为 Document 对象，提高重排后的查找速度 (O(n) 替代 O(n^2))
            Map<String, Document> textToDocMap = childDocs.stream()
                    .collect(Collectors.toMap(Document::getText, d -> d, (a, b) -> a));

            List<String> childTexts = new ArrayList<>(textToDocMap.keySet());
            List<RerankService.RerankDocument> scoredDocs = rerankService.rerankWithScore(query, childTexts);

            return scoredDocs.stream()
                    .map(scored -> textToDocMap.get(scored.getText()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Rerank 过程异常，回退至向量检索排序: {}", e.getMessage());
            return childDocs;
        }
    }

    /**
     * 将数据库行映射为 Document，核心修复：防止 Metadata Null Value 导致 Assert 失败
     */
    private Map<String, Document> convertRowsToDocuments(List<Map<String, Object>> rows, String kbId) {
        Map<String, Document> docMap = new HashMap<>();

        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String content = String.valueOf(row.getOrDefault("content", ""));
            String metadataJson = (String) row.get("metadata_json");
            // 解析原始 JSON 字段
            Map<String, Object> metadata = new HashMap<>();
            if (StringUtils.hasText(metadataJson)) {
                try {
                    metadata = objectMapper.readValue(metadataJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.warn("解析分块元数据 JSON 失败 [id={}]: {}", id, e.getMessage());
                }
            }

            // ⭐ 关键修复：注入业务元数据，确保字段名与 SQL 别名/列名匹配
            // 使用 safePut 确保不向 Spring AI Document 传入任何 null value
            safePut(metadata, "fileId", row.get("file_id"));
            safePut(metadata, "chunkIndex", row.get("chunk_index"));
            safePut(metadata, "tokenCount", row.get("token_count"));
            safePut(metadata, "kbId", kbId);
            safePut(metadata, "id", id);

            docMap.put(id, new Document(id, content, metadata));
        }
        return docMap;
    }

    /**
     * 安全写入元数据：如果值为 null 则填充默认值，防止 Spring AI 报错
     */
    private void safePut(Map<String, Object> metadata, String key, Object value) {
        if (value == null) {
            // Spring AI Document 断言 metadata 不含 null，这里根据类型给默认值
            if ("chunkIndex".equals(key) || "tokenCount".equals(key)) {
                metadata.put(key, 0);
            } else {
                metadata.put(key, "unknown");
            }
        } else {
            metadata.put(key, value);
        }
    }

    /**
     * 根据文件ID删除向量库中的子块
     */
    public void deleteChildChunksByFileId(Long fileId) {
        try {
            // 从向量库表直接查询关联的子块ID
            String sql = "SELECT id FROM vector_store WHERE metadata_->>'file_id' = ?";
            List<String> ids = pgVectorJdbcTemplate.queryForList(sql, String.class, String.valueOf(fileId));

            if (!ids.isEmpty()) {
                log.info("[AdvancedRagEtlService] 删除 {} 个子块从向量库, fileId={}", ids.size(), fileId);
                vectorStore.delete(ids);
            }
        } catch (Exception e) {
            log.error("删除子块失败: fileId={}, error={}", fileId, e.getMessage());
            throw e;
        }
    }

    /**
     * 解析结果内部类
     */
    private static class DocumentParseResult {
        String filename;
        List<Document> parentDocs;
        List<Document> childDocs;
        int totalTokens;

        DocumentParseResult(String filename, List<Document> parentDocs,
                           List<Document> childDocs, int totalTokens) {
            this.filename = filename;
            this.parentDocs = parentDocs;
            this.childDocs = childDocs;
            this.totalTokens = totalTokens;
        }
    }


}
