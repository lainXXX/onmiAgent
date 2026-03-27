package top.javarem.skillDemo.model.rag;

public class FileRecord {
        private Long id;
        private Long kbId;
        private String filename;
        private String status;
        private Integer totalChunks;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getKbId() { return kbId; }
        public void setKbId(Long kbId) { this.kbId = kbId; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Integer getTotalChunks() { return totalChunks; }
        public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    }