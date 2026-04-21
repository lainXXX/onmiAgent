package top.javarem.omni.advisor.hook.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import top.javarem.omni.advisor.hook.CompressionHook;
import top.javarem.omni.config.ContextCompressionProperties;
import top.javarem.omni.model.compression.PipelineResult;
import top.javarem.omni.model.compression.TokenEstimator;
import top.javarem.omni.service.compression.CompressionPipeline;
import top.javarem.omni.service.compression.context.CompactionContext;

import java.util.List;

/**
 * 压缩 Hook 实现
 *
 * <p>使用 CompressionPipeline 执行四层压缩。
 */
@Slf4j
@Component
public class CompressionHookImpl implements CompressionHook {

    private final CompressionPipeline pipeline;
    private final ContextCompressionProperties properties;

    public CompressionHookImpl(CompressionPipeline pipeline,
                               ContextCompressionProperties properties) {
        this.pipeline = pipeline;
        this.properties = properties;
    }

    @Override
    public List<Message> execute(ChatClientRequest request,
                                ChatClientResponse response,
                                ToolExecutionResult result) {

        if (!properties.isEnabled()) {
            log.debug("压缩已禁用，跳过");
            return null;
        }

        // 获取工具执行结果中的消息历史
        List<Message> messages = result.conversationHistory();
        if (messages == null || messages.isEmpty()) {
            log.debug("无消息需要压缩");
            return null;
        }

        // 构建压缩上下文
        CompactionContext ctx = CompactionContext.builder()
                .request(request)
                .properties(properties)
                .preTokens(TokenEstimator.estimateMessages(messages))
                .postTokens(TokenEstimator.estimateMessages(messages))
                .tokenUsage((double) TokenEstimator.estimateMessages(messages) / properties.getContextWindow())
                .build();

        // 执行压缩流水线
        PipelineResult pipelineResult = pipeline.execute(ctx, messages);

        if (pipelineResult.isWasCompacted()) {
            log.info("压缩完成: 释放 {} tokens, 压缩前 {} tokens, 压缩后 {} tokens",
                    pipelineResult.getTotalTokensFreed(),
                    pipelineResult.getPreTokens(),
                    pipelineResult.getPostTokens());
            return pipelineResult.getMessages();
        }

        log.debug("压缩未执行（无工作需要做）");
        return null;
    }
}
