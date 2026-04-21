package top.javarem.omni.service.compression.layer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.compression.LayerResult;
import top.javarem.omni.model.compression.TokenEstimator;
import top.javarem.omni.service.compression.context.CompactionContext;
import top.javarem.omni.tool.AgentTool;
import top.javarem.omni.tool.ToolsManager;

import java.time.Duration;
import java.util.*;

/**
 * Layer 2: MicroCompact - 时间衰减触发的小规模压缩
 *
 * <p>功能：
 * <ul>
 *   <li>距离上次 Assistant 消息超过 gapThresholdMinutes 时触发</li>
 *   <li>将较旧的工具结果替换为占位符，仅保留最近 keepRecent 条完整结果</li>
 * </ul>
 *
 * <p>此层按需执行（时间衰减触发）。压缩时保留工具调用结构，
 * 只将结果替换为占位符。
 */
@Slf4j
@Component
public class MicroCompactLayer implements CompressionLayer {

    private static final int ORDER = 2;
    private static final String NAME = "MicroCompact";

    /**
     * 默认时间衰减阈值（分钟）
     */
    private static final int DEFAULT_GAP_THRESHOLD_MINUTES = 60;

    /**
     * 默认保留最近完整工具结果数
     */
    private static final int DEFAULT_KEEP_RECENT = 5;

    /**
     * 占位符文案，用于替换被压缩的旧工具消息
     */
    private static final String TOOL_RESULT_CLEARED = "[Old tool result content cleared]";

    /**
     * 可压缩的工具名称白名单（搜索/读取类）
     */
    private static final Set<String> COMPACTABLE_TOOL_NAMES = Set.of(
            "read", "grep", "glob", "web_search", "web_fetch"
    );

    private final ToolsManager toolsManager;

