package top.javarem.omni.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Description;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
@Slf4j
public class ToolsManager implements BeanPostProcessor {

    private final ConfigurableListableBeanFactory beanFactory;
    public ToolsManager(ConfigurableListableBeanFactory beanFactory) {

        this.beanFactory = beanFactory;
    }

    private final List<String> allToolNames = new ArrayList<>();
    private final List<ToolCallback> allToolCallbacks = new ArrayList<>();

    public List<String> getAllToolNames() {
        return allToolNames;
    }

    public List<ToolCallback> getToolCallbacks() {
        return allToolCallbacks;
    }

    /**
     * 根据工具名称获取 AgentTool 实例
     */
    public AgentTool getTool(String name) {
        if (name == null) return null;
        try {
            var toolCallbacks = getToolCallbacks();
            for (ToolCallback callback : toolCallbacks) {
                if (callback.getToolDefinition().name().equalsIgnoreCase(name)) {
                    // 通过回调找到对应的 bean
                    Object tool = callback.getToolDefinition();
                    // 尝试从 beanFactory 获取 bean
                    String[] beanNames = beanFactory.getBeanNamesForType(AgentTool.class);
                    for (String beanName : beanNames) {
                        Object bean = beanFactory.getBean(beanName);
                        if (bean instanceof AgentTool agentTool) {
                            if (agentTool.getName().equalsIgnoreCase(name)) {
                                return agentTool;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("查找工具 {} 失败: {}", name, e.getMessage());
        }
        return null;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 1. 处理 @Bean("Write") 形式定义的 Function 工具
        if (isFunctionInterface(bean)) {
            // 注意：对于 @Bean 定义的工具，@Description 通常标注在配置类的方法上
            // 实例类本身可能没有注解，所以需要从 BeanFactory 中查找该 Bean 上的注解
            Description description = beanFactory.findAnnotationOnBean(beanName, Description.class);
            if (description != null) {
                // 此时的 beanName 就是你定义的 "Write"
                allToolNames.add(beanName);
            }
        }

        // 2. 处理类方法中通过 @Tool 标注的工具
        ReflectionUtils.doWithMethods(bean.getClass(), method -> {
            Tool toolAnnotation = AnnotationUtils.findAnnotation(method, Tool.class);
            if (toolAnnotation != null) {
                // 优先取 @Tool(name="xxx")，否则取方法名
                String name = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                allToolNames.add(name);
            }
        });

        return bean;
    }

    private boolean isFunctionInterface(Object bean) {
        return bean instanceof Function ||
                bean instanceof Supplier ||
                bean instanceof Consumer ||
                bean instanceof BiFunction;
    }

    @Bean
    public ToolCallbackResolver toolCallbackResolver(List<AgentTool> agentTools) {
        log.info(" [Agent System] 开始扫描并自动注册技能集 (Agent Skills)...");

        List<ToolCallback> allRegisteredCallbacks = new ArrayList<>();

        // 1. 筛选并处理所有标识了 AgentTool 接口的 Bean
        for (Object bean : agentTools) {
            if (bean instanceof AgentTool) {
                // 使用 Spring AI 提供的工具解析 Bean 中的所有 @Tool 方法
                ToolCallback[] callbacks = ToolCallbacks.from(bean);

                if (callbacks.length > 0) {

                    for (ToolCallback callback : callbacks) {
                        String toolName = callback.getToolDefinition().name();
                        String toolDesc = callback.getToolDefinition().description();

                        log.info("   ↳ 🛠️ 注册原子工具: [{}] - 描述: {}", toolName, toolDesc);
                        allRegisteredCallbacks.add(callback);
                    }
                }
            }
        }

        // 保存引用以供 Agent 子类过滤使用
        allToolCallbacks.clear();
        allToolCallbacks.addAll(allRegisteredCallbacks);

        log.info("✅ [Agent System] 技能解析完成，共激活 {} 个技能类，注册 {} 个原子工具。",
                agentTools.stream().filter(b -> b instanceof AgentTool).count(),
                allRegisteredCallbacks.size());

        // 2. 返回静态解析器。ChatClient 在需要时会通过名称在这里寻找工具执行逻辑
        return new StaticToolCallbackResolver(allRegisteredCallbacks);
    }
}