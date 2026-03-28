package top.javarem.omni.model;

import java.util.Map;

/**
 * 响应结构（联合类型）
 *
 * @param answers     正常回答 { "问题1": "选项A" }
 * @param annotations 批注 { "问题1": { "preview": "...", "notes": "..." } }
 * @param timeout     超时标记
 * @param skipReason  跳过原因（如果有）
 */
public record AskUserResponse(
    Map<String, String> answers,
    Map<String, UserAnnotation> annotations,
    boolean timeout,
    String skipReason
) {
    /**
     * 工厂方法：创建超时响应
     */
    public static AskUserResponse timeoutResponse() {
        return new AskUserResponse(Map.of(), Map.of(), true, null);
    }

    /**
     * 工厂方法：创建跳过响应
     */
    public static AskUserResponse skipped(String reason) {
        return new AskUserResponse(Map.of(), Map.of(), false, reason);
    }

    /**
     * 工厂方法：创建正常响应
     */
    public static AskUserResponse of(
            Map<String, String> answers,
            Map<String, UserAnnotation> annotations) {
        return new AskUserResponse(answers, annotations, false, null);
    }
}
