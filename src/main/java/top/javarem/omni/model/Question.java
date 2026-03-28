package top.javarem.omni.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 问题定义
 *
 * @param header     短标签，最多 12 字符
 * @param question   完整问题，以问号结尾
 * @param options    2~4 个选项
 * @param multiSelect true=多选，false=单选
 */
public record Question(
    @JsonProperty(required = true) String header,
    @JsonProperty(required = true) String question,
    @JsonProperty(required = true) List<Option> options,
    @JsonProperty(required = true) boolean multiSelect
) {}
