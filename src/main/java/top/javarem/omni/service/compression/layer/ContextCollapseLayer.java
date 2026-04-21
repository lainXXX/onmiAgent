package top.javarem.omni.service.compression.layer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.compression.CollapseState;
import top.javarem.omni.model.compression.LayerResult;
import top.javarem.omni.model.compression.TokenEstimator;
import top.javarem.omni.service.compression.context.CompactionContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 3: Context Collapse - 上下文折叠
 *
 * <p>功能：
 * <ul>
 *   <li>90% 阈值：进入 Commit 模式，生成折叠视图</li>
 *   <li>95% 阈值：进入 Block 模式，阻塞新 API 调用</li>
 *   <li>保留完整 commit log，支持展开</li>
 * </ul>
 *
 * <p>此层按需执行，达到阈值才触发。
 */
@Slf4j
@Component
public class ContextCollapseLayer implements CompressionLayer {

    private static final int ORDER = 3;
    private static final String NAME = "ContextCollapse";

    /**
     * Commit 模式阈值
     */
    private static final double COMMIT_THRESHOLD = 0.90;

    /**
     * Block 模式阈值
     */
    private static final double BLOCK_THRESHOLD = 0.95;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(CompactionContext ctx) {
        // 默认禁用，可以通过配置启用
        return ctx.getProperties() != null && ctx.getProperties().isCollapseEnabled();
    }

    @Override
    public boolean shouldSkip(CompactionContext ctx, List<Message> messages) {
        // 未达到阈值，跳过
        if (ctx.getTokenUsage() < COMMIT_THRESHOLD) {
            return true;
        }
        return false;
    }

    @Override
    public LayerResult compress(CompactionContext ctx, List<Message> messages) {
        long preTokens = TokenEstimator.estimateMessages(messages);
        long startTime = System.currentTimeMillis();

        double usage = ctx.getTokenUsage();
        CollapseState newState;

        if (usage >= BLOCK_THRESHOLD) {
            newState = CollapseState.BLOCK;
            log.warn("Context Collapse: BLOCK 模式触发，使用率 {}%", String.format("%.1f", usage * 100));
        } else {
            newState = CollapseState.COMMIT;
            log.info("Context Collapse: COMMIT 模式触发，使用率 {}%", String.format("%.1f", usage * 100));
        }

        // 更新上下文状态
        ctx.setCollapseState(newState);

        // 生成折叠视图
        List<Message> compacted = generateCollapsedView(messages, newState);

        long freedTokens = preTokens - TokenEstimator.estimateMessages(compacted);
        Duration executionTime = Duration.ofMillis(System.currentTimeMillis() - startTime);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("collapseState", newState);
        metadata.put("originalMessageCount", messages.size());
        metadata.put("collapsedMessageCount", compacted.size());

        return LayerResult.builder()
                .layerOrder(ORDER)
                .layerName(NAME)
                .messages(compacted)
                .tokensFreed(freedTokens)
                .messagesCleared(messages.size() - compacted.size())
                .didWork(true)
                .executionTime(executionTime)
                .metadata(metadata)
                .build();
    }

    /**
     * 生成折叠视图
     */
    private List<Message> generateCollapsedView(List<Message> messages, CollapseState state) {
        List<Message> result = new ArrayList<>();

        // 保留系统消息
        for (Message msg : messages) {
            if (msg instanceof SystemMessage) {
                result.add(msg);
            }
        }

        // 添加折叠标记消息
        String collapseMarker = buildCollapseMarker(messages.size(), state);
        result.add(new UserMessage(collapseMarker));

        // 保留最近的消息（用于连贯性）
        int keepRecent = state == CollapseState.BLOCK ? 5 : 3;
        if (messages.size() > keepRecent) {
            result.addAll(messages.subList(messages.size() - keepRecent, messages.size()));
        }

        return result;
    }

    /**
     * 构建折叠标记消息
     */
    private String buildCollapseMarker(int totalMessages, CollapseState state) {
        String mode = state == CollapseState.BLOCK ? "⚠️ 阻塞模式" : "📋 折叠视图";

        return String.format("""
                [上下文折叠]

                %s
                上下文已达到较高使用率，早期消息已折叠。
                折叠消息数：%d 条

                可使用 /expand 查看完整历史，或等待自动压缩释放空间。
                """, mode, totalMessages);
    }
}