    public MicroCompactLayer(@Autowired(required = false) ToolsManager toolsManager) {
        this.toolsManager = toolsManager;
    }

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
        return ctx.getProperties() == null || ctx.getProperties().isMicroCompactEnabled();
    }

    @Override
    public boolean shouldSkip(CompactionContext ctx, List<Message> messages) {
        // 检查时间衰减是否触发
        if (!isTimeDecayTriggered(ctx)) {
            return true;
        }
        return false;
    }

    @Override
    public LayerResult compress(CompactionContext ctx, List<Message> messages) {
        long preTokens = TokenEstimator.estimateMessages(messages);
        long startTime = System.currentTimeMillis();

        int keepRecent = getKeepRecent(ctx);
        int clearedCount = 0;

        // ========== 临时调试日志：压缩前消息列表 ==========
        log.info("========== MicroCompact 压缩前 ==========");
        long totalToolMessages = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String msgType = msg.getClass().getSimpleName();
            boolean isToolResult = msg instanceof ToolResponseMessage;
            if (isToolResult) totalToolMessages++;

            String textPreview = getTextPreview(msg.getText(), 80);
            log.info("  [{}] {}: isToolResult={}, text长度={}, text=\"{}\"",
                    i, msgType, isToolResult,
                    msg.getText() != null ? msg.getText().length() : 0,
                    textPreview);
        }
        log.info("  总计: {} 条消息, {} tokens, 工具结果数={}, keepRecent={}",
                messages.size(), preTokens, totalToolMessages, keepRecent);
        log.info("========================================");

        List<Message> compacted = new ArrayList<>(messages.size());

        // 计算需要被折叠/替换的旧工具结果数量
        long toFoldCount = Math.max(0, totalToolMessages - keepRecent);

        if (toFoldCount > 0) {
            int foldedCount = 0;
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);

                if (msg instanceof ToolResponseMessage trm) {
                    // 只处理 ToolResponseMessage（工具返回结果）
                    if (foldedCount < toFoldCount) {
                        // 检查这个工具是否可压缩
                        String toolName = getToolNameFromResponse(trm);
                        if (canCompactTool(toolName)) {
                            // 替换内容为占位符，用 AssistantMessage 承载
                            compacted.add(new AssistantMessage(TOOL_RESULT_CLEARED));
                            foldedCount++;
                            clearedCount++;
                            log.info("  >> [压缩] 索引 {} 处的工具结果已替换为占位符", i);
                        } else {
                            compacted.add(msg);
                            log.info("  >> [保留] 索引 {} 处的不可压缩工具结果", i);
                        }
                    } else {
                        compacted.add(msg);
                        log.info("  >> [保留] 索引 {} 处的最新工具结果", i);
                    }
                } else {
                    // UserMessage, SystemMessage, AssistantMessage（文本或tool_use）原样保留
                    compacted.add(msg);
                }
            }
            log.info("  >> 工具结果压缩完成: 总计 {} 条, 保留 {} 条, 压缩 {} 条",
                    totalToolMessages, keepRecent, clearedCount);
        } else {
            // 工具数量未超标，直接复制
            compacted.addAll(messages);
            log.info("  >> 工具结果数量 {} <= keepRecent {}, 无需压缩", totalToolMessages, keepRecent);
        }

        long freedTokens = preTokens - TokenEstimator.estimateMessages(compacted);
        Duration executionTime = Duration.ofMillis(System.currentTimeMillis() - startTime);

        // ========== 临时调试日志：压缩后消息列表 ==========
        log.info("========== MicroCompact 压缩后 ==========");
        for (int i = 0; i < compacted.size(); i++) {
            Message msg = compacted.get(i);
            String msgType = msg.getClass().getSimpleName();
            String textPreview = getTextPreview(msg.getText(), 80);
            log.info("  [{}] {}: text长度={}, text=\"{}\"",
                    i, msgType,
                    msg.getText() != null ? msg.getText().length() : 0,
                    textPreview);
        }
        log.info("  总计: {} 条消息, {} tokens", compacted.size(), TokenEstimator.estimateMessages(compacted));
        log.info("========================================");

        return LayerResult.builder()
                .layerOrder(ORDER)
                .layerName(NAME)
                .messages(compacted)
                .tokensFreed(freedTokens)
                .messagesCleared(clearedCount)
                .didWork(clearedCount > 0)
                .executionTime(executionTime)
                .build();
    }

    private String getTextPreview(String text, int maxLen) {
        if (text == null) return "null";
        if (text.length() <= maxLen) return text.replace("\n", "\\n");
        return text.substring(0, maxLen).replace("\n", "\\n") + "...";
    }

    /**
     * 获取时间衰减阈值（分钟）
     */
    private int getGapThresholdMinutes(CompactionContext ctx) {
        if (ctx.getProperties() != null) {
            return ctx.getProperties().getGapThresholdMinutes() != null
                    ? ctx.getProperties().getGapThresholdMinutes()
                    : DEFAULT_GAP_THRESHOLD_MINUTES;
        }
        return DEFAULT_GAP_THRESHOLD_MINUTES;
    }

    /**
     * 获取保留最近数
     */
    private int getKeepRecent(CompactionContext ctx) {
        if (ctx.getProperties() != null) {
            return ctx.getProperties().getMicroCompactKeepRecent() != null
                    ? ctx.getProperties().getMicroCompactKeepRecent()
                    : DEFAULT_KEEP_RECENT;
        }
        return DEFAULT_KEEP_RECENT;
    }

    /**
     * 判断是否触发时间衰减
     */
    private boolean isTimeDecayTriggered(CompactionContext ctx) {
        if (ctx.getPreCompactMessages() == null || ctx.getPreCompactMessages().isEmpty()) {
            return false;
        }

        Message lastAssistant = null;
        for (int i = ctx.getPreCompactMessages().size() - 1; i >= 0; i--) {
            Object msg = ctx.getPreCompactMessages().get(i);
            if (msg instanceof AssistantMessage) {
                lastAssistant = (Message) msg;
                break;
            }
        }

        if (lastAssistant == null) {
            return false;
        }

        // TODO: 需要从消息元数据中获取时间戳
        return true;
    }

    /**
     * 判断工具是否可压缩
     */
    private boolean canCompactTool(String toolName) {
        if (toolName == null) {
            return false;
        }

        // 1. 白名单检查
        if (COMPACTABLE_TOOL_NAMES.contains(toolName.toLowerCase())) {
            return true;
        }

        // 2. 运行时查找 AgentTool.isCompactable()
        if (toolsManager != null) {
            try {
                AgentTool tool = toolsManager.getTool(toolName);
                if (tool != null) {
                    return tool.isCompactable();
                }
            } catch (Exception e) {
                log.debug("查找工具 {} 失败: {}", toolName, e.getMessage());
            }
        }

        return false;
    }

    /**
     * 从 ToolResponseMessage 提取工具名称
     */
    private String getToolNameFromResponse(ToolResponseMessage trm) {
        if (trm == null || trm.getResponses() == null || trm.getResponses().isEmpty()) {
            return null;
        }
        // ToolResponseMessage 的响应列表包含 tool name 信息
        var responses = trm.getResponses();
        if (!responses.isEmpty()) {
            return responses.get(0).name();
        }
        return null;
    }
}