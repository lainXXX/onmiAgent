package top.javarem.omni.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.yaml.snakeyaml.Yaml;
import top.javarem.omni.model.skill.SkillMetadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

/**
 * @Author: rem
 * @Date: 2026/03/09/21:32
 * @Description: Skill 发现服务，负责扫描 skills 目录、解析 YAML 元数据并存储到向量库
 *
 */
@Slf4j
@Service
public class SkillLoader {

    private final ResourcePatternResolver resourcePatternResolver;
    private final VectorStore vectorStore;
    private final Yaml yaml;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChatModel chatModel;


    // 可变前缀
    @Value("${skill.discovery.root:classpath:/}")
    private String skillRootPath;

    // 文件结构
    @Value("${skill.discovery.path:/skills/**/SKILL.md}")
    private String skillStructure;

    // 是否启用技能发现
    @Value("${skill.discovery.enabled:true}")
    private boolean enabled;

    // Skills Guide 生成配置
    @Value("${skill.guide.enabled:true}")
    private boolean guideEnabled;

    @Value("${skill.guide.output-path:${skill.discovery.root}}")
    private String guideOutputPath;

    /**
     * Skills 描述缓存
     * 避免每次请求都重新构建描述文本
     */
    private volatile String cachedSkillsDescription = null;

    public SkillLoader(ResourceLoader resourceLoader, VectorStore vectorStore,
                       @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                       ObjectMapper objectMapper,
                       @Qualifier("minimaxChatModel") ChatModel chatModel) {
        this.resourcePatternResolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.yaml = new Yaml();
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.chatModel = chatModel;
    }

    public String getSkillsDescription() {
        if (cachedSkillsDescription != null) {
            return cachedSkillsDescription;
        }
        // 未初始化时返回空字符串，避免返回模板占位符
        return "";
    }

    /**
     * 构建 Skills Description 模板
     */
    private String buildSkillsDescriptionTemplate(String skillListContent) {
        return """
                <system-reminder>

                The following skills are available for use with the Skill tool:

                %s

                When replying or executing tasks, please prioritize referring to and applying the norms and suggestions listed in the aforementioned skill inventory.

                <SUBAGENT-STOP>
                    If you are assigned to perform a specific task, skip this skill.
                </SUBAGENT-STOP>

                <EXTREMELY-IMPORTANT>
                    If you think there is even a 1%% chance that a certain skill will be applied to what you are doing, you absolutely must use that skill.

                    If a certain skill is applicable to your task, you have no choice. You must use it.

                    This is non-negotiable. It's not optional. You can't use rationality to evade all of this.

                    The instructions state "what to do" rather than "how to do it". "Adding X" or "correcting Y" does not mean skipping the workflow.
                </EXTREMELY-IMPORTANT>
                </system-reminder>
                """.formatted(skillListContent);
    }

    /**
     * 启动时扫描并加载 skills（幂等操作）
     */
    @PostConstruct
    public void discoverSkills() {
        if (!enabled) {
            log.debug("技能发现已禁用");
            return;
        }

        List<SkillMetadata> skillMetadataList = new ArrayList<>(); // 收集解析后的 SkillMetadata

        try {
            // 拼接绝对路径模式，必须加上 "file:" 前缀，告诉 Spring 去物理硬盘找
            // 这里使用 **/*.md 表示递归扫描 skills 目录下所有的 .md 文件
            String skillPathPattern = skillRootPath + skillStructure;

            // 3. 执行扫描
            Resource[] resources = resourcePatternResolver.getResources(skillPathPattern);

            StringBuilder sb = new StringBuilder();
            // 2. 遍历资源，对比并分类处理
            for (Resource r : resources) {
                SkillMetadata localMeta = parseSkillMetadata(r);
                if (localMeta == null) {
                    log.warn("跳过解析失败的文件: {}", r.getFilename());
                    continue;
                }
                skillMetadataList.add(localMeta);
                sb.append("- ").append(localMeta.getName()).append(": ").append(localMeta.getDescription()).append("\n");

            }
            cachedSkillsDescription = buildSkillsDescriptionTemplate(sb.toString());
        } catch (Exception e) {
            log.error("技能发现流程异常", e);
        }

        // 生成 Skills Guide
        if (guideEnabled && !skillMetadataList.isEmpty()) {
            generateSkillsGuideIfNeeded(skillMetadataList);
        }
    }

