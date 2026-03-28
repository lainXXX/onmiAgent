package top.javarem.onmi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.javarem.onmi.model.rag.EtlProcessReport;
import top.javarem.onmi.service.rag.AdvancedRagEtlService;
import top.javarem.onmi.service.rag.CustomDocxReader;

import java.util.List;

@RestController
@RequestMapping("/api/etl")
@Slf4j
public class RagEtlController {

    // 注入向量数据库 (L: Load 环节使用)
    private final VectorStore vectorStore;
    private final AdvancedRagEtlService advancedRagEtlService;
    private final ObjectMapper objectMapper;
    private final CustomDocxReader customDocxReader;

    public RagEtlController(VectorStore vectorStore, AdvancedRagEtlService advancedRagEtlService, CustomDocxReader customDocxReader) {
        this.vectorStore = vectorStore;
        this.advancedRagEtlService = advancedRagEtlService;
        this.customDocxReader = customDocxReader;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 上传文件并执行 ETL 管道存入向量数据库
     * 接口地址: POST /api/etl/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadAndProcessFile(@RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("上传的文件不能为空！");
        }

        try {
            // 获取文件名，用于后续作为元数据(Metadata)附加到文本块中
            String filename = file.getOriginalFilename();
            System.out.println("开始处理文件: " + filename);

            // 将 MultipartFile 转换为 Spring 的 Resource
            Resource resource = file.getResource();

            // ==========================================
            // [E] Extract: 提取数据
            // ==========================================
            // 使用 TikaDocumentReader，它能自动识别 PDF, Word, TXT 等格式并提取纯文本
            List<Document> documents = customDocxReader.read(resource);
//            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
//            List<Document> documents = documentReader.read();

            // 为提取出的文档手动增加 "文件名" 元数据，方便日后溯源和过滤检索
            for (Document doc : documents) {
                doc.getMetadata().put("source_filename", filename);
            }

            // ==========================================
            // [T] Transform: 转换/文本切分
            // ==========================================
            // 采用 TokenTextSplitter 进行分块，防止超出大模型上下文限制
            // defaultChunkSize: 800 (每个分块大约800个Token)
            // minChunkSizeChars: 350 (最小字符数)
            // keepSeparator: true (保留换行符等分隔符)
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> chunkedDocuments = splitter.apply(documents);

            System.out.println("文件 [" + filename + "] 已被切分为 " + chunkedDocuments.size() + " 个片段。");

            // ==========================================
            // [L] Load: 加载/存入向量数据库
            // ==========================================
            // vectorStore 会自动调用 EmbeddingModel 将纯文本转为向量(特征数组)并持久化
            vectorStore.accept(chunkedDocuments); // 或者使用 vectorStore.add(chunkedDocuments)

            return ResponseEntity.ok("文件 [" + filename + "] ETL处理成功！共生成 " + chunkedDocuments.size() + " 个向量分块入库。");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("ETL 处理失败: " + e.getMessage());
        }
    }

    /**
     * 处理单个上传文件 (母子嵌套分块)
     */
    @PostMapping("/process/file")
    public ResponseEntity<String> processSingleFileWithParentChildChunking(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbId", required = false) String kbId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("上传的文件不能为空！");
        }

        try {
            log.info("开始处理文件: {}, kbId: {}", file.getOriginalFilename(), kbId);
            EtlProcessReport report = advancedRagEtlService.processFile(file, kbId);

            StringBuilder response = new StringBuilder();
            response.append("=== 母子嵌套递归分块处理完成 ===\n\n");
            response.append("文件: ").append(file.getOriginalFilename()).append("\n");
            if (kbId != null) {
                response.append("知识库: ").append(kbId).append("\n");
            }
            response.append("生成母块数: ").append(report.getParentChunkCount()).append("\n");
            response.append("生成子块数: ").append(report.getChildChunkCount()).append("\n");
            response.append("平均母块Token数: ").append(String.format("%.2f", report.getAvgTokenPerParent())).append("\n");
            response.append("平均子块Token数: ").append(String.format("%.2f", report.getAvgTokenPerChild())).append("\n");
            response.append("总Token消耗: ").append(report.getTotalTokenConsumed()).append("\n");
            response.append("处理耗时: ").append(report.getProcessTimeMs()).append("ms\n");

            return ResponseEntity.ok(response.toString());

        } catch (Exception e) {
            log.error("处理失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("处理失败: " + e.getMessage());
        }
    }

    /**
     * 母子嵌套递归分块处理
     */
    @PostMapping("/process")
    public ResponseEntity<String> processWithParentChildChunking(
            @RequestParam(value = "path", required = false, defaultValue = "classpath:elt_test/*") String resourcePath,
            @RequestParam(value = "kbId", required = false) String kbId) {

        try {
            log.info("开始母子嵌套递归分块处理, 路径: {}, kbId: {}", resourcePath, kbId);
            EtlProcessReport report = advancedRagEtlService.processDirectory(resourcePath, kbId);

            StringBuilder response = new StringBuilder();
            response.append("=== 母子嵌套递归分块处理完成 ===\n\n");
            response.append("处理文件数: ").append(report.getProcessedFileCount()).append("\n");
            if (kbId != null) {
                response.append("知识库: ").append(kbId).append("\n");
            }
            response.append("生成母块数: ").append(report.getParentChunkCount()).append("\n");
            response.append("生成子块数: ").append(report.getChildChunkCount()).append("\n");
            response.append("平均母块Token数: ").append(String.format("%.2f", report.getAvgTokenPerParent())).append("\n");
            response.append("平均子块Token数: ").append(String.format("%.2f", report.getAvgTokenPerChild())).append("\n");
            response.append("总Token消耗: ").append(report.getTotalTokenConsumed()).append("\n");
            response.append("处理耗时: ").append(report.getProcessTimeMs()).append("ms\n");

            log.info("处理完成: {}", response);
            return ResponseEntity.ok(response.toString());

        } catch (Exception e) {
            log.error("处理失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("处理失败: " + e.getMessage());
        }
    }

    /**
     * 检索测试接口 - 验证RAG效果
     */
    @PostMapping("/search")
    public ResponseEntity<String> search(@RequestParam String query,
                                         @RequestParam(defaultValue = "5") int topK) {
        try {
            log.info("检索查询: {}, topK={}", query, topK);
            String response = advancedRagEtlService.retrieveContext(query, topK, null).toString();
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("检索失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("检索失败: " + e.getMessage());
        }
    }
}