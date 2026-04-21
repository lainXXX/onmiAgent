package top.javarem.omni.service.compression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.compression.LayerResult;
import top.javarem.omni.model.compression.PipelineResult;
import top.javarem.omni.model.compression.TokenEstimator;
import top.javarem.omni.service.compression.context.CompactionContext;
import top.javarem.omni.service.compression.layer.CompressionLayer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 压缩流水线编排器
 *
 * <p>负责按顺序执行所有压缩层，并汇总结果。
 */
@Slf4j
@Component
public class CompressionPipeline {

    private final List<CompressionLayer> layers;

    public CompressionPipeline(List<CompressionLayer> layers) {
        // 按 order 排序
        this.layers = layers.stream()
                .sorted(Comparator.comparingInt(CompressionLayer::getOrder))
                .toList();
        log.info("CompressionPipeline 初始化，层数: {}", this.layers.size());
        for (CompressionLayer layer : this.layers) {
            log.info("  - Layer {}: {}", layer.getOrder(), layer.getName());
        }
    }

    /**
     * 执行压缩流水线
     *
     * @param ctx 压缩上下文
     * @param messages 原始消息列表
     * @return 流水线执行结果
     */
    public PipelineResult execute(CompactionContext ctx, List<Message> messages) {
        long startTime = System.currentTimeMillis();
        long preTokens = TokenEstimator.estimateMessages(messages);

        // 记录压缩前状态
        ctx.setPreTokens(preTokens);
        ctx.setPreCompactMessages(messages);

        List<Message> current = new ArrayList<>(messages);
        List<LayerResult> layerResults = new ArrayList<>();
        long totalFreed = 0;

        // ========== 临时调试日志：Pipeline 压缩前消息列表 ==========
        log.info("========== CompressionPipeline 原始消息 ==========");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String msgType = msg.getClass().getSimpleName();
            String textPreview = getTextPreview(msg.getText(), 100);
            boolean hasToolCalls = hasToolCalls(msg);
            log.info("  [{}] {}: text长度={}, hasToolCalls={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    hasToolCalls,
                    textPreview);
        }
        log.info("  总计: {} 条消息, {} tokens", messages.size(), preTokens);
        log.info("================================================");

        // 按顺序执行每一层
        for (CompressionLayer layer : layers) {
            // 检查是否启用
            if (!layer.isEnabled(ctx)) {
                log.debug("Layer {} 跳过：未启用", layer.getName());
                continue;
            }

            // 快速检查是否跳过
            if (layer.shouldSkip(ctx, current)) {
                log.debug("Layer {} 跳过：shouldSkip 返回 true", layer.getName());
                layerResults.add(LayerResult.noOp(layer.getOrder(), layer.getName()));
                continue;
            }

            // 执行压缩
            log.info(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
            log.info("Layer {} 开始执行...", layer.getName());
            LayerResult result = layer.compress(ctx, current);

            // 更新当前消息
            current = result.getMessages();
            totalFreed += result.getTokensFreed();
            layerResults.add(result);

            log.info("Layer {} 执行完成: {} tokens freed, {} messages cleared, didWork={}",
                    layer.getName(),
                    result.getTokensFreed(),
                    result.getMessagesCleared(),
                    result.isDidWork());
            log.info(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");

            // 如果此层执行了压缩，更新上下文的 token 使用量
            if (result.isDidWork()) {
                long postTokens = TokenEstimator.estimateMessages(current);
                ctx.setPostTokens(postTokens);
                ctx.setTokenUsage((double) postTokens / ctx.getContextWindow());
            }
        }

        // 记录压缩后状态
        ctx.setPostCompactMessages(current);
        long postTokens = TokenEstimator.estimateMessages(current);
        ctx.setPostTokens(postTokens);

        // ========== 临时调试日志：Pipeline 压缩后消息列表 ==========
        log.info("========== CompressionPipeline 压缩后消息 ==========");
        for (int i = 0; i < current.size(); i++) {
            Message msg = current.get(i);
            String msgType = msg.getClass().getSimpleName();
            String textPreview = getTextPreview(msg.getText(), 100);
            boolean hasToolCalls = hasToolCalls(msg);
            log.info("  [{}] {}: text长度={}, hasToolCalls={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    hasToolCalls,
                    textPreview);
        }
        log.info("  总计: {} 条消息, {} tokens (释放: {}), 降幅: {:.1f}%",
                current.size(), postTokens, totalFreed, 100.0 * totalFreed / preTokens);
        log.info("==============================================");

        Duration totalTime = Duration.ofMillis(System.currentTimeMillis() - startTime);

        PipelineResult pipelineResult = PipelineResult.of(
                current,
                totalFreed,
                layerResults,
                preTokens,
                postTokens,
                totalTime
        );

        log.info("CompressionPipeline 执行完成: 释放 {} tokens, 执行层数: {}, 耗时: {}ms",
                totalFreed,
                pipelineResult.getExecutedLayerCount(),
                totalTime.toMillis());

        return pipelineResult;
    }

    private String getTextPreview(String text, int maxLen) {
        if (text == null) return "null";
        if (text.length() <= maxLen) return text.replace("\n", "\\n");
        return text.substring(0, maxLen).replace("\n", "\\n") + "...";
    }

    private boolean hasToolCalls(Message msg) {
        if (msg instanceof org.springframework.ai.chat.messages.AssistantMessage) {
            org.springframework.ai.chat.messages.AssistantMessage am = (org.springframework.ai.chat.messages.AssistantMessage) msg;
            return am.getToolCalls() != null && !am.getToolCalls().isEmpty();
        }
        return false;
    }

    /**
     * 获取所有层
     */
    public List<CompressionLayer> getLayers() {
        return layers;
    }
}
