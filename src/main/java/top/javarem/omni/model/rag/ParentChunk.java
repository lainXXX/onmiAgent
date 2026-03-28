package top.javarem.omni.model.rag;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 母块 (Parent Chunk) - 提供给LLM的最终上下文
 */
@Data
public class ParentChunk {
    private String id;
    private String content;
    private int tokenCount;
    private String sourceFile;
    private List<ChildChunk> childChunks = new ArrayList<>();

    public ParentChunk(String id, String content, int tokenCount, String sourceFile) {
        this.id = id;
        this.content = content;
        this.tokenCount = tokenCount;
        this.sourceFile = sourceFile;
    }
}
