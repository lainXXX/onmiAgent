package top.javarem.skillDemo.test.rag.model;

import java.util.List;
import java.util.Map;

/**
 * 测试用例结果 - 记录每个测试的执行结果
 *
 * @param question 问题文本
 * @param type 问题类型
 * @param recallScore 召回率 (0.0 - 1.0)
 * @param precisionScore 精确率 (0.0 - 1.0)
 * @param mrrScore MRR 分数 (Mean Reciprocal Rank)
 * @param actualParentIds 实际召回的母块 ID 列表
 * @param expectedParentIds 期望召回的母块 ID 列表
 * @param metadata 附加元数据
 * @param passed 是否通过
 */
public record TestCaseResult(
    String question,
    QuestionType type,
    double recallScore,
    double precisionScore,
    double mrrScore,
    List<String> actualParentIds,
    List<String> expectedParentIds,
    Map<String, Object> metadata,
    boolean passed
) {
    /**
     * 便捷构造函数 - 不需要 metadata 时使用
     */
    public TestCaseResult(String question, QuestionType type, double recallScore,
                          double precisionScore, double mrrScore,
                          List<String> actualParentIds, List<String> expectedParentIds,
                          boolean passed) {
        this(question, type, recallScore, precisionScore, mrrScore,
             actualParentIds, expectedParentIds, Map.of(), passed);
    }
}
