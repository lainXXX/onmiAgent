package top.javarem.skillDemo.repository.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.javarem.skillDemo.model.rag.ParentChunkRecord;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG Chunk 仓库 - 管理 rag_parent_chunks 表
 */
@Slf4j
@Repository
public class RagChunkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RagChunkRepository(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量插入母块文档
     */
    public void batchInsert(List<Document> parentDocs) {
        if (parentDocs == null || parentDocs.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO rag_parent_chunks (id, content, metadata, file_id, chunk_index, token_count)
            VALUES (?, ?, ?::jsonb, ?, ?, ?)
            """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Document doc = parentDocs.get(i);
                ps.setString(1, doc.getId());
                ps.setString(2, doc.getText());

                String metadataJson = toJson(doc.getMetadata());
                ps.setString(3, metadataJson);

                Object fileId = doc.getMetadata().get("file_id");
                ps.setObject(4, fileId);

                Object chunkIndex = doc.getMetadata().get("chunkIndex");
                ps.setInt(5, chunkIndex != null ? (Integer) chunkIndex : 0);

                Object tokenCount = doc.getMetadata().get("token_count");
                ps.setInt(6, tokenCount != null ? (Integer) tokenCount : 0);
            }

            @Override
            public int getBatchSize() {
                return parentDocs.size();
            }
        });

        log.info("[RagChunkRepository] 批量插入 {} 个母块", parentDocs.size());
    }

    /**
     * 根据 fileId 查询所有母块
     */
    public List<ParentChunkRecord> findByFileId(Long fileId) {
        String sql = """
            SELECT id, content, metadata::text as metadata_json, file_id, chunk_index, token_count
            FROM rag_parent_chunks
            WHERE file_id = ?
            ORDER BY chunk_index
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String id = rs.getString("id");
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata_json");
            Long fileIdVal = rs.getLong("file_id");
            int chunkIndex = rs.getInt("chunk_index");
            int tokenCount = rs.getInt("token_count");

            Map<String, Object> metadata = parseJson(metadataJson);
            metadata.put("chunkIndex", chunkIndex);
            metadata.put("tokenCount", tokenCount);
            metadata.put("fileId", fileIdVal);

            return new ParentChunkRecord(id, content, metadata, fileIdVal, chunkIndex, tokenCount);
        }, fileId);
    }

    /**
     * 根据 ID 列表批量查询
     */
    public List<ParentChunkRecord> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }

        String sql = String.format("""
            SELECT id, content, metadata::text as metadata_json, file_id, chunk_index, token_count
            FROM rag_parent_chunks
            WHERE id IN (%s)
            """, placeholders);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String id = rs.getString("id");
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata_json");
            Long fileId = rs.getLong("file_id");
            int chunkIndex = rs.getInt("chunk_index");
            int tokenCount = rs.getInt("token_count");

            Map<String, Object> metadata = parseJson(metadataJson);
            metadata.put("chunkIndex", chunkIndex);
            metadata.put("tokenCount", tokenCount);
            metadata.put("fileId", fileId);

            return new ParentChunkRecord(id, content, metadata, fileId, chunkIndex, tokenCount);
        }, ids.toArray());
    }

    /**
     * 根据 fileId 删除所有关联母块
     */
    public int deleteByFileId(Long fileId) {
        String sql = "DELETE FROM rag_parent_chunks WHERE file_id = ?";
        return jdbcTemplate.update(sql, fileId);
    }

    /**
     * 将 Record 转换为 Spring AI Document
     */
    public Document toDocument(ParentChunkRecord record) {
        Map<String, Object> metadata = new HashMap<>(record.getMetadata());
        metadata.put("fileId", record.getFileId());
        metadata.put("chunkIndex", record.getChunkIndex());
        metadata.put("tokenCount", record.getTokenCount());
        return new Document(record.getId(), record.getContent(), metadata);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("[RagChunkRepository] 元数据 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[RagChunkRepository] 元数据 JSON 解析失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
