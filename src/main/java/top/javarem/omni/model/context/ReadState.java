package top.javarem.omni.model.context;

import java.util.Objects;

/**
 * Read 工具的 dedup 状态
 * 用于避免重复读取未修改的文件
 */
public final class ReadState {

    /**
     * 文件内容（用于内容对比）
     */
    private final String content;

    /**
     * 文件最后修改时间（毫秒）
     */
    private final long timestamp;

    /**
     * 读取起始行
     */
    private final int offset;

    /**
     * 读取行数限制，null 表示读取全部
     */
    private final Integer limit;

    /**
     * 是否为部分读取（offset > 1 或 limit 有值）
     */
    private final boolean isPartialView;

    public ReadState(String content, long timestamp, int offset, Integer limit) {
        this.content = content;
        this.timestamp = timestamp;
        this.offset = offset;
        this.limit = limit;
        this.isPartialView = offset > 1 || limit != null;
    }

    public String content() {
        return content;
    }

    public long timestamp() {
        return timestamp;
    }

    public int offset() {
        return offset;
    }

    public Integer limit() {
        return limit;
    }

    public boolean isPartialView() {
        return isPartialView;
    }

    /**
     * 检查给定的 offset 和 limit 是否与当前状态匹配
     */
    public boolean matchesRange(int offset, Integer limit) {
        if (this.offset != offset) {
            return false;
        }
        if (this.limit == null && limit == null) {
            return true;
        }
        if (this.limit == null || limit == null) {
            return false;
        }
        return Objects.equals(this.limit, limit);
    }

    /**
     * 检查文件是否未修改（通过 mtime 判断）
     */
    public boolean isUnchanged(long currentMtime) {
        return this.timestamp == currentMtime;
    }

    @Override
    public String toString() {
        return "ReadState{" +
                "timestamp=" + timestamp +
                ", offset=" + offset +
                ", limit=" + limit +
                ", isPartialView=" + isPartialView +
                ", contentLength=" + (content != null ? content.length() : 0) +
                '}';
    }
}
