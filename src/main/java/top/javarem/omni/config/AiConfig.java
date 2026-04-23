package top.javarem.omni.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import top.javarem.omni.advisor.*;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class AiConfig {

    //   翻译：你将长对话压缩成简洁、事实性的摘要。捕获关键决策、实体、意图和未解决事项。
    private final String summarySystemPrompt = """
            You compress long conversations into a concise, factual summary. 
            Capture key decisions, entities, intents, and unresolved items.
            """;



    @Bean("minimaxChatModel") // 这里的名字必须和 Qualifier 一致
    public ChatModel minimaxChatModel(MiniMaxChatModel miniMaxChatModel) {
        return miniMaxChatModel;
    }

    @Bean("anthropicChatModel")
    public ChatModel anthropicChatModel(AnthropicChatModel anthropicChatModel) {
        return anthropicChatModel;
    }

    @Bean
    public ChatClient anthropicChatClient(AnthropicChatModel anthropicChatModel,
                                          MessageFormatAdvisor messageFormatAdvisor,
                                          ChatMemoryAdvisor chatMemoryAdvisor,

                                          LifecycleToolCallAdvisor lifecycleToolCallAdvisor,
                                          TaskProgressAdvisor taskProgressAdvisor,
                                          RetryAdvisor retryAdvisor
                                          ) {
        return ChatClient.builder(anthropicChatModel)
                .defaultToolContext(new HashMap<>(Map.of("debug", true)))
                .defaultAdvisors(
                        // 1. 打印最原始的请求
//                        new SimpleLoggerAdvisor(),
                        // 2. 拉取历史记忆
//                        MessageChatMemoryAdvisor.builder(chatMemory).jdbcTemplate(mysqlJdbcTemplate).executor(threadPoolExecutor).build(),
                        // 3. 匹配技能
//                        skillActivationAdvisor,
                        // 4. 重排消息格式并修正角色顺序
                        messageFormatAdvisor,
                        chatMemoryAdvisor,
                        lifecycleToolCallAdvisor,
                        taskProgressAdvisor,
                        retryAdvisor
                )
                .build();
    }

    /**
     * OpenAI ChatClient
     */
    @Bean
    @Primary
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel,
                                       MessageFormatAdvisor messageFormatAdvisor,
                                       ChatMemoryAdvisor chatMemoryAdvisor,
                                       LifecycleToolCallAdvisor lifecycleToolCallAdvisor,
                                       TaskProgressAdvisor taskProgressAdvisor,
                                       RetryAdvisor retryAdvisor
                                       ) {    // 注入
        return ChatClient.builder(openAiChatModel)
                // 🚀 设置默认工具上下文，防止工具因缺少上下文而崩溃
                .defaultToolContext(new HashMap<>(Map.of("debug", true)))
                .defaultAdvisors(
                        // 1. 打印最原始的请求
//                        new SimpleLoggerAdvisor(),
                        // 2. 拉取历史记忆
//                        MessageChatMemoryAdvisor.builder(chatMemory).jdbcTemplate(mysqlJdbcTemplate).executor(threadPoolExecutor).build(),
                        // 3. 匹配技能
//                        skillActivationAdvisor,
                        // 4. 重排消息格式并修正角色顺序
                        messageFormatAdvisor,
                        chatMemoryAdvisor,
                        lifecycleToolCallAdvisor,
                        taskProgressAdvisor,
                        retryAdvisor
                )
                .build();
    }

    /**
     * 为 PgVector 创建专用的数据源
     */
    @Bean("pgVectorDataSource")
    public DataSource pgVectorDataSource(@Value("${spring.ai.vectorstore.pgvector.datasource.driver-class-name}") String driverClassName,
                                         @Value("${spring.ai.vectorstore.pgvector.datasource.url}") String url,
                                         @Value("${spring.ai.vectorstore.pgvector.datasource.username}") String username,
                                         @Value("${spring.ai.vectorstore.pgvector.datasource.password}") String password,
                                         @Value("${spring.ai.vectorstore.pgvector.datasource.hikari.maximum-pool-size:5}") int maximumPoolSize,
                                         @Value("${spring.ai.vectorstore.pgvector.datasource.hikari.minimum-idle:2}") int minimumIdle,
                                         @Value("${spring.ai.vectorstore.pgvector.datasource.hikari.idle-timeout:30000}") long idleTimeout,
                                         @Value("${spring.ai.vectorstore.pgvector.datasource.hikari.connection-timeout:30000}") long connectionTimeout) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        // 连接池配置
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setIdleTimeout(idleTimeout);
        dataSource.setConnectionTimeout(connectionTimeout);
        // 确保在启动时连接数据库
        dataSource.setInitializationFailTimeout(1);  // 设置为1ms，如果连接失败则快速失败
        dataSource.setConnectionTestQuery("SELECT 1"); // 简单的连接测试查询
        dataSource.setAutoCommit(true);
        dataSource.setPoolName("PgVectorHikariPool");
        return dataSource;
    }

    /**
     * 为 PgVector 创建专用的 JdbcTemplate
     */
    @Bean("pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // PG 专用的 NamedParameterJdbcTemplate
    @Bean
    @Primary
    public NamedParameterJdbcTemplate namedJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource ds) {
        return new NamedParameterJdbcTemplate(ds);
    }

    /**
     * MySQL 创建主数据源
     */
    @Bean("mysqlDataSource")
    @Primary
    public DataSource mysqlDataSource(@Value("${spring.datasource.driver-class-name}") String driverClassName,
                                        @Value("${spring.datasource.url}") String url,
                                        @Value("${spring.datasource.username}") String username,
                                        @Value("${spring.datasource.password}") String password,
                                        @Value("${spring.datasource.hikari.maximum-pool-size:10}") int maximumPoolSize,
                                        @Value("${spring.datasource.hikari.minimum-idle:5}") int minimumIdle,
                                        @Value("${spring.datasource.hikari.idle-timeout:30000}") long idleTimeout,
                                        @Value("${spring.datasource.hikari.connection-timeout:30000}") long connectionTimeout,
                                        @Value("${spring.datasource.hikari.max-lifetime:1800000}") long maxLifetime) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        // 连接池配置
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setIdleTimeout(idleTimeout);
        dataSource.setConnectionTimeout(connectionTimeout);
        dataSource.setMaxLifetime(maxLifetime);
        dataSource.setPoolName("MainHikariPool");
        return dataSource;
    }

    @Primary
    @Bean("mysqlJdbcTemplate")
    public JdbcTemplate mysqlJdbcTemplate(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Vector Store
     */
    @Bean
    public VectorStore vectorStore(@Qualifier("openAiEmbeddingModel") OpenAiEmbeddingModel openAiEmbeddingModel,
                                   @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate
                                   ) {

        return PgVectorStore.builder(jdbcTemplate, openAiEmbeddingModel)
                .vectorTableName("vector_store")
                .build();
    }
}
