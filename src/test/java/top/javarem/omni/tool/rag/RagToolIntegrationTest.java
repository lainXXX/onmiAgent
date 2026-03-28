package top.javarem.omni.tool.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 工具联动测试
 *
 * 测试场景：
 * 1. 用户想了解"访谈技巧"
 * 2. 先用 list_files 查看有哪些文件
 * 3. 用 get_file_metadata 获取文件详情
 * 4. 用 semantic_search 搜索相关内容
 * 5. 用 read_chunks 读取具体内容
 */
@SpringBootTest
@ActiveProfiles("dev")
class RagToolIntegrationTest {

    @Autowired
    private RagToolConfig ragToolConfig;

    @Nested
    @DisplayName("场景一：用户想了解访谈技巧")
    class InterviewScenarioTest {

        @Test
        @DisplayName("Step 1: 列出知识库文件")
        void step1_listFiles() {
            String result = ragToolConfig.listFiles("default", 1, 10);
            System.out.println("\n=== Step 1: 列出文件 ===");
            System.out.println(result);

            assertNotNull(result);
            assertFalse(result.contains("error"));
            // 应该返回文件列表
            assertTrue(result.contains("文件") || result.contains("倾听的技艺"));
        }

        @Test
        @DisplayName("Step 2: 获取文件元数据")
        void step2_getFileMetadata() {
            // 假设已知 fileId = "15"
            String result = ragToolConfig.getFileMetadata("default", new String[]{"15"});
            System.out.println("\n=== Step 2: 获取文件元数据 ===");
            System.out.println(result);

            assertNotNull(result);
            // 文件可能不存在，不强制要求包含特定内容
            assertTrue(result.length() > 0);
        }

        @Test
        @DisplayName("Step 3: 语义搜索访谈相关内容")
        void step3_semanticSearch() {
            // 模拟 semantic_search 工具
            String result = ragToolConfig.semanticSearch("default", "访谈技巧 提问方法");
            System.out.println("\n=== Step 3: 语义搜索 ===");
            System.out.println("返回结果长度: " + (result != null ? result.length() : 0));

            assertNotNull(result);
            // 语义搜索会返回 JSON 格式的文档列表
            assertTrue(result.startsWith("[") || result.contains("content"));
        }

        @Test
        @DisplayName("Step 4: 读取具体 chunk 内容")
        void step4_readChunks() {
            // 读取第85个chunk
            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 85)
            );

            String result = ragToolConfig.readChunks(chunks);
            System.out.println("\n=== Step 4: 读取 chunk 内容 ===");
            System.out.println(result.substring(0, Math.min(200, result.length())));

            assertNotNull(result);
            assertFalse(result.contains("error"));
            assertTrue(result.contains("访谈"));
        }
    }

    @Nested
    @DisplayName("场景二：完整工作流 - 从搜索到阅读")
    class CompleteWorkflowTest {

        @Test
        @DisplayName("完整流程测试")
        void completeWorkflow() {
            System.out.println("\n========== 完整工作流测试 ==========");

            // 1. 列出文件
            String filesResult = ragToolConfig.listFiles("default", 1, 10);
            System.out.println("\n[1] 列出文件结果:");
            System.out.println(filesResult);
            assertNotNull(filesResult);

            // 2. 获取文件元数据
            String metadataResult = ragToolConfig.getFileMetadata("default", new String[]{"15"});
            System.out.println("\n[2] 文件元数据:");
            System.out.println(metadataResult);
            assertNotNull(metadataResult);

            // 3. 语义搜索
            String searchResult = ragToolConfig.semanticSearch("default", "访谈");
            System.out.println("\n[3] 语义搜索结果 (前200字符):");
            System.out.println(searchResult != null ? searchResult.substring(0, Math.min(200, searchResult.length())) : "null");
            assertNotNull(searchResult);

            // 4. 读取 chunk
            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 0),
                    new RagToolConfig.ChunkReference("15", 1)
            );
            String readResult = ragToolConfig.readChunks(chunks);
            System.out.println("\n[4] 读取 chunk 结果 (前200字符):");
            System.out.println(readResult.substring(0, Math.min(200, readResult.length())));
            assertNotNull(readResult);

            System.out.println("\n========== 工作流测试完成 ==========");
        }

        @Test
        @DisplayName("多文件场景测试")
        void multiFileWorkflow() {
            System.out.println("\n========== 多文件场景测试 ==========");

            // 1. 获取多个文件的元数据
            String[] fileIds = {"15"};
            String metadataResult = ragToolConfig.getFileMetadata("default", fileIds);
            System.out.println("\n[多文件] 获取文件元数据:");
            System.out.println(metadataResult);

            // 2. 读取多个 chunks
            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 10),
                    new RagToolConfig.ChunkReference("15", 20),
                    new RagToolConfig.ChunkReference("15", 30)
            );
            String readResult = ragToolConfig.readChunks(chunks);
            System.out.println("\n[多文件] 读取多个 chunks:");
            System.out.println(readResult);

            assertNotNull(metadataResult);
            assertNotNull(readResult);

            System.out.println("\n========== 多文件场景测试完成 ==========");
        }
    }

    @Nested
    @DisplayName("场景三：异常处理和边界情况")
    class EdgeCaseWorkflowTest {

        @Test
        @DisplayName("空知识库场景")
        void emptyKnowledgeBase() {
            // 测试不存在的 kbId
            String result = ragToolConfig.listFiles("non_existent_kb", 1, 10);
            System.out.println("\n[边界] 空知识库测试:");
            System.out.println(result);

            assertNotNull(result);
        }

        @Test
        @DisplayName("不存在的文件ID")
        void nonExistentFile() {
            String result = ragToolConfig.getFileMetadata("default", new String[]{"99999"});
            System.out.println("\n[边界] 不存在的文件ID:");
            System.out.println(result);

            // 应该返回空结果而不是错误
            assertNotNull(result);
        }

        @Test
        @DisplayName("不存在的 chunk")
        void nonExistentChunk() {
            List<RagToolConfig.ChunkReference> chunks = List.of(
                    new RagToolConfig.ChunkReference("15", 999999)
            );
            String result = ragToolConfig.readChunks(chunks);
            System.out.println("\n[边界] 不存在的 chunk:");
            System.out.println(result);

            assertNotNull(result);
            assertTrue(result.contains("未找到") || result.contains("error"));
        }

        @Test
        @DisplayName("分页测试")
        void paginationTest() {
            // 第一页
            String page1 = ragToolConfig.listFiles("default", 1, 5);
            System.out.println("\n[分页] 第1页:");
            System.out.println(page1);

            // 第二页
            String page2 = ragToolConfig.listFiles("default", 2, 5);
            System.out.println("\n[分页] 第2页:");
            System.out.println(page2);

            assertNotNull(page1);
            assertNotNull(page2);
        }
    }
}
