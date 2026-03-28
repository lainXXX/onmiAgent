package top.javarem.omni.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rerank 服务测试
 */
@Slf4j
@SpringBootTest
public class RerankServiceTest {

    @Autowired
    private RerankService rerankService;

    @Test
    void testRerankWithScore() {
        String query = "苹果的营养价值";

        List<String> documents = Arrays.asList(
            "苹果是一种常见的水果，富含维生素C和纤维素。",
            "香蕉含有丰富的钾元素，对心脏健康有益。",
            "苹果是红色的，味道甜美多汁。",
            "橙子富含维生素C，可以增强免疫力。",
            "苹果的营养价值很高，含有多种维生素和矿物质。"
        );

        log.info("查询: {}", query);
        log.info("原始文档顺序: ");
        for (int i = 0; i < documents.size(); i++) {
            log.info("  [{}] {}", i, documents.get(i));
        }

        List<RerankService.RerankDocument> results = rerankService.rerankWithScore(query, documents);

        assertNotNull(results, "结果不应为空");
        assertFalse(results.isEmpty(), "结果列表不应为空");

        log.info("\nRerank 后的结果（按相关性排序）: ");
        for (int i = 0; i < results.size(); i++) {
            RerankService.RerankDocument doc = results.get(i);
            log.info("  [{}] 分数: {:.4f} - {}", i, doc.getScore(), doc.getText());
        }

        // 验证第一个结果的相关性分数最高
        double firstScore = results.get(0).getScore();
        for (RerankService.RerankDocument doc : results) {
            assertTrue(doc.getScore() <= firstScore, "结果应该按分数降序排列");
            firstScore = doc.getScore();
        }

        log.info("\n✅ Rerank 测试通过!");
    }

    @Test
    void testRerankAndGetDocuments() {
        String query = "如何联系客服";

        List<String> documents = Arrays.asList(
            "客服电话：400-123-4567，工作时间9:00-18:00。",
            "产品价格：299元。",
            "您可以拨打客服热线或发送邮件联系客服。",
            "订单配送时间一般为2-3天。",
            "客服邮箱：support@example.com。"
        );

        List<String> results = rerankService.rerankAndGetDocuments(query, documents);

        assertNotNull(results, "结果不应为空");
        assertFalse(results.isEmpty(), "结果列表不应为空");

        log.info("查询: {}", query);
        log.info("Rerank 后的文档: ");
        for (int i = 0; i < results.size(); i++) {
            log.info("  [{}] {}", i, results.get(i));
        }

        // 验证客服相关的文档排在前面
        String firstDoc = results.get(0);
        assertTrue(firstDoc.contains("客服") || firstDoc.contains("电话") || firstDoc.contains("热线"),
                "最相关的文档应该包含客服相关信息");

        log.info("\n✅ Rerank 文档列表测试通过!");
    }
}
