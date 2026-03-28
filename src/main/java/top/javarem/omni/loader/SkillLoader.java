package top.javarem.onmi.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
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
import top.javarem.onmi.model.skill.SkillMetadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;

    // 可变前缀
    @Value("${skill.discovery.root:classpath:/}")
    private String skillRootPath;

    // 文件结构
    @Value("${skill.discovery.path:/skills/**/SKILL.md}")
    private String skillStructure;

    // 是否启用技能发现
    @Value("${skill.discovery.enabled:true}")
    private boolean enabled;

    public String skillsDescription = """
                <system-reminder>
                
                The following skills are available for use with the Skill tool:
                              
                
                <skill_descriptions>
                %s
                </skill_descriptions>
                
                When replying or executing tasks, please prioritize referring to and applying the norms and suggestions listed in the aforementioned skill inventory.
                
                </system-reminder>
                """;

    public SkillLoader(ResourceLoader resourceLoader, VectorStore vectorStore, @Qualifier("pgVectorJdbcTemplate")JdbcTemplate pgVectorJdbcTemplate, ObjectMapper objectMapper) {
        this.resourcePatternResolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(pgVectorJdbcTemplate);
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.yaml = new Yaml();
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
    }

    public String getSkillsDescription() {
        return skillsDescription;
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

        // 统计计数器
        int[] stats = {0, 0, 0, 0}; // 新增、更新内容、更新路径、清理

        try {
            // 1. 加载数据库全量快照
            Map<String, SkillSnapshot> dbSnapshot = loadAllDbSkills();

            // 拼接绝对路径模式，必须加上 "file:" 前缀，告诉 Spring 去物理硬盘找
            // 这里使用 **/*.md 表示递归扫描 skills 目录下所有的 .md 文件
            String skillPathPattern = skillRootPath + skillStructure;

            // 3. 执行扫描
            Resource[] resources = resourcePatternResolver.getResources(skillPathPattern);
            log.info("技能发现开始: 本地 {} 个文件 vs 数据库 {} 条记录",
                    resources.length, dbSnapshot.size());

            List<Document> toAdd = new ArrayList<>();
            List<String> toDelete = new ArrayList<>();  // 待删除的 skill 名称
            List<Map<String, Object>> toUpdateMetadata = new ArrayList<>(); // 待更新的元数据
            StringBuilder sb = new StringBuilder();
            // 2. 遍历资源，对比并分类处理
            for (Resource r : resources) {
                SkillMetadata localMeta = parseSkillMetadata(r);
                if (localMeta == null) {
                    log.warn("跳过解析失败的文件: {}", r.getFilename());
                    continue;
                }
                sb.append("- ").append(localMeta.getName()).append(": ").append(localMeta.getDescription()).append("\n");


//                String name = localMeta.getName();
//                String sHash = getSemanticHash(localMeta.getDescription());
//                String mHash = getMetadataHash(name, localMeta.getPath());
//
//                if (dbSnapshot.containsKey(name)) {
//                    SkillSnapshot dbInfo = dbSnapshot.remove(name); // 标记已处理
//
//                    if (!Objects.equals(dbInfo.semanticHash(), sHash)) {
//                        // 内容变化：删除旧文档 + 添加新文档
//                        log.info("【更新内容】技能 [{}]", name);
//                        toDelete.add(name);
//                        prepareDocument(localMeta, sHash, mHash, toAdd);
//                        stats[1]++;
//                    } else if (!Objects.equals(dbInfo.metadataHash(), mHash)) {
//                        // 仅路径变化：SQL 更新（零 token 消耗）
//                        log.info("【更新路径】技能 [{}]", name);
//                        toUpdateMetadata.add(Map.of(
//                                "docId", dbInfo.docId(),
//                                "path", localMeta.getPath().replace("\\", "/"),
//                                "mHash", mHash
//                        ));
//                        stats[2]++;
//                    } else {
//                        log.debug("【跳过】技能 [{}] 无变化", name);
//                    }
//                } else {
//                    // 新增
//                    log.info("【新增】技能 [{}]", name);
//                    prepareDocument(localMeta, sHash, mHash, toAdd);
//                    stats[0]++;
//                }
            }
            skillsDescription = skillsDescription.formatted(sb.toString());

//            // 3. 清理：本地已删除的技能
//            if (!dbSnapshot.isEmpty()) {
//                dbSnapshot.forEach((name, info) -> {
//                    log.info("【清理】本地已删除: {}", name);
//                    toDelete.add(name);
//                    stats[3]++;
//                });
//            }
//
//            // 4. 执行批量操作
//            executeBatchOperations(toDelete, toAdd, toUpdateMetadata);
//
//            // 5. 输出统计
//            log.info("技能发现完成: 新增={}, 更新内容={}, 更新路径={}, 清理={}, 总变化={}",
//                    stats[0], stats[1], stats[2], stats[3],
//                    stats[0] + stats[1] + stats[2] + stats[3]);

        } catch (Exception e) {
            log.error("技能发现流程异常", e);
        }
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
         * 1. 解决“僵尸数据”问题（物理删除同步）
         *
         * 如果你使用 IN (:localNames) 查询：
         *  - 数据库只会返回本地还存在的技能。
         *  - 如果昨天你删除了 old-skill.md，今天的 IN 查询结果里根本不会出现 old-skill。
         *  - 结果：数据库里的 old-skill 永远无法被清理掉，变成“僵尸数据”。
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
