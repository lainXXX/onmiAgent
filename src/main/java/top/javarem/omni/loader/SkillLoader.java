package top.javarem.omni.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

/**
 * @Author: rem
 * @Date: 2026/03/09/21:32
 * @Description: Skill 发现服务，负责扫描 skills 目录、解析 YAML 元数据并缓存描述文本
 */
@Slf4j
@Service
public class SkillLoader {

    private final ResourcePatternResolver resourcePatternResolver;
    private final Yaml yaml;
    private final ChatModel chatModel;

    @Value("${skill.discovery.root:classpath:/}")
    private String skillRootPath;

    @Value("${skill.discovery.path:/skills/**/SKILL.md}")
    private String skillStructure;

    @Value("${skill.discovery.enabled:true}")
    private boolean enabled;

    @Value("${skill.guide.enabled:true}")
    private boolean guideEnabled;

    @Value("${skill.guide.output-path:${skill.discovery.root}}")
    private String guideOutputPath;

    private volatile String cachedSkillsDescription = null;

    public SkillLoader(ResourceLoader resourceLoader,
                       @Qualifier("minimaxChatModel") ChatModel chatModel) {
        this.resourcePatternResolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        this.yaml = new Yaml();
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

    private static final String SKILLS_GUIDE_FILE = "skills_guide.md";

    /**
     * 检查并生成 Skills Guide
     */
    private void generateSkillsGuideIfNeeded(List<SkillMetadata> skills) {
        try {
            String hash = computeSkillsHash(skills);
            String fileName = SKILLS_GUIDE_FILE;
            String outputDir = guideOutputPath.replace("file:", "");

            Path guideFile = Path.of(outputDir, fileName);

            // 检查是否需要重新生成：文件不存在 或 hash 变化
            String storedHash = readStoredHash(guideFile);
            if (Files.exists(guideFile) && hash.equals(storedHash)) {
                log.debug("[SkillsGuide] Guide 无变化，跳过: {}", guideFile);
                return;
            }

            // 调用 LLM 生成 Guide
            log.info("[SkillsGuide] 开始生成 Guide: {}, Skill 数量={}", guideFile, skills.size());
            String guideContent = generateSkillsGuide(skills);

            if (guideContent == null || guideContent.isBlank()) {
                log.warn("[SkillsGuide] LLM 返回为空，跳过保存");
                return;
            }

            // 写入文件，头部嵌入 hash
            String content = "---\nhash: " + hash + "\n---\n\n" + guideContent;
            Files.writeString(guideFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("[SkillsGuide] Guide 生成成功: {}", guideFile);

        } catch (Exception e) {
            log.error("[SkillsGuide] Guide 生成失败", e);
        }
    }

    /**
     * 读取文件中存储的 hash
     */
    private String readStoredHash(Path guideFile) {
        try {
            String content = Files.readString(guideFile, StandardCharsets.UTF_8);
            String trimmed = content.trim();
            if (trimmed.startsWith("---")) {
                int endIndex = trimmed.indexOf("---", 3);
                if (endIndex > 0) {
                    String frontmatter = trimmed.substring(3, endIndex).trim();
                    for (String line : frontmatter.split("\n")) {
                        if (line.startsWith("hash:")) {
                            return line.substring(5).trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SkillsGuide] 读取 stored hash 失败: {}", guideFile);
        }
        return null;
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

}
