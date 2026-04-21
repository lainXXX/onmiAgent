package top.javarem.omni.repository.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.javarem.omni.model.rag.FileRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        String sql = "INSERT INTO kb_file (kb_id, filename, status, total_chunks) VALUES (?, ?, 'uploading', 0) RETURNING id";
        return jdbcTemplate.queryForObject(sql, Long.class, kbId, filename);
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
     * 根据 ID 查询文件记录
     */
    public FileRecord findById(Long fileId) {
        String sql = "SELECT id, kb_id, filename, status, total_chunks, created_at, updated_at FROM kb_file WHERE id = ?";
        List<FileRecord> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
            FileRecord record = new FileRecord();
            record.setId(rs.getLong("id"));
            record.setKbId(rs.getObject("kb_id") != null ? rs.getLong("kb_id") : null);
            record.setFilename(rs.getString("filename"));
            record.setStatus(rs.getString("status"));
            record.setTotalChunks(rs.getInt("total_chunks"));
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            return record;
        }, fileId);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 查询所有文件
     */
    public List<FileRecord> findAll() {
        String sql = "SELECT id, kb_id, filename, status, total_chunks, created_at, updated_at FROM kb_file ORDER BY id DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            FileRecord record = new FileRecord();
            record.setId(rs.getLong("id"));
            record.setKbId(rs.getObject("kb_id") != null ? rs.getLong("kb_id") : null);
            record.setFilename(rs.getString("filename"));
            record.setStatus(rs.getString("status"));
            record.setTotalChunks(rs.getInt("total_chunks"));
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            return record;
        });
    }

    /**
     * 根据 kbId 查询所有文件
     */
    public List<FileRecord> findByKbId(Long kbId) {
        String sql = "SELECT id, kb_id, filename, status, total_chunks, created_at, updated_at FROM kb_file WHERE kb_id = ? ORDER BY id DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            FileRecord record = new FileRecord();
            record.setId(rs.getLong("id"));
            record.setKbId(rs.getObject("kb_id") != null ? rs.getLong("kb_id") : null);
            record.setFilename(rs.getString("filename"));
            record.setStatus(rs.getString("status"));
            record.setTotalChunks(rs.getInt("total_chunks"));
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
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

    /**
     * 获取知识库统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        // 总文件数
        String totalFilesSql = "SELECT COUNT(*) FROM kb_file";
        Integer totalFiles = jdbcTemplate.queryForObject(totalFilesSql, Integer.class);
        stats.put("totalFiles", totalFiles != null ? totalFiles : 0);

        // 已完成文件数
        String completedFilesSql = "SELECT COUNT(*) FROM kb_file WHERE status = 'completed'";
        Integer completedFiles = jdbcTemplate.queryForObject(completedFilesSql, Integer.class);
        stats.put("completedFiles", completedFiles != null ? completedFiles : 0);

        // 处理中文件数
        String processingFilesSql = "SELECT COUNT(*) FROM kb_file WHERE status = 'processing' OR status = 'uploading'";
        Integer processingFiles = jdbcTemplate.queryForObject(processingFilesSql, Integer.class);
        stats.put("processingFiles", processingFiles != null ? processingFiles : 0);

        // 失败文件数
        String failedFilesSql = "SELECT COUNT(*) FROM kb_file WHERE status = 'failed'";
        Integer failedFiles = jdbcTemplate.queryForObject(failedFilesSql, Integer.class);
        stats.put("failedFiles", failedFiles != null ? failedFiles : 0);

        // 总分块数
        String totalChunksSql = "SELECT COALESCE(SUM(total_chunks), 0) FROM kb_file WHERE status = 'completed'";
        Integer totalChunks = jdbcTemplate.queryForObject(totalChunksSql, Integer.class);
        stats.put("totalChunks", totalChunks != null ? totalChunks : 0);

        log.info("[RagFileRepository] 获取统计信息: {}", stats);
        return stats;
    }

}
