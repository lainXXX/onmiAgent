package top.javarem.omni.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 选项定义（支持预览）
 *
 * @param label       选项名称，1~5 个词
 * @param description 选项详细解释
 * @param preview     仅单选可用，用于展示代码片段、ASCII UI 草图或配置对比
 */
public record Option(
    @JsonProperty("label") String label,
    @JsonProperty("description") String description,
    @JsonProperty("preview") String preview
) {
    /**
     * 兼容旧构造函数（无 preview）
     */
    public Option(String label, String description) {
        this(label, description, null);
    }
}
