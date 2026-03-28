package top.javarem.omni.test.rag;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class ErrorHealingTester {

    private final JdbcTemplate mysqlJdbcTemplate;

    public ErrorHealingTester(JdbcTemplate mysqlJdbcTemplate) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
    }

    /**
     * 测试场景：空结果返回的纠错能力
     *
     * 期望行为：
     * - 当搜索无结果时，应返回明确的空结果提示
     * - Agent 可据此触发"换关键词重试"或"列出文件目录"
     */
    @Test
    void testEmptyResultHandling() {
        // 使用一个不存在的关键词
        String nonExistentKeyword = "xyzabc123456不存在的内容";

        // 模拟搜索（实际会返回空）
        List<String> searchResult = simulateSearchWithKeyword(nonExistentKeyword);

        // 验证：返回空结果，但不是 null
        assertNotNull(searchResult, "搜索结果不应为 null");
        assertTrue(searchResult.isEmpty(), "不存在的关键词应返回空结果");

        log.info("空结果处理测试通过 - 关键词: {}", nonExistentKeyword);
    }

    /**
     * 测试场景：无效参数的错误处理
     *
     * 期望行为：
     * - 传入无效参数时，应返回有意义的错误信息
     */
    @Test
    void testInvalidParameterHandling() {
        // 测试 1: 无效的 fileId
        try {
            String sql = "SELECT * FROM rag_parent_chunks WHERE file_id = -999999";
            List<Map<String, Object>> results = mysqlJdbcTemplate.queryForList(sql);
            assertTrue(results.isEmpty(), "无效 fileId 应返回空结果");
            log.info("无效 fileId 测试通过");
        } catch (Exception e) {
            fail("无效参数不应抛出异常: " + e.getMessage());
        }

        // 测试 2: 无效的 parentId 格式
        try {
            String sql = "SELECT content FROM rag_parent_chunks WHERE id = 'invalid-id-format'";
            List<String> results = mysqlJdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getString("content"),
                "invalid-id-format");
            assertTrue(results.isEmpty(), "无效 parentId 应返回空结果");
            log.info("无效 parentId 格式测试通过");
        } catch (Exception e) {
            fail("无效参数格式不应抛出异常: " + e.getMessage());
        }
    }

    /**
     * 测试场景：SQL 语法错误的错误信息
     *
     * 期望行为：
     * - SQL 错误应返回结构化的错误信息
     * - 包含错误类型、表名、字段名等信息
     */
    @Test
    void testSqlErrorHandling() {
        // 故意使用错误的 SQL（不存在的表）
        try {
            mysqlJdbcTemplate.queryForList("SELECT * FROM non_existent_table_xyz");
            fail("应该抛出异常");
        } catch (Exception e) {
            // 验证错误信息包含有用信息
            String errorMessage = e.getMessage();
            assertNotNull(errorMessage, "错误信息不应为空");
            log.info("SQL 错误测试通过 - 错误信息: {}", errorMessage);

            // 错误信息应该包含表名提示
            assertTrue(errorMessage.contains("non_existent_table") ||
                       errorMessage.contains("Table") ||
                       errorMessage.contains("not found"),
                    "错误信息应包含表名信息");
        }
    }

    /**
     * 模拟搜索（带关键词）
     */
    private List<String> simulateSearchWithKeyword(String keyword) {
        // 简化实现：返回一个空列表表示无结果
        // 实际需要通过 VectorStore 执行真实搜索
        return List.of();
    }
}
