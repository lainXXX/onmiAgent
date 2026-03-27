package top.javarem.skillDemo.repository.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.javarem.skillDemo.model.rag.FileRecord;

/**
 * RAG 文件仓库 - 管理 kb_file 表
 */
@Slf4j
@Repository
public class RagFileRepository {

    private final JdbcTemplate jdbcTemplate;

    public RagFileRepository(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入文件记录，返回自增 ID
     */
    public Long insert(Long kbId, String filename) {
        String sql = "INSERT INTO kb_file (kb_id, filename, status, total_chunks) VALUES (?, ?, 'uploading', 0)";
        jdbcTemplate.update(sql, kbId, filename);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * 更新文件状态
     */
    public void updateStatus(Long fileId, String status, Integer totalChunks) {
        String sql = "UPDATE kb_file SET status = ?, total_chunks = ? WHERE id = ?";
        jdbcTemplate.update(sql, status, totalChunks != null ? totalChunks : 0, fileId);
        log.info("[RagFileRepository] 更新文件状态: fileId={}, status={}, totalChunks={}", fileId, status, totalChunks);
    }

    /**
     * 根据 ID 查询文件名
     */
    public String findFilenameById(Long fileId) {
        String sql = "SELECT filename FROM kb_file WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, String.class, fileId);
    }

    /**
     * 根据 kbId 查询所有文件
     */
    public java.util.List<FileRecord> findByKbId(Long kbId) {
        String sql = "SELECT id, kb_id, filename, status, total_chunks FROM kb_file WHERE kb_id = ? ORDER BY id DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            FileRecord record = new FileRecord();
            record.setId(rs.getLong("id"));
            record.setKbId(rs.getLong("kb_id"));
            record.setFilename(rs.getString("filename"));
            record.setStatus(rs.getString("status"));
            record.setTotalChunks(rs.getInt("total_chunks"));
            return record;
        }, kbId);
    }

    /**
     * 删除文件记录
     */
    public int deleteById(Long fileId) {
        String sql = "DELETE FROM kb_file WHERE id = ?";
        return jdbcTemplate.update(sql, fileId);
    }

}
