package top.javarem.skillDemo.test.rag;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import top.javarem.skillDemo.test.rag.model.GoldenQAPair;
import top.javarem.skillDemo.test.rag.model.TestCaseResult;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class SemanticRecallTester {

    private final VectorStore vectorStore;
    private final TestDataManager testDataManager;

    public SemanticRecallTester(VectorStore vectorStore, TestDataManager testDataManager) {
        this.vectorStore = vectorStore;
        this.testDataManager = testDataManager;
    }

    /**
     * 测试单个问答对的召回效果
     */
    public TestCaseResult testSingleRecall(GoldenQAPair testCase) {
        // 1. 执行向量搜索
        SearchRequest searchRequest = SearchRequest.builder()
                .query(testCase.question())
                .topK(5)
                .build();

        List<Document> childDocs = vectorStore.similaritySearch(searchRequest);

        // 2. 提取返回结果中的 parentId
        Set<String> actualParentIds = childDocs.stream()
                .filter(doc -> doc.getMetadata().get("parentId") != null)
                .map(doc -> (String) doc.getMetadata().get("parentId"))
                .collect(Collectors.toSet());

        // 3. 计算召回指标
        Set<String> expectedParentIds = new HashSet<>(testCase.expectedParentIds());

        double recall = calculateRecall(actualParentIds, expectedParentIds);
        double precision = calculatePrecision(actualParentIds, expectedParentIds);
        double mrr = calculateMRR(actualParentIds, expectedParentIds);

        boolean passed = recall >= 0.5; // 召回率 >= 50% 视为通过

        log.info("测试问题: {}", testCase.question());
        log.info("期望母块: {}", expectedParentIds);
        log.info("实际召回: {} | Recall: {} | Precision: {} | MRR: {}",
                actualParentIds, recall, precision, mrr);

        return new TestCaseResult(
                testCase.question(),
                testCase.type(),
                recall,
                precision,
                mrr,
                new ArrayList<>(actualParentIds),
                testCase.expectedParentIds(),
                Map.of("childDocsCount", childDocs.size()),
                passed
        );
    }

    /**
     * 计算召回率 (Recall@K)
     */
    private double calculateRecall(Set<String> actual, Set<String> expected) {
        if (expected.isEmpty()) return 0.0;
        long matchCount = actual.stream().filter(expected::contains).count();
        return (double) matchCount / expected.size();
    }

    /**
     * 计算精确率 (Precision@K)
     */
    private double calculatePrecision(Set<String> actual, Set<String> expected) {
        if (actual.isEmpty()) return 0.0;
        long matchCount = actual.stream().filter(expected::contains).count();
        return (double) matchCount / actual.size();
    }

    /**
     * 计算 MRR (Mean Reciprocal Rank)
     */
    private double calculateMRR(Set<String> actual, Set<String> expected) {
        if (actual.isEmpty() || expected.isEmpty()) return 0.0;
        List<String> actualList = new ArrayList<>(actual);
        for (int i = 0; i < actualList.size(); i++) {
            if (expected.contains(actualList.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    @Test
    void testSemanticRecallAll() {
        List<GoldenQAPair> testCases = testDataManager.getAllTestCases();
        List<TestCaseResult> results = new ArrayList<>();

        for (GoldenQAPair testCase : testCases) {
            TestCaseResult result = testSingleRecall(testCase);
            results.add(result);
        }

        // 计算总体指标
        double avgRecall = results.stream().mapToDouble(TestCaseResult::recallScore).average().orElse(0);
        double avgPrecision = results.stream().mapToDouble(TestCaseResult::precisionScore).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(TestCaseResult::mrrScore).average().orElse(0);
        long passedCount = results.stream().filter(TestCaseResult::passed).count();

        log.info("========== 语义召回测试报告 ==========");
        log.info("总测试用例: {}", testCases.size());
        log.info("通过: {}", passedCount);
        log.info("平均召回率: {:.2f}%", avgRecall * 100);
        log.info("平均精确率: {:.2f}%", avgPrecision * 100);
        log.info("平均 MRR: {:.2f}", avgMrr);

        // 断言：平均召回率 >= 50%
        assertTrue(avgRecall >= 0.5, "平均召回率低于 50%");
    }
}
