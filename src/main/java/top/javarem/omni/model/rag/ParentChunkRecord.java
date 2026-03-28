package top.javarem.onmi.model.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 母块数据库记录 - 对应 rag_parent_chunks 表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParentChunkRecord {
    private String id;
    private String content;
    private Map<String, Object> metadata;
    private Long fileId;
    private Integer chunkIndex;
    private Integer tokenCount;
}