    /**
     * 计算所有 Skills 的内容哈希
     */
    private String computeSkillsHash(List<SkillMetadata> skills) {
        StringBuilder sb = new StringBuilder();
        for (SkillMetadata skill : skills) {
            sb.append(skill.getName()).append(skill.getDescription());
        }
        return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成期望的 Guide 文件名
     */
    private String computeGuideFileName(String hash) {
        return "skills_guide_" + hash.substring(0, 8) + ".md";
    }

    /**
     * 检查并生成 Skills Guide
     */
    private void generateSkillsGuideIfNeeded(List<SkillMetadata> skills) {
        try {
            // 1. 计算哈希
            String hash = computeSkillsHash(skills);
            String fileName = computeGuideFileName(hash);
            String outputDir = guideOutputPath.replace("file:", "");

            // 2. 检查文件是否存在
            Path guideFile = Path.of(outputDir, fileName);
            if (Files.exists(guideFile)) {
                log.debug("[SkillsGuide] Guide 文件已存在，跳过: {}", guideFile);
                return;
            }

            // 3. 调用 LLM 生成 Guide
            log.info("[SkillsGuide] 开始生成 Guide: {}, Skill 数量={}", guideFile, skills.size());
            String guideContent = generateSkillsGuide(skills);

            // 4. 保存文件
            if (guideContent == null || guideContent.isBlank()) {
                log.warn("[SkillsGuide] LLM 返回为空，跳过保存");
                return;
            }
            Files.writeString(guideFile, guideContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("[SkillsGuide] Guide 生成成功: {}", guideFile);

        } catch (Exception e) {
            log.error("[SkillsGuide] Guide 生成失败", e);
        }
    }

    /**
     * 调用 LLM 生成 Skills Guide
     */
    private String generateSkillsGuide(List<SkillMetadata> skills) {
        StringBuilder skillList = new StringBuilder();
        for (SkillMetadata skill : skills) {
            skillList.append("- **").append(skill.getName()).append("**: ")
                    .append(skill.getDescription()).append("\n");
        }

        String systemPrompt = """
                你是一个 Agent 逻辑编译器。任务是将动态工具集（Skills）编译为极高密度、零底噪的系统指令 `<system-reminder>`。
                            
                【编译器法则】(违反则逻辑崩溃)：
                1. 标识符死律：【绝对禁止】修改、翻译或缩写 Skill ID。必须字符级匹配（如 `subagent-driven-development`）。
                2. 语法范式：
                   - 触发器：`IF [精准条件] THEN MUST [SKILL_ID]`
                   - 链路：`SCENARIO: ID1 -> ID2 -> ID3`
                   - 回退：`ID [FAIL] -> RECOVERY_ID`
                3. 拓扑推演：基于描述自动校准为 [Explore -> Plan -> Execute -> Verify] 的单向流。
                4. 结构约束：仅允许使用最外层 `<system-reminder>` 和内部 `<EXTREMELY-IMPORTANT>` 标签。禁止列表符或方括号 `[]`、圆括号 `()` 或任何包裹符号解释性后缀。
                            
                【标准输出模板】
                <system-reminder>
                # ORCHESTRATION-LOGIC
                [IF/THEN 映射]
                            
                # GOLDEN-CHAINS
                [全生命周期链路]
                            
                <EXTREMELY-IMPORTANT>
                # FALLBACK-STRATEGY
                [FAIL 状态机]
                # BOUNDARY-LOGIC
                [易混淆 Skill 辨析]
                </EXTREMELY-IMPORTANT>
                </system-reminder>
            """;

        String userPrompt = """
                请编译以下动态 Skill 列表，生成满足【编译器法则】的 `<system-reminder>`。
                            
                【本次任务指标】：
                1. 覆盖率：提取所有 Skill 的 Hard Triggers。
                2. 深度：推导至少 3 条覆盖“调研到收尾”的完整 GOLDEN-CHAINS。
                3. 鲁棒性：计算 `verification-before-completion` 返回 FAIL 后的回退路径。
                            
                【动态 Skill 列表】：
                %s
                            
                【指令】：
                直接输出 `<system-reminder>` 内容，严禁思考过程泄漏。

            """.formatted(skillList);

            return ChatClient.builder(chatModel).build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.2)
//                            .maxTokens(1024)
                            .build())
                    .call()
                    .content();
    }

