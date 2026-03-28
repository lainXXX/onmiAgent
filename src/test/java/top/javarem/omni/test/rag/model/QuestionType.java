package top.javarem.omni.test.rag.model;

/**
 * 问题类型枚举
 */
public enum QuestionType {
    FACTUAL,      // 事实性问题（直接命中）
    INFERENCE,   // 推理性问题（需整合多段落）
    MULTI_HOP    // 多跳问题（需跨文件推理）
}
