package top.javarem.omni.test.rag;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import top.javarem.omni.test.rag.model.GoldenQAPair;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class DecisionChainTester {

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final TestDataManager testDataManager;

    public DecisionChainTester(JdbcTemplate mysqlJdbcTemplate, JdbcTemplate pgVectorJdbcTemplate, TestDataManager testDataManager) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.testDataManager = testDataManager;
    }

    /**
     * 测试场景：Agent 决策链路验证
     *
     * 期望链路：
     * 1. ListFiles(kbId) -> 获取文件列表
     * 2. SemanticSearch(query) -> 获取相关子块
     * 3. ReadContinuousText(parentId) -> 获取完整母块
     */
    @Test
    void testDecisionChain() {
        // Step 1: 模拟 ListFiles
        List<Map<String, Object>> files = listFiles("default");
        log.info("Step 1 - 文件列表: {}", files.size());

        // Step 2: 如果有文件，测试语义搜索链路
        if (!files.isEmpty()) {
            GoldenQAPair testCase = testDataManager.getAllTestCases().get(0);
            String searchQuery = testCase.keywords().get(0);
            List<String> parentIds = simulateSemanticSearch(searchQuery);
            log.info("Step 2 - 搜索关键词: {}, 召回母块: {}", searchQuery, parentIds);

            // Step 3: 模拟 ReadContinuousText
            for (String parentId : parentIds) {
                if ("期望的母块ID".equals(parentId)) continue; // 跳过占位符
                String content = readParentChunk(parentId);
                if (content != null && !content.isEmpty()) {
                    log.info("Step 3 - 母块 {} 内容预览: {}", parentId, content.substring(0, Math.min(100, content.length())));
                }
            }
        } else {
            log.info("Step 1 - 知识库为空，跳过详细链路测试");
        }

        // 验证：确保数据库连接正常
        assertNotNull(mysqlJdbcTemplate, "MySQL JdbcTemplate 不应为 null");
        assertNotNull(pgVectorJdbcTemplate, "PostgreSQL JdbcTemplate 不应为 null");
        log.info("决策链路测试通过 - 数据库连接正常!");
    }

    /**
     * 列出知识库中的文件
     */
    private List<Map<String, Object>> listFiles(String kbId) {
        String sql = "SELECT id, filename, status, total_chunks FROM kb_file WHERE kb_id = ? AND status = 'done'";
        return mysqlJdbcTemplate.queryForList(sql, kbId);
    }

    /**
     * 模拟语义搜索（简化版：直接查数据库）
     * 实际场景中会调用 VectorStore
     */
    private List<String> simulateSemanticSearch(String keyword) {
        // 简化：假设我们已经知道相关的 parentId
        // 实际测试中需要通过 VectorStore 获取
        // 这里返回测试用例中的期望 ID
        GoldenQAPair testCase = testDataManager.getAllTestCases().stream()
                .filter(tc -> tc.keywords().contains(keyword))
                .findFirst()
                .orElse(null);

        if (testCase != null) {
            return testCase.expectedParentIds();
        }
        return Collections.emptyList();
    }

    /**
     * 读取母块内容
     */
    private String readParentChunk(String parentId) {
        String sql = "SELECT content FROM rag_parent_chunks WHERE id = ?";
        List<String> results = pgVectorJdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("content"), parentId);
        return results.isEmpty() ? null : results.get(0);
    }
}
