package top.javarem.omni.loader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @Author: rem
 * @Date: 2026/03/25/22:47
 * @Description:
 */
@Component
public class SystemMessageLoader {


    private final static String SYSTEM_PROMPT = """
            你是一个具备高度自主能力的 Agent。在处理用户问题时，请遵循 ReAct 模式：
                        
            1. Thought: 分析用户问题，思考我是否拥有完成任务所需的信息。
            2. Action: 如果信息不足，决定调用哪个工具，并输出参数。
            3. Observation: 观察工具返回的结果。
            4. Thought: 根据结果进行推理。如果任务未完成，重复上述 Action；如果完成，给出最终回答。
                        
            请注意：
            - 永远不要猜测数据，不知道就去查。
            - 如果工具返回错误，请在 Thought 中分析原因并尝试不同的参数或工具。
            - 最终回答请在 Thought 后给出。
            """;

    /**
     * 从文件加载 Agent System Prompt
     */
    @Value("classpath:agent_system_prompt.md")
    private Resource agentSystemPromptResource;
    public String loadSystemPrompt() {
        try {
            return agentSystemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return SYSTEM_PROMPT;
        }
    }

    @Value("classpath:TOOLS.md")
    private Resource toolsResource;
    public String loadTools() {
        try {
            return toolsResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    @Value("file:${user.home}/.omni/skills_guide.md")
    private Resource skillsGuide;

    public String loadSkillsGuide() {
        try {
            String content = skillsGuide.getContentAsString(StandardCharsets.UTF_8);
            return stripFrontmatter(content);
        } catch (IOException e) {
            return "";
        }
    }

    private String stripFrontmatter(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("---")) {
            int endIndex = trimmed.indexOf("---", 3);
            if (endIndex > 0) {
                return trimmed.substring(endIndex + 3).trim();
            }
        }
        return content;
    }
}
