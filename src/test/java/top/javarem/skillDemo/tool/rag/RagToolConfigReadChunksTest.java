package top.javarem.skillDemo.tool.rag;

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
 * 测试 RagToolConfig 的 readChunks 方法
 */
@SpringBootTest
@ActiveProfiles("dev")
class RagToolConfigReadChunksTest {

    @Autowired
    private RagToolConfig ragToolConfig;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    /**
     * 测试单个 chunk 查询 - 使用 =
     */
    @Test
    void testReadSingleChunk() {
        // 直接执行 SQL 验证语法正确
        String sql = """
            SELECT file_id AS "文件ID",
                   chunk_index AS "序号",
                   content AS "文本内容"
            FROM rag_parent_chunks
            WHERE metadata->>'kbId' = ?
            AND (file_id = ? AND chunk_index = ?)
            ORDER BY file_id, chunk_index ASC
            """;

        // 执行查询验证 SQL 语法正确
        List<Object> params = List.of("default", 15L, 85);
        List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(sql, params.toArray());

        assertNotNull(results);
        System.out.println("Single chunk query result: " + results.size() + " rows");
    }

    /**
     * 测试多个 chunks 查询 - 使用 IN
     */
    @Test
    void testReadMultipleChunks() {
        // 直接执行 SQL 验证语法正确
        String sql = """
            SELECT file_id AS "文件ID",
                   chunk_index AS "序号",
                   content AS "文本内容"
            FROM rag_parent_chunks
            WHERE metadata->>'kbId' = ?
            AND (file_id, chunk_index) IN ((?, ?), (?, ?))
            ORDER BY file_id, chunk_index ASC
            """;

        // 执行查询验证 SQL 语法正确
        List<Object> params = List.of("default", 15L, 176, 15L, 177);
        List<Map<String, Object>> results = pgVectorJdbcTemplate.queryForList(sql, params.toArray());

        assertNotNull(results);
        System.out.println("Multiple chunks query result: " + results.size() + " rows");
    }

    /**
     * 测试工具方法 - 单个 chunk
     */
    @Test
    void testReadChunksToolSingle() {
        List<RagToolConfig.ChunkReference> chunks = List.of(
                new RagToolConfig.ChunkReference("15", 85)
        );

        String result = ragToolConfig.readChunks(chunks);

        assertNotNull(result);
        assertFalse(result.contains("error"));
        System.out.println("Single chunk tool result: " + result);
    }

    /**
     * 测试工具方法 - 多个 chunks
     */
    @Test
    void testReadChunksToolMultiple() {
        List<RagToolConfig.ChunkReference> chunks = List.of(
                new RagToolConfig.ChunkReference("15", 176),
                new RagToolConfig.ChunkReference("15", 177)
        );

        String result = ragToolConfig.readChunks(chunks);

        assertNotNull(result);
        assertFalse(result.contains("error"));
        System.out.println("Multiple chunks tool result: " + result);
    }
}
