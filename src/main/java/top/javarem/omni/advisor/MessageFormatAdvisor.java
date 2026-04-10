package top.javarem.omni.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import top.javarem.omni.loader.SystemMessageLoader;
import top.javarem.omni.model.context.AdvisorContextConstants;
import top.javarem.omni.loader.SkillLoader;
import top.javarem.omni.repository.chat.MemoryRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 专门修复 MiniMax/智谱等模型的消息顺序问题
 */
@Component
@Slf4j
public class MessageFormatAdvisor implements BaseAdvisor {

    private final SkillLoader skillLoader;
    private final MemoryRepository memoryRepository;
    private final SystemMessageLoader systemMessageLoader;
    private final ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();

    public MessageFormatAdvisor(SkillLoader skillLoader, MemoryRepository memoryRepository, SystemMessageLoader systemMessageLoader) {
        this.skillLoader = skillLoader;
        this.memoryRepository = memoryRepository;
        this.systemMessageLoader = systemMessageLoader;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String conversationId = (String) request.context().get(ChatMemory.CONVERSATION_ID);
        // 构建消息列表
        List<Message> messages = new ArrayList<>();
        // 1.获取系统消息
        String systemPrompt = systemMessageLoader.loadSystemPrompt();
        messages.add(new SystemMessage(systemPrompt));
        String toolsMessage = systemMessageLoader.loadTools();
        if (toolsMessage != null) {
            messages.add(new UserMessage(toolsMessage));
        }
        String skillsGuide = systemMessageLoader.loadSkillsGuide();
        if (skillsGuide != null) {
            messages.add(new UserMessage(skillsGuide));
        }
        String toolsGuide = systemMessageLoader.loadTools();
        if (toolsGuide != null) {
            messages.add(new UserMessage(toolsGuide));
        }
        // 2.获取提醒消息
        if ((boolean) request.context().get(AdvisorContextConstants.ENABLE_SKILL)) {
            UserMessage skillMessage = new UserMessage(skillLoader.getSkillsDescription());
            messages.add(skillMessage);
        }
        // 3.获取记忆消息
        List<Message> memoryMessages = memoryRepository.findMessagesByConversationId(conversationId);
        messages.addAll(memoryMessages);
        // 4.获取用户消息 (final last message)
        messages.add(request.prompt().getUserMessage());
        Prompt prompt = request.prompt().mutate().messages(messages).build();
        return request.mutate().prompt(prompt).build();
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