    /**
     * 批量执行删除、添加、更新操作
     */
    private void executeBatchOperations(List<String> toDelete,
                                       List<Document> toAdd,
                                       List<Map<String, Object>> toUpdateMetadata) {

        // 4.1 批量删除（优化：合并为一次删除）
        if (!toDelete.isEmpty()) {
            try {
                for (String skillName : toDelete) {
                    vectorStore.delete("type == 'skill' AND skillName == '" + escapeFilter(skillName) + "'");
                }
                log.debug("批量删除 {} 条记录", toDelete.size());
            } catch (Exception e) {
                log.error("批量删除失败", e);
            }
        }

        // 4.2 批量添加
        if (!toAdd.isEmpty()) {
            try {
                vectorStore.add(toAdd);
                log.debug("批量添加 {} 条记录", toAdd.size());
            } catch (Exception e) {
                log.error("批量添加失败", e);
            }
        }

        // 4.3 批量更新元数据（SQL 更新，零 token）
        if (!toUpdateMetadata.isEmpty()) {
            try {
                for (var update : toUpdateMetadata) {
                    updateMetadataOnly(
                            (String) update.get("docId"),
                            (String) update.get("path"),
                            (String) update.get("mHash")
                    );
                }
                log.debug("批量更新 {} 条元数据", toUpdateMetadata.size());
            } catch (Exception e) {
                log.error("批量更新元数据失败", e);
            }
        }
    }

    /**
     * 转义过滤条件，防止 SQL 注入
     */
    private String escapeFilter(String input) {
        if (input == null) return "";
        return input.replace("'", "''");
    }

    /**
     * 转义百分号，防止 String.formatted() 解析失败
     */
    private String escapePercent(String input) {
        if (input == null) return "";
        // 转义 % 为 %%，但保留已经是格式说明符的 %s, %d, %f 等
        // 使用负向后瞻：匹配 % 后面不是有效转换符的情况
        return input.replaceAll("%(?![0-9$+\\-#,.<(]?[a-zA-Z])", "%%");
    }

    /**
     * 添加文档
     * 需要向量化的文档（即技能描述）
     * @param metadata 技能元数据
     * @param sHash 语义哈希
     * @param mHash 元数据哈希
     * @param documents 文档列表
     */
    private void prepareDocument(SkillMetadata metadata, String sHash, String mHash, List<Document> documents) {
        if (metadata != null) {
            // 创建文档，description 用于向量检索
            Document doc = new Document(
                    metadata.getDescription(),
                    Map.of(
                            "skillName", metadata.getName(),
                            "path", metadata.getPath().replace("\\", "/"), // 标准化路径
                            "semanticHash", sHash,
                            "metadataHash", mHash,
                            "type", "skill"
                    )
            );
            documents.add(doc);
        }
    }

    /**
     * 更新元数据（仅更新路径等元信息，零 token 消耗）
     * @param docId 文档 ID
     * @param path 文件路径
     * @param mHash 元数据哈希
     */
    private void updateMetadataOnly(String docId, String path, String mHash) {
        // 使用 PostgreSQL 的 JSONB 拼接操作符 ||（metadata 列是 json 类型，需要显式转换为 jsonb）
        String sql = "UPDATE vector_store SET metadata = metadata::jsonb || ?::jsonb WHERE id = ?::uuid";

        try {
            Map<String, Object> updates = Map.of(
                    "path", path    ,
                    "metadataHash", mHash
            );
            String json = objectMapper.writeValueAsString(updates);
            pgVectorJdbcTemplate.update(sql, json, docId);
        } catch (Exception e) {
            log.error("SQL 更新元数据失败: docId={}", docId, e);
        }
    }

