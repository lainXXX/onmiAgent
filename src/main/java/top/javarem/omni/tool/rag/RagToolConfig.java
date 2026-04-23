package top.javarem.omni.tool.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import top.javarem.omni.service.rag.AdvancedRagEtlService;
import top.javarem.omni.tool.AgentTool;
import top.javarem.omni.utils.MarkdownUtil;

import java.util.*;

/**
 * RAG 工具集 - 语义检索、上下文阅读、文件列表
 *
 * 实现四大核心工具：
 * 1. SemanticSearchTool - 语义检索
 * 2. ReadContinuousTextTool - 连续文本阅读
 * 3. ListKnowledgeBaseFilesTool - 知识库文件列表
 * 4. ListFilesTool - 文件列表
 */
@Component
@Slf4j
public class RagToolConfig implements AgentTool {

    @Override
    public String getName() {
        return "SemanticSearch";
    }

    @Override
    public boolean isCompactable() {
        return false;
    }

    private final VectorStore vectorStore;
    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdvancedRagEtlService advancedRagEtlService;

    @Autowired
    public RagToolConfig(
            VectorStore vectorStore,
            JdbcTemplate mysqlJdbcTemplate,
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ObjectMapper objectMapper, AdvancedRagEtlService advancedRagEtlService) {
        this.vectorStore = vectorStore;
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
        this.advancedRagEtlService = advancedRagEtlService;
    }

    // ==================== 工具一：语义检索 ====================

    /**
     * SemanticSearchTool - 核心语义检索工具
     *
     * 使用语义相似度在知识库中搜索答案。
     * 关键指令：对于用户的任何事实性提问，必须首先且强制调用此工具。
     */
    @Tool(name = "SemanticSearch", description = """
             【核心功能】
             利用向量语义检索技术在知识库中查找相关文档片段。
            
             【调用规范 (MUST)】
             1. 强制优先性：对于任何涉及知识库内容的咨询，必须首先调用此工具。
             2. 严禁幻觉：严禁使用你自身的知识储备回答问题，除非搜索结果已明确提供答案。
             3. 话题重置：若用户开启新话题或话题发生实质性转移，必须立即重新调用此工具搜索新主题，严禁使用旧搜索结果回答。
             4. 迭代搜索：若首次搜索结果不匹配，请尝试更换关键词或语义陈述后再次调用。
            
             【最佳实践】
             - 将用户的问题重写为精准的“搜索短语”（例如：将“去年的利润情况”重写为“2023年年度净利润及增长率”）。
             - 若搜索结果语义断裂，请结合 [read_chunks] 工具获取更完整的上下文。
            """)
    public String semanticSearch(
            @ToolParam(description = "知识库ID (可选)。若为null，则在系统内所有授权的知识库中进行全局语义检索。", required = false) String kbId,
            @ToolParam(description = "语义搜索查询内容。应包含核心名词、时间范围或具体事件，以便获得最佳匹配效果。") String query) {

        log.info("[semantic_search] 开始: kbId={}, query={}", kbId, query);

        if (query == null || query.trim().isEmpty()) {
            log.error("[semantic_search] 失败: 查询词为空");
            return buildError("搜索关键词不能为空", "请提供有效的搜索内容");
        }
        if (StringUtils.isEmpty(kbId)) {
            kbId = "default";
        }
        int topK = 10;

        try {
            List<Document> documents = advancedRagEtlService.retrieveContext(query, topK, kbId);
            log.info("[semantic_search] 完成: 结果={}", documents.size());
            return objectMapper.writeValueAsString(documents);

        } catch (Exception e) {
            log.error("[semantic_search] 失败: error={}", e.getMessage());
            return buildError("搜索失败: " + e.getMessage(), "请稍后重试或尝试使用其他关键词");
        }
    }

    // ==================== 工具二：连续文本阅读 ====================
    // 用于接收批量读取请求的单个块对象
    public record ChunkReference(@ToolParam(description = "文件ID") String fileId,
                                 @ToolParam(description = "块索引") Integer chunkIndex) {}

