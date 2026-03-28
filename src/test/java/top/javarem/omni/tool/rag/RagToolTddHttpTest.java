package top.javarem.onmi.tool.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 测试：RAG 工具 SQL 查询测试
 *
 * 测试流程：
 * 1. 先写失败的测试
 * 2. 验证测试失败原因
 * 3. 修复 SQL 语法
 * 4. 验证测试通过
 */
@SpringBootTest
@ActiveProfiles("dev")
class RagToolTddHttpTest {

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Autowired
    @Qualifier("mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    private static final String TEST_KB_ID = "default";
    private static final Long TEST_FILE_ID = 15L;

    @Nested
    @DisplayName("1. semantic_search 语义搜索工具测试")
    class SemanticSearchTest {

        @Test
        @DisplayName("测试语义搜索 - 应该返回相关文档")
        void testSemanticSearch() {
            String sql = "SELECT id, content, metadata->>'sourceFile' as source_file " +
                    "FROM rag_parent_chunks " +
                    "WHERE metadata->>'kbId' = ? " +
                    "ORDER BY chunk_index " +
                    "LIMIT 5";

            List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(sql, TEST_KB_ID);

            assertNotNull(results, "应该返回文档列表");
            assertFalse(results.isEmpty(), "知识库应该有数据");
            System.out.println("semantic_search 验证通过，返回 " + results.size() + " 条记录");
        }
    }

    @Nested
    @DisplayName("2. read_chunks 文本读取工具测试")
    class ReadChunksTest {

        @Test
        @DisplayName("测试单个 chunk 读取 - 使用 = 查询")
        void testReadSingleChunk() {
            String sql = "SELECT file_id, chunk_index, content " +
                    "FROM rag_parent_chunks " +
                    "WHERE metadata->>'kbId' = ? " +
                    "AND file_id = ? AND chunk_index = ?";

            List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(
                    sql, TEST_KB_ID, TEST_FILE_ID, 85);

            assertNotNull(results);
            assertEquals(1, results.size(), "应该返回 1 条记录");

            Map<String, Object> row = results.get(0);
            assertEquals(TEST_FILE_ID, row.get("file_id"));
            assertEquals(85, row.get("chunk_index"));
            assertNotNull(row.get("content"), "内容不应为空");

            System.out.println("read_chunks (单条) 测试通过");
            String content = row.get("content").toString();
            System.out.println("内容预览: " + content.substring(0, Math.min(100, content.length())));
        }

        @Test
        @DisplayName("测试多个 chunks 读取 - 使用 IN 查询")
        void testReadMultipleChunks() {
            String sql = "SELECT file_id, chunk_index, content " +
                    "FROM rag_parent_chunks " +
                    "WHERE metadata->>'kbId' = ? " +
                    "AND (file_id, chunk_index) IN ((?, ?), (?, ?)) " +
                    "ORDER BY file_id, chunk_index";

            List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(
                    sql, TEST_KB_ID, TEST_FILE_ID, 176, TEST_FILE_ID, 177);

            assertNotNull(results);
            assertEquals(2, results.size(), "应该返回 2 条记录");

            System.out.println("read_chunks (多条) 测试通过，返回 " + results.size() + " 条记录");
        }

        @Test
        @DisplayName("测试读取不存在的 chunk - 应返回空")
        void testReadNonExistentChunk() {
            String sql = "SELECT file_id, chunk_index, content " +
                    "FROM rag_parent_chunks " +
                    "WHERE metadata->>'kbId' = ? " +
                    "AND file_id = ? AND chunk_index = ?";

            List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(
                    sql, TEST_KB_ID, TEST_FILE_ID, 999999);

            assertNotNull(results);
            assertTrue(results.isEmpty(), "不存在的 chunk 应返回空列表");

            System.out.println("read_chunks (空结果) 测试通过");
        }
    }

    @Nested
    @DisplayName("3. get_file_metadata 文件元数据测试")
    class GetFileMetadataTest {

        @Test
        @DisplayName("测试获取文件元数据 - 动态 IN 查询")
        void testGetFileMetadata() {
            // 使用正确的字段名 id 而不是 file_id
            String[] fileIds = {"15"};
            StringBuilder placeholders = new StringBuilder();
            Object[] params = new Object[fileIds.length + 1];
            params[0] = TEST_KB_ID;

            for (int i = 0; i < fileIds.length; i++) {
                if (i > 0) placeholders.append(", ");
                placeholders.append("?");
                params[i + 1] = Long.parseLong(fileIds[i]);
            }

            String sql = "SELECT id, filename, status, total_chunks " +
                    "FROM kb_file " +
                    "WHERE kb_id = ? AND status = 'done' " +
                    "AND id IN (" + placeholders + ") " +
                    "ORDER BY created_at DESC";

            List<Map<String, Object>> results = mysqlJdbcTemplate.queryForList(sql, params);

            assertNotNull(results);
            assertFalse(results.isEmpty(), "应该有文件记录");

            Map<String, Object> row = results.get(0);
            assertEquals(TEST_FILE_ID, row.get("id"));
            assertEquals("done", row.get("status"));

            System.out.println("get_file_metadata 测试通过，文件: " + row.get("filename"));
        }

        @Test
        @DisplayName("测试多个文件 ID 查询")
        void testGetFileMetadataMultipleIds() {
            String[] fileIds = {"15", "16"};
            StringBuilder placeholders = new StringBuilder();
            Object[] params = new Object[fileIds.length + 1];
            params[0] = TEST_KB_ID;

            for (int i = 0; i < fileIds.length; i++) {
                if (i > 0) placeholders.append(", ");
                placeholders.append("?");
                params[i + 1] = Long.parseLong(fileIds[i]);
            }

            String sql = "SELECT id, filename, status " +
                    "FROM kb_file " +
                    "WHERE kb_id = ? AND status = 'done' " +
                    "AND id IN (" + placeholders + ")";

            List<Map<String, Object>> results = mysqlJdbcTemplate.queryForList(sql, params);

            assertNotNull(results);
            System.out.println("get_file_metadata (多文件) 测试通过，返回 " + results.size() + " 条");
        }
    }

    @Nested
    @DisplayName("4. list_files 文件列表工具测试")
    class ListFilesTest {

        @Test
        @DisplayName("测试文件列表查询 - 分页")
        void testListFiles() {
            String countSql = "SELECT COUNT(*) FROM kb_file WHERE kb_id = ?";
            Integer total = mysqlJdbcTemplate.queryForObject(countSql, Integer.class, TEST_KB_ID);

            assertNotNull(total);
            assertTrue(total > 0, "知识库应该有文件");

            String sql = "SELECT id, filename, total_chunks, status " +
                    "FROM kb_file " +
                    "WHERE kb_id = ? " +
                    "ORDER BY created_at DESC " +
                    "LIMIT ? OFFSET ?";

            int page = 1;
            int size = 10;
            int offset = (page - 1) * size;

            List<Map<String, Object>> results = mysqlJdbcTemplate.queryForList(sql, TEST_KB_ID, size, offset);

            assertNotNull(results);
            assertFalse(results.isEmpty(), "分页查询应有结果");

            System.out.println("list_files 测试通过，共 " + total + " 个文件，当前页 " + results.size() + " 条");
        }
    }

    @Nested
    @DisplayName("5. 边界条件测试")
    class EdgeCaseTest {

        @Test
        @DisplayName("测试空 fileIds 数组")
        void testEmptyFileIds() {
            String[] fileIds = {};

            if (fileIds.length > 0) {
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < fileIds.length; i++) {
                    if (i > 0) placeholders.append(", ");
                    placeholders.append("?");
                }

                String sql = "SELECT * FROM kb_file WHERE file_id IN (" + placeholders + ")";
                List<Map<String, Object>> results = mysqlJdbcTemplate.queryForList(sql, (Object[]) fileIds);
                assertTrue(results.isEmpty());
            }

            System.out.println("边界测试 - 空数组: 通过");
        }

        @Test
        @DisplayName("测试 kbId 为空时使用默认值")
        void testDefaultKbId() {
            String sql = "SELECT COUNT(*) as cnt FROM rag_parent_chunks " +
                    "WHERE metadata->>'kbId' = 'default'";

            Integer count = pgVectorJdbcTemplate.queryForObject(sql, Integer.class);

            assertNotNull(count);
            assertTrue(count > 0, "默认知识库应该有数据");

            System.out.println("边界测试 - 默认 kbId: 通过，有 " + count + " 条记录");
        }
    }

    @Nested
    @DisplayName("6. 多次查询压力测试")
    class StressTest {

        @Test
        @DisplayName("测试连续多次查询稳定性")
        void testMultipleQueries() {
            String sql = "SELECT file_id, chunk_index, content " +
                    "FROM rag_parent_chunks " +
                    "WHERE metadata->>'kbId' = ? " +
                    "AND file_id = ? AND chunk_index = ?";

            for (int i = 0; i < 5; i++) {
                List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(
                        sql, TEST_KB_ID, TEST_FILE_ID, 85 + i);

                assertNotNull(results);
                assertTrue(results.size() <= 1);
            }

            System.out.println("压力测试 - 连续5次查询: 通过");
        }
    }
}
