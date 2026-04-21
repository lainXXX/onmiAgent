package top.javarem.omni.model.compression;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 压缩流水线执行结果 DTO
 */
@Data
@Builder
public class PipelineResult {

    /**
     * 压缩后的消息列表
     */
    private final List<Message> messages;

    /**
     * 总共释放的 token 数
     */
    private final long totalTokensFreed;

    /**
     * 各层结果列表
     */
    private final List<LayerResult> layerResults;

    /**
     * 压缩前 token 数
     */
    private final long preTokens;

    /**
     * 压缩后 token 数
     */
    private final long postTokens;

    /**
     * 总执行耗时
     */
    private final Duration totalExecutionTime;

    /**
     * 是否执行了压缩（至少有一层执行了工作）
     */
    private final boolean wasCompacted;

    /**
     * 创建成功结果
     */
    public static PipelineResult of(List<Message> messages,
                                   long totalTokensFreed,
                                   List<LayerResult> layerResults,
                                   long preTokens,
                                   long postTokens,
                                   Duration totalExecutionTime) {
        boolean wasCompacted = layerResults.stream()
                .anyMatch(LayerResult::isDidWork);
        return PipelineResult.builder()
                .messages(messages)
                .totalTokensFreed(totalTokensFreed)
                .layerResults(layerResults)
                .preTokens(preTokens)
                .postTokens(postTokens)
                .totalExecutionTime(totalExecutionTime)
                .wasCompacted(wasCompacted)
                .build();
    }

    /**
     * 创建未压缩结果（跳过了）
     */
    public static PipelineResult notCompacted(List<Message> messages, long preTokens) {
        return PipelineResult.builder()
                .messages(messages)
                .totalTokensFreed(0)
                .layerResults(List.of())
                .preTokens(preTokens)
                .postTokens(preTokens)
                .totalExecutionTime(Duration.ZERO)
                .wasCompacted(false)
                .build();
    }

    /**
     * 获取执行了的层数
     */
    public int getExecutedLayerCount() {
        return (int) layerResults.stream()
                .filter(LayerResult::isDidWork)
                .count();
    }

    /**
     * 获取层执行摘要
     */
    public String getSummary() {
        return layerResults.stream()
                .filter(LayerResult::isDidWork)
                .map(r -> String.format("%s: %d tokens freed",
                        r.getLayerName(), r.getTokensFreed()))
                .collect(Collectors.joining(", "));
    }
}
