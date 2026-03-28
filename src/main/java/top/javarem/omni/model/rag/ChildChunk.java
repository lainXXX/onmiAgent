package top.javarem.omni.model.rag;

import lombok.Data;

import java.util.Map;

/**
 * 子块 (Child Chunk) - 用于向量检索
 */
@Data
public class ChildChunk {
    private String id;
    private String content;
    private int tokenCount;
    private String parentId;
    private String sourceFile;
    private Map<String, Object> metadata;

    public ChildChunk(String id, String content, int tokenCount, String parentId, String sourceFile) {
        this.id = id;
        this.content = content;
        this.tokenCount = tokenCount;
        this.parentId = parentId;
        this.sourceFile = sourceFile;
    }
}