    /**
     * 获取语义哈希
     * @param description 描述内容
     * @return 语义哈希值
     */
    private String getSemanticHash(String description) {
        if (description == null) return "";
        // 1. 统一换行符 2. 去除首尾空格 3. 转小写(可选，视业务而定)
        String normalized = description.replace("\r\n", "\n").trim();
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 获取元数据哈希
     * @param skillName 技能名称
     * @param path 文件路径
     * @return 元数据哈希值
     */
    private String getMetadataHash(String skillName, String path) {
        String combined = skillName + "|" + path.replace("\\", "/");
        return DigestUtils.md5DigestAsHex(combined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 SKILL.md 文件，提取 YAML 前置元数据
     * @param resource SKILL.md 文件资源
     * @return 解析后的技能元数据，或 null
     */
    private SkillMetadata parseSkillMetadata(Resource resource) {
        try {
            String content = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            // 提取 --- 包裹的 YAML 内容
            String yamlContent = extractYamlFrontMatter(content);
            if (yamlContent == null || yamlContent.isBlank()) {
                log.warn("No YAML front matter found in: {}", resource.getFilename());
                return null;
            }

            // 解析 YAML
            Map<String, String> data = yaml.load(yamlContent);
            if (data == null || !data.containsKey("name")) {
                log.warn("Invalid skill metadata, missing 'name' field: {}", resource.getFilename());
                return null;
            }

            String path = standardizePath(resource);

            return SkillMetadata.builder()
                    .name(data.get("name"))
                    .description(data.get("description"))
                    .path(path)
                    .build();

        } catch (Exception e) {
            log.error("Error parsing skill metadata from: {}", resource.getFilename(), e);
            return null;
        }
    }

    /**
     * 提取 YAML 前置元数据
     * @param content 文本内容
     * @return 提取的 YAML 内容，或 null
     */
    private String extractYamlFrontMatter(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return null;
        }

        int endIndex = trimmed.indexOf("---", 3);
        if (endIndex == -1) {
            return null;
        }

        return trimmed.substring(3, endIndex).trim();
    }

    /**
     * 标准化路径
     * @param resource 资源
     * @return 标准化后的路径
     */
    private String standardizePath(Resource resource) {
        try {
            // 1. 获取物理绝对路径并替换反斜杠
            String path = resource.getFile().getAbsolutePath().replace("\\", "/");

            // 2. 获取用户家目录并替换反斜杠
            String userHome = System.getProperty("user.home").replace("\\", "/");

            // 3. 将物理的家目录前缀替换为统一的波浪号 ~
            if (path.startsWith(userHome)) {
                return "~" + path.substring(userHome.length());
                // 结果：~/.claude/skills/tdd/SKILL.md
            }

            return path;
        } catch (IOException e) {
            return resource.getFilename();
        }
    }

    /**
     * 加载数据库中现有的所有技能快照
     */
    private Map<String, SkillSnapshot> loadAllDbSkills() {

        /**
         * SQL 查询语句
         *
         * 为什么不用IN条件而是做skill全量加载？
         * 1. 解决"僵尸数据"问题（物理删除同步）
         *
         * 如果你使用 IN (:localNames) 查询：
         *  - 数据库只会返回本地还存在的技能。
         *  - 如果昨天你删除了 old-skill.md，今天的 IN 查询结果里根本不会出现 old-skill。
         *  - 结果：数据库里的 old-skill 永远无法被清理掉，变成"僵尸数据"。
         *
         * 如果你使用 全量加载：
         *  - 你会拿到数据库里所有的技能。
         *  - 通过 dbSnapshot.remove(localName)，剩下的就是本地已经不存在的。
         *  - 结果：你可以实现真正的双向同步（本地删，数据库也删）。
         *
         * 2.内存与性能
         *  即使有几百上千个skill描述，内存占用也就100KB ~ 5MB，性能损耗很低。
         *  况且正常用户不会创建那么多个skill（通常5~20个）
         * TODO 后续如果涉及到用户隔离等 可在WHERE条件中添加用户过滤等
         */
        String sql = """
        SELECT id, 
               metadata->>'skillName' as name, 
               metadata->>'semanticHash' as s_hash, 
               metadata->>'metadataHash' as m_hash,
               metadata->>'path' as path
        FROM vector_store 
        WHERE metadata->>'type' = 'skill'
        """;

        return pgVectorJdbcTemplate.query(sql, (rs) -> {
            Map<String, SkillSnapshot> map = new HashMap<>();
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null) {
                    map.put(name, new SkillSnapshot(
                            rs.getString("id"),
                            rs.getString("s_hash"),
                            rs.getString("m_hash"),
                            rs.getString("path")
                    ));
                }
            }
            return map;
        });
    }

    /**
     * 技能快照（Snapshot）
     * @param docId 数据库里的主键 UUID
     * @param semanticHash 语义指纹 (sHash)
     * @param metadataHash 元数据指纹 (mHash)
     * @param path 文件路径
     */
    public record SkillSnapshot(
            String docId,
            String semanticHash,
            String metadataHash,
            String path
    ) {}
}
