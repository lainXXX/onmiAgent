package top.javarem.skillDemo.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;

// 1. 选项定义（支持预览）
public record Option(
    @JsonProperty(required = true) String label,
    @JsonProperty(required = true) String description,
    // preview 仅单选可用，用于展示代码片段、ASCII UI草图或配置对比
    String preview
) {
    // 兼容旧构造函数
    public Option(String label, String description) {
        this(label, description, null);
    }
}

// 2. 问题定义
record Question(
    @JsonProperty(required = true) String header,
    @JsonProperty(required = true) String question,
    @JsonProperty(required = true) List<Option> options,
    @JsonProperty(required = true) boolean multiSelect
) {}

// 3. 工具请求参数外层包装
record AskUserQuestionRequest(
    @ToolParam(description = "问题数组，支持多个问题")
    @JsonProperty(required = true)
    List<Question> questions
) {}