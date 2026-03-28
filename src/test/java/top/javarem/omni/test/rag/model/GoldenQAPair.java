package top.javarem.omni.test.rag.model;

import java.util.List;

/**
 * 黄金问答对 - 用于 RAG 测试的基准数据
 *
 * @param question 问题文本
 * @param expectedParentIds 期望召回的母块 ID 列表
 * @param keywords 关键词列表
 * @param expectedSummary 期望的回答要点
 * @param type 问题类型
 */
public record GoldenQAPair(
    String question,
    List<String> expectedParentIds,
    List<String> keywords,
    String expectedSummary,
    QuestionType type
) {}
