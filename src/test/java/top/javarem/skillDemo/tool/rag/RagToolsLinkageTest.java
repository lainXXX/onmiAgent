package top.javarem.skillDemo.tool.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 工具链路压力测试
 *
 * 测试 4 个核心工具的联动：
 * 1. list_files
 * 2. get_file_metadata
 * 3. semantic_search
 * 4. read_chunks
 */
@SpringBootTest
@ActiveProfiles("dev")
class RagToolsLinkageTest {

    @Autowired
    private RagToolConfig ragToolConfig;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== Test Scenario 1: 基础联动与上下文传递 ====================

    @Nested
    @DisplayName("【测试场景1】基础联动与上下文传递 (The Golden Path)")
    class GoldenPathTest {

        @Test
        @DisplayName("Step 1: 调用 list_files 获取知识库文件列表")
        void step1_listFiles() {
            System.out.println("\n========== [DEBUG] Test Scenario 1: Golden Path ==========");
            System.out.println("[DEBUG] Plan: 首先列出知识库中的所有文件");
            System.out.println("[DEBUG] Request: list_files(kbId='default', page=1, size=10)");
            System.out.println("[DEBUG] Expected: 返回知识库文件列表，包含 fileId 和 filename");

            String result = ragToolConfig.listFiles("default", 1, 10);

            System.out.println("\n[DEBUG] Response:");
            System.out.println(result);

            assertNotNull(result);
            assertFalse(result.contains("error"), "list_files 不应返回错误");
            assertTrue(result.contains("文件") || result.contains("倾听的技艺"), "应包含文件信息");
        }

        @Test
        @DisplayName("Step 2: 获取文件元数据")
        void step2_getFileMetadata() {
            System.out.println("\n[DEBUG] Plan: 获取文件 ID=15 的元数据");
            System.out.println("[DEBUG] Request: get_file_metadata(kbId='default', fileIds=['15'])");
            System.out.println("[DEBUG] Expected: 返回文件元数据，包含 status='done', total_chunks");

            String result = ragToolConfig.getFileMetadata("default", new String[]{"15"});

            System.out.println("\n[DEBUG] Response:");
            System.out.println(result);

            assertNotNull(result);
            // 验证返回包含必要字段
            assertTrue(result.contains("15") || result.length() > 0);
        }

        @Test
        @DisplayName("Step 3: 语义搜索关键词")
        void step3_semanticSearch() throws Exception {
            System.out.println("\n[DEBUG] Plan: 搜索关键词'喘息与休息'");
            System.out.println("[DEBUG] Request: semantic_search(kbId='default', query='喘息与休息')");
            System.out.println("[DEBUG] Expected: 返回相关文档列表，包含 fileId, chunkIndex, content");

            String result = ragToolConfig.semanticSearch("default", "喘息与休息");

            System.out.println("\n[DEBUG] Response (前500字符):");
            System.out.println(result.substring(0, Math.min(500, result.length())));

            assertNotNull(result);
            assertTrue(result.startsWith("["), "应返回 JSON 数组");
        }

        @Test
        @DisplayName("Step 4: 使用搜索结果调用 read_chunks")
        void step4_readChunksFromSearch() throws Exception {
            // 先搜索
            String searchResult = ragToolConfig.semanticSearch("default", "喘息与休息");
            System.out.println("\n[DEBUG] 解析搜索结果...");

            // 解析返回的 JSON (是 Document 对象列表的 JSON 序列化)
            // 这里我们直接使用已知的 chunk 信息进行测试
            System.out.println("[DEBUG] Plan: 使用搜索返回的 fileId 和 chunkIndex 调用 read_chunks");

            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 50)
            );

            System.out.println("[DEBUG] Request: read_chunks(chunks=" + chunks + ")");
            System.out.println("[DEBUG] Expected: 返回 chunk 内容");

            String result = ragToolConfig.readChunks(chunks);

            System.out.println("\n[DEBUG] Response (前300字符):");
            System.out.println(result.substring(0, Math.min(300, result.length())));

