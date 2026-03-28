package top.javarem.omni.model.rag;

import lombok.Data;

/**
 * ETL处理报告
 */
@Data
public class EtlProcessReport {
    private int processedFileCount;
    private int parentChunkCount;
    private int childChunkCount;
    private double avgTokenPerParent;
    private double avgTokenPerChild;
    private long totalTokenConsumed;
    private long processTimeMs;

    public EtlProcessReport(int processedFileCount, int parentChunkCount, int childChunkCount,
                            double avgTokenPerParent, double avgTokenPerChild,
                            long totalTokenConsumed, long processTimeMs) {
        this.processedFileCount = processedFileCount;
        this.parentChunkCount = parentChunkCount;
        this.childChunkCount = childChunkCount;
        this.avgTokenPerParent = avgTokenPerParent;
        this.avgTokenPerChild = avgTokenPerChild;
        this.totalTokenConsumed = totalTokenConsumed;
        this.processTimeMs = processTimeMs;
    }
}
