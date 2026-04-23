package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.loader.SystemMessageLoader;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.loader.SkillLoader;
import top.javarem.omni.repository.chat.ChatMemoryRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 专门修复 MiniMax/智谱等模型的消息顺序问题
 */
@Component
@Slf4j
public class MessageFormatAdvisor implements BaseAdvisor {

    private final SkillLoader skillLoader;
    private final ChatMemoryRepository chatMemoryRepository;
    private final SystemMessageLoader systemMessageLoader;
    private final ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();

    public MessageFormatAdvisor(SkillLoader skillLoader, ChatMemoryRepository chatMemoryRepository, SystemMessageLoader systemMessageLoader) {
        this.skillLoader = skillLoader;
        this.chatMemoryRepository = chatMemoryRepository;
        this.systemMessageLoader = systemMessageLoader;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Map<String, Object> context = request.context();
        String conversationId = (String) context.get(AdvisorContextConstants.SESSION_ID);

        // 1. 初始化消息容量，减少 ArrayList 扩容开销
        List<Message> messages = new ArrayList<>(16);

        // 2. 批量加载系统/配置级消息
        addMessageIfPresent(messages, MessageType.SYSTEM, systemMessageLoader.loadSystemPrompt());

        // 注意：原代码中 loadTools 被调用了两次，这里已去重
        addInjectedMessage(messages, MessageType.USER, systemMessageLoader.loadTools());
        addInjectedMessage(messages, MessageType.USER, systemMessageLoader.loadSkillsGuide());

        // 3. 动态上下文消息 (Skill & Workspace)
        if (Boolean.TRUE.equals(context.get(AdvisorContextConstants.ENABLE_SKILL))) {
            addInjectedMessage(messages, MessageType.USER, skillLoader.getSkillsDescription());
        }

        String workSpace = context.get(AdvisorContextConstants.WORKSPACE).toString();
        if (StringUtils.isNotBlank(workSpace)) {
            Map<String, Object> workspaceMetadata = new HashMap<>();
            workspaceMetadata.put(AdvisorContextConstants.OMNI_INJECTED, true);
            messages.add(UserMessage.builder()
                    .text("<system-reminder>当前工作目录（CWD）为：" + workSpace + "</system-reminder>")
                    .metadata(workspaceMetadata)
                    .build());
        }

        // 4. 历史记忆消息
        if (conversationId != null) {
            List<Message> memoryMessages = chatMemoryRepository.getCleanContext(conversationId);
            if (memoryMessages != null) {
                messages.addAll(memoryMessages);
            }
        }
        messages.add(request.prompt().getUserMessage());

        // 5. 合并当前用户输入并构建最终请求
        Prompt prompt = request.prompt().mutate().messages(messages).build();
        return request.mutate()
                .prompt(prompt)
                .build();
    }

    /**
     * 辅助工具方法：判空并添加消息，保持主逻辑清爽
     */
    private void addMessageIfPresent(List<Message> messages, MessageType type, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        messages.add(type == MessageType.SYSTEM ? new SystemMessage(content) : new UserMessage(content));
    }

    /**
     * 辅助工具方法：创建带有 omni_injected 标记的系统注入消息
     */
    private void addInjectedMessage(List<Message> messages, MessageType type, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AdvisorContextConstants.OMNI_INJECTED, true);
        if (type == MessageType.SYSTEM) {
            messages.add(SystemMessage.builder().text(content).metadata(metadata).build());
        } else {
            messages.add(UserMessage.builder().text(content).metadata(metadata).build());
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        request = before(request, chain);
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        if (flux == null) {
            return Flux.empty();
        }
        // 使用 ChatClientMessageAggregator 聚合流，在流结束后再执行 after 逻辑
        return aggregator.aggregateChatClientResponse(flux, completeResponse -> {
            // after 逻辑在流结束后执行，此时 completeResponse 包含完整响应
            after(completeResponse, chain);
        });
    }

    @Override public int getOrder() { return 10000; }
}