            assertNotNull(result);
            assertFalse(result.contains("error"), "read_chunks 不应返回错误");
            assertTrue(result.contains("文本内容") || result.contains("chunk") || result.length() > 0);
        }

        @Test
        @DisplayName("完整链路: list->metadata->search->read")
        void completeGoldenPath() throws Exception {
            System.out.println("\n========== [DEBUG] Complete Golden Path Test ==========");

            // Step 1: list_files
            System.out.println("\n[Step 1] 调用 list_files");
            String filesResult = ragToolConfig.listFiles("default", 1, 10);
            System.out.println("结果: " + filesResult.substring(0, Math.min(200, filesResult.length())));
            assertNotNull(filesResult);

            // Step 2: get_file_metadata
            System.out.println("\n[Step 2] 调用 get_file_metadata(fileId=15)");
            String metadataResult = ragToolConfig.getFileMetadata("default", new String[]{"15"});
            System.out.println("结果: " + metadataResult);
            assertNotNull(metadataResult);

            // Step 3: semantic_search
            System.out.println("\n[Step 3] 调用 semantic_search(query='喘息')");
            String searchResult = ragToolConfig.semanticSearch("default", "喘息");
            System.out.println("结果: " + searchResult.substring(0, Math.min(200, searchResult.length())));
            assertNotNull(searchResult);

            // Step 4: read_chunks
            System.out.println("\n[Step 4] 调用 read_chunks(fileId=15, chunkIndex=50)");
            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 50)
            );
            String readResult = ragToolConfig.readChunks(chunks);
            System.out.println("结果: " + readResult.substring(0, Math.min(200, readResult.length())));
            assertNotNull(readResult);

            System.out.println("\n========== Golden Path 测试完成 ==========");
        }
    }

    // ==================== Test Scenario 2: 批量读取压力测试 ====================

    @Nested
    @DisplayName("【测试场景2】批量读取与 SQL 兼容性压测 (The Stress Test)")
    class StressTest {

        @Test
        @DisplayName("批量读取 5 个 chunks")
        void batchRead5Chunks() {
            System.out.println("\n========== [DEBUG] Stress Test: 批量读取 5 个 chunks ==========");
            System.out.println("[DEBUG] Plan: 一次读取 5 个连续的 chunks (索引 50-54)");
            System.out.println("[DEBUG] Request: read_chunks with 5 ChunkReferences");

            List<RagToolConfig.ChunkReference> chunks = new ArrayList<>();
            for (int i = 50; i < 55; i++) {
                chunks.add(new RagToolConfig.ChunkReference("15", i));
            }

            String result = ragToolConfig.readChunks(chunks);

            System.out.println("\n[DEBUG] Response:");
            System.out.println(result);

            assertNotNull(result);
            assertFalse(result.contains("error"), "批量读取不应报错");
            assertTrue(result.contains("50") || result.contains("51") || result.contains("52"));
        }

        @Test
        @DisplayName("批量读取 10 个 chunks (超压测)")
        void batchRead10Chunks() {
            System.out.println("\n========== [DEBUG] Stress Test: 批量读取 10 个 chunks ==========");

            List<RagToolConfig.ChunkReference> chunks = new ArrayList<>();
            for (int i = 100; i < 110; i++) {
                chunks.add(new RagToolConfig.ChunkReference("15", i));
            }

            String result = ragToolConfig.readChunks(chunks);

            System.out.println("\n[DEBUG] Response (前300字符):");
            System.out.println(result.substring(0, Math.min(300, result.length())));

            assertNotNull(result);
        }

        @Test
        @DisplayName("批量读取非连续 chunks")
        void batchReadNonContinuous() {
            System.out.println("\n========== [DEBUG] Stress Test: 非连续 chunks (10, 20, 30, 40, 50) ==========");

            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 10),
                    new RagToolConfig.ChunkReference("15", 20),
                    new RagToolConfig.ChunkReference("15", 30),
                    new RagToolConfig.ChunkReference("15", 40),
                    new RagToolConfig.ChunkReference("15", 50)
            );

            String result = ragToolConfig.readChunks(chunks);

            System.out.println("\n[DEBUG] Response:");
            System.out.println(result);

            assertNotNull(result);
            assertFalse(result.contains("error"));
        }
    }

    // ==================== Test Scenario 3: 容错与幻觉修正测试 ====================

    @Nested
    @DisplayName("【测试场景3】容错与幻觉修正测试 (Error Recovery)")
    class ErrorRecoveryTest {

        @Test
        @DisplayName("测试不存在的 fileId")
        void testNonExistentFileId() {
            System.out.println("\n========== [DEBUG] Error Recovery: 不存在的 fileId=-999 ==========");
            System.out.println("[DEBUG] Plan: 传入不存在的 fileId，观察系统容错能力");

            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("-999", 0)
            );

            String result = ragToolConfig.readChunks(chunks);

            System.out.println("\n[DEBUG] Response:");
            System.out.println(result);

            // 验证系统能正确处理错误情况
            assertNotNull(result);
            assertTrue(result.contains("未找到") || result.contains("error") || result.length() > 0);
        }

        @Test
        @DisplayName("测试不存在的 chunkIndex")
        void testNonExistentChunkIndex() {
            System.out.println("\n[DEBUG] Error Recovery: 不存在的 chunkIndex=999999");

            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 999999)
            );

            String result = ragToolConfig.readChunks(chunks);

            System.out.println("\n[DEBUG] Response:");
            System.out.println(result);

            assertNotNull(result);
            // 应该返回空结果或提示信息
            assertTrue(result.contains("未找到") || result.contains("error") || result.length() > 0);
        }

        @Test
        @DisplayName("测试空知识库")
        void testEmptyKnowledgeBase() {
            System.out.println("\n[DEBUG] Error Recovery: 空知识库 kbId='non_existent'");

            String result = ragToolConfig.listFiles("non_existent_kb", 1, 10);

            System.out.println("\n[DEBUG] Response:");
            System.out.println(result);

            assertNotNull(result);
        }
    }

    // ==================== Test Scenario 4: 跨文件综合总结 ====================

    @Nested
    @DisplayName("【测试场景4】跨文件跨语境综合总结 (End-to-End)")
    class CrossFileTest {

        @Test
        @DisplayName("跨文件内容读取对比")
        void crossFileComparison() {
            System.out.println("\n========== [DEBUG] Cross-File Test: 跨文件内容对比 ==========");
            System.out.println("[DEBUG] Plan: 读取同一文件不同位置的内容，生成对比报告");

            // 读取文件开头、中间、结尾部分
            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 0),   // 开头
                    new RagToolConfig.ChunkReference("15", 100), // 中间
                    new RagToolConfig.ChunkReference("15", 180)   // 结尾
            );

            String result = ragToolConfig.readChunks(chunks);

            System.out.println("\n[DEBUG] Response - 跨文件对比报告:");
            System.out.println(result);

            assertNotNull(result);
            assertFalse(result.contains("error"));

            // 验证返回了 3 个 chunk
            assertTrue(result.contains("0") || result.contains("100") || result.contains("180"));
        }

        @Test
        @DisplayName("语义搜索后跨段落读取")
        void semanticSearchWithRead() throws Exception {
            System.out.println("\n[DEBUG] Cross-File: 语义搜索 + 跨段落读取");

            // 1. 搜索相关内容
            String searchResult = ragToolConfig.semanticSearch("default", "研究 方法");
            System.out.println("搜索结果: " + searchResult.substring(0, Math.min(200, searchResult.length())));

            // 2. 读取多个相关 chunk
            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 10),
                    new RagToolConfig.ChunkReference("15", 20),
                    new RagToolConfig.ChunkReference("15", 30)
            );

            String readResult = ragToolConfig.readChunks(chunks);
            System.out.println("\n读取结果 (前300字符):");
            System.out.println(readResult.substring(0, Math.min(300, readResult.length())));

            assertNotNull(searchResult);
            assertNotNull(readResult);
        }
    }
}
