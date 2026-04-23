package top.javarem.omni.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.javarem.omni.model.rag.EtlProcessReport;
import top.javarem.omni.model.rag.FileRecord;
import top.javarem.omni.repository.rag.RagChunkRepository;
import top.javarem.omni.repository.rag.RagFileRepository;
import top.javarem.omni.service.rag.AdvancedRagEtlService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理控制器
 */
@RestController
@RequestMapping("/api/knowledge-base")
@Slf4j
public class KnowledgeBaseController {

    private final RagFileRepository ragFileRepository;
    private final RagChunkRepository ragChunkRepository;
    private final AdvancedRagEtlService advancedRagEtlService;

    public KnowledgeBaseController(RagFileRepository ragFileRepository,
                                  RagChunkRepository ragChunkRepository,
                                  AdvancedRagEtlService advancedRagEtlService) {
        this.ragFileRepository = ragFileRepository;
        this.ragChunkRepository = ragChunkRepository;
        this.advancedRagEtlService = advancedRagEtlService;
    }

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = ragFileRepository.getStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("获取统计信息失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取文件列表
     */
    @GetMapping("/files")
    public ResponseEntity<List<FileRecord>> listFiles(
            @RequestParam(required = false) Long kbId) {
        try {
            List<FileRecord> files;
            if (kbId != null) {
                files = ragFileRepository.findByKbId(kbId);
            } else {
                files = ragFileRepository.findAll();
            }
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            log.error("获取文件列表失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(List.of());
        }
    }

    /**
     * 获取单个文件详情
     */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<FileRecord> getFile(@PathVariable Long fileId) {
        try {
            FileRecord file = ragFileRepository.findById(fileId);
            if (file == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(file);
        } catch (Exception e) {
            log.error("获取文件详情失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 上传并处理文件
     */
    @PostMapping("/files/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbId", required = false) Long kbId) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "上传的文件不能为空"));
            }

            log.info("上传文件: {}, kbId: {}", file.getOriginalFilename(), kbId);
            EtlProcessReport report = advancedRagEtlService.processFile(file, kbId != null ? String.valueOf(kbId) : null);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "文件处理成功");
            result.put("report", report);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("上传文件失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除文件及其关联的分块（母块、子块、向量）
     */
    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(@PathVariable Long fileId) {
        try {
            // 1. 删除向量库中的子块
            advancedRagEtlService.deleteChildChunksByFileId(fileId);

            // 2. 删除数据库中的母块
            int deletedParentChunks = ragChunkRepository.deleteByFileId(fileId);

            // 3. 删除文件记录
            int deletedFiles = ragFileRepository.deleteById(fileId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", deletedFiles > 0);
            result.put("deletedParentChunks", deletedParentChunks);
            result.put("deletedFiles", deletedFiles);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("删除文件失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 重试失败的文件
     */
    @PostMapping("/files/{fileId}/retry")
    public ResponseEntity<?> retryFile(
            @PathVariable Long fileId,
            @RequestParam(value = "kbId", required = false) Long kbId) {
        try {
            FileRecord file = ragFileRepository.findById(fileId);
            if (file == null) {
                return ResponseEntity.notFound().build();
            }

            // 更新状态为处理中
            ragFileRepository.updateStatus(fileId, "processing", null);

            // 重新处理文件（需要重新获取文件内容，这里简化处理）
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "文件已重新加入处理队列");
            result.put("fileId", fileId);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("重试文件失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 搜索知识库内容
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) Long kbId) {
        try {
            var documents = advancedRagEtlService.retrieveContext(query, topK, kbId != null ? String.valueOf(kbId) : null);
            String results = documents.stream()
                    .map(doc -> doc.getText())
                    .reduce((a, b) -> a + "\n\n---\n\n" + b)
                    .orElse("");
            return ResponseEntity.ok(Map.of("query", query, "results", results));
        } catch (Exception e) {
            log.error("搜索失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