    // 用于接收批量请求的整体包装类
    /**
     * ReadContinuousTextTool - 上下文深度阅读工具
     *
     * 精准读取指定文件的连续文本段落。
     * 适用场景：当语义搜索提供的信息不够完整，需要通读某份文件的特定章节时使用。
     */
    @Tool(name = "ReadChunks", description = """
        精准读取指定文件的连续或离散文本段落。
        适用场景：当语义搜索信息不全，需要补全上下文或通读特定章节时使用。
        可以直接传入包含 fileId 和 chunkIndex 的对象列表。
        """)
    public String readChunks(List<ChunkReference> request) {
        log.info("[read_chunks] 开始: request={}", request);
        List<ChunkReference> chunks = request;

        if (CollectionUtils.isEmpty(chunks)) {
            log.error("[read_chunks] 失败: chunks列表为空");
            return "⚠️ 请提供需要读取的 chunks 列表（包含 fileId 和 chunkIndex）。";
        }
        String kbId = "default";

        // 1. 限制单次读取上限，防止上下文撑爆 (企业级保护)
        if (chunks.size() > 20) {
            log.warn("单次读取请求过大: {}，已截断至 20", chunks.size());
            chunks = chunks.subList(0, 20);
        }

        try {
            // 2. 构造 SQL：PostgreSQL 元组查询
            // kb_id 存储在 metadata JSONB 字段中
            // 单个 chunk 用 =，多个 chunks 用 IN
            String whereClause;
            List<Object> args = new ArrayList<>();
            args.add(kbId);

            if (chunks.size() == 1) {
                // 单个 chunk：使用 =
                whereClause = "(file_id = ? AND chunk_index = ?)";
                args.add(Long.parseLong(chunks.get(0).fileId()));
                args.add(chunks.get(0).chunkIndex());
            } else {
                // 多个 chunks：使用 IN ((?,?), (?,?)...)
                StringJoiner placeholders = new StringJoiner(", ");
                for (ChunkReference chunk : chunks) {
                    placeholders.add("(?, ?)");
                    args.add(Long.parseLong(chunk.fileId()));
                    args.add(chunk.chunkIndex());
                }
                whereClause = "(file_id, chunk_index) IN (" + placeholders + ")";
            }

            String sql = """
                SELECT file_id AS "文件ID",
                       chunk_index AS "序号",
                       content AS "文本内容"
                FROM rag_parent_chunks
                WHERE metadata->>'kbId' = ?
                AND """ + whereClause + """
                ORDER BY file_id, chunk_index ASC
                """;

            // 5. 执行查询
            List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(sql, args.toArray());

            if (results.isEmpty()) {
                log.info("[read_chunks] 完成: 结果=0");
                return "❌ 未找到指定的文本片段，请确认文件 ID 和序号是否正确。";
            }

            // 4. 优化返回格式：对于“内容类”数据，表格可能不是最佳选择
            // 如果内容很长，表格会很难看。我们采用“分块卡片”模式。
            StringBuilder sb = new StringBuilder("📖 **文本读取结果** (共 ").append(results.size()).append(" 段):\n\n");

            sb.append(MarkdownUtil.renderTable(results));

            log.info("[read_chunks] 完成: 结果={}", results.size());
            return sb.toString();

        } catch (Exception e) {
            log.error("[read_chunks] 失败: error={}", e.getMessage(), e);
            return "❌ 读取过程中发生系统错误，请稍后重试。";
        }
    }

    // ==================== 工具三：知识库文件列表 ====================

    /**
     * ListKnowledgeBaseFilesTool - 知识库文件列表工具
     *
     * 获取元数据。
     */
    @Tool(name = "GetFileMetadata", description = """
            获取当前知识库的文件目录和元数据。
            适用场景：
            1. 需要确认知识库中是否包含某一类文件时。
            2. 在尝试调用阅读工具前，获取准确的file_id和该文件的最大块数。
            只信任status为'done'的文件。
            """)
    public String getFileMetadata(
            @ToolParam(description = "知识库ID，不传则使用默认知识库") String kbId,
            @ToolParam(description = "文件ID列表") String[] fileIds) {

        // 1. 参数标准化与防呆处理
        String safeKbId = StringUtils.hasText(kbId) ? kbId.trim() : "default";

        log.info("文件列表: kbId={}, fileIds={}", kbId, Arrays.toString(fileIds));

        try {

            // 构建 In 查询 SQL，使用动态占位符
            StringBuilder placeholders = new StringBuilder();
            Object[] params = new Object[fileIds.length + 1];
            params[0] = safeKbId;
            for (int i = 0; i < fileIds.length; i++) {
                placeholders.append(i == 0 ? "?" : ", ?");
                params[i + 1] = fileIds[i];
            }

            String selectSql = """
            SELECT filename, status, total_chunks, created_at
            FROM kb_file
            WHERE kb_id = ? AND status = 'done'
            AND file_id IN (""" + placeholders + """
            )
            ORDER BY created_at DESC
            """;

            List<Map<String, Object>> files = mysqlJdbcTemplate.queryForList(selectSql, params);

            // 4. 构建并返回结果
            return MarkdownUtil.renderTable(files);

        } catch (DataAccessException e) {
            log.error("数据库访问异常: kbId={}", safeKbId, e);
            return "{\"error\": \"获取文件列表失败，数据库异常\"}";
        } catch (Exception e) {
            log.error("获取知识库文件列表出现未知错误: kbId={}", safeKbId, e);
            return "{\"error\": \"系统内部错误\"}";
        }
    }

    // ==================== 工具四：简单文件列表 ====================

    /**
     * ListFilesTool - 简单文件列表工具
     *
     * 列出知识库中的文件列表。
     */
    @Tool(name = "ListFiles", description = """
        列出当前知识库中的所有文件。返回每个文件的文件ID、文件名和块数等信息。
        """)
    public String listFiles(
            @ToolParam(description = "知识库ID，不传则使用默认知识库", required = false) String kbId,
            @ToolParam(description = "页码，从0开始", required = false) Integer pageIndex,
            @ToolParam(description = "每页数量", required = false) Integer pageSize) {

        // 1. 参数标准化 (防呆处理)
        String safeKbId = StringUtils.hasText(kbId) ? kbId.trim() : "default";
        int page = (pageIndex != null && pageIndex >= 0) ? pageIndex : 0;
        int size = (pageSize != null && pageSize > 0) ? pageSize : 10;
        int offset = page * size;

        log.info("分页查询文件列表: kbId={}, pageIndex={}, pageSize={}", safeKbId, page, size);

        try {
            // 2. 统计总数 (分页必备，告诉 LLM 后面还有没有)
            String countSql = "SELECT COUNT(*) FROM kb_file WHERE kb_id = ?";
            Integer total = mysqlJdbcTemplate.queryForObject(countSql, Integer.class, safeKbId);

            if (total == null || total == 0) {
                return "📁 知识库 [" + safeKbId + "] 目前是空的，没有任何文件。";
            }

            // 3. 分页查询 (使用 SQL 别名直接定义表头)
            String selectSql = """
            SELECT id AS '文件ID', 
                   filename AS '文件名', 
                   total_chunks AS '分块数',
                   status AS '状态'
            FROM kb_file 
            WHERE kb_id = ? 
            ORDER BY created_at DESC 
            LIMIT ? OFFSET ?
            """;

            List<Map<String, Object>> files = mysqlJdbcTemplate.queryForList(selectSql, safeKbId, size, offset);

            // 4. 使用自动表格工具生成内容
            String tableMd = MarkdownUtil.renderTable(files);

            // 5. 组合最终返回 (包含分页概览)
            int totalPages = (int) Math.ceil((double) total / size);

            return String.format(
                    "📁 **知识库 [%s] 文件列表**\n\n%s\n\n> 📊 **统计信息**: 共 `%d` 个文件 | 当前第 `%d` 页 (共 `%d` 页)",
                    safeKbId, tableMd, total, page, totalPages
            );

        } catch (Exception e) {
            log.error("获取文件列表失败: kbId={}", safeKbId, e);
            return "❌ 无法获取文件列表，请检查知识库 ID 是否正确。";
        }
    }


    private String buildError(String error, String suggestion) {
        return "❌ " + error + "\n\n💡 建议: " + suggestion;
    }
}
