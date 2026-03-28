package top.javarem.omni.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class CustomDocxReader {
//
//    private final ImageOcrService imageOcrService;
//
//    public CustomDocxReader(ImageOcrService imageOcrService) {
//        this.imageOcrService = imageOcrService;
//    }

    /**
     * 智能读取 Word 文件 (兼容 .doc 和 .docx)
     */
    public List<Document> read(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.doc";
        }

        log.info("开始解析 Word 文件: {}", filename);
        StringBuilder contentBuilder = new StringBuilder();

        // 使用 BufferedInputStream，因为 POI 的 FileMagic 需要支持 mark/reset
        try (InputStream is = new BufferedInputStream(resource.getInputStream())) {

            // 1. 智能探测真实文件格式 (无视扩展名)
            FileMagic fm = FileMagic.valueOf(is);

            if (fm == FileMagic.OOXML) {
                // 处理现代的 .docx
                log.debug("识别为 OOXML 格式 (.docx)");
                processDocx(is, contentBuilder);
            } else if (fm == FileMagic.OLE2) {
                // 处理老旧的 .doc
                log.debug("识别为 OLE2 格式 (.doc)");
                processDoc(is, contentBuilder);
            } else {
                throw new IllegalArgumentException("不支持的文件格式: " + fm);
            }

            // 2. 封装返回
            String finalContent = contentBuilder.toString().trim();
            Document document = new Document(
                    finalContent,
                    Collections.singletonMap("source", filename)
            );
            return List.of(document);

        } catch (Exception e) {
            log.error("解析 Word 文件失败: {}", filename, e);
            throw new RuntimeException("解析 Word 文件失败: " + filename, e);
        }
    }

    /**
     * 处理新版 .docx (保留你之前的精细化图表提取逻辑)
     */
    private void processDocx(InputStream is, StringBuilder contentBuilder) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            List<IBodyElement> bodyElements = doc.getBodyElements();

            for (IBodyElement element : bodyElements) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    processParagraph((XWPFParagraph) element, contentBuilder);
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    String markdownTable = convertTableToMarkdown((XWPFTable) element);
                    contentBuilder.append(markdownTable).append("\n\n");
                }
            }
        }
    }

    /**
     * 处理老版 .doc (HWPF 格式)
     * 注意：由于老版 doc 格式的限制，这里采用纯文本提取降级方案。
     * 复杂的表格和图片提取在 .doc 中极其不稳定，不建议在生产环境强求。
     */
    private void processDoc(InputStream is, StringBuilder contentBuilder) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {

            // 提取老版本 doc 的纯文本
            String text = extractor.getText();
            if (text != null) {
                // 清理多余的连续换行
                text = text.replaceAll("(?m)^\\s*\\r?\\n", "");
                contentBuilder.append(text).append("\n\n");
            }
        }
    }

    // --- 下面是你原有的精确提取 docx 段落、图片、表格的方法，原样保留 ---

    private void processParagraph(XWPFParagraph paragraph, StringBuilder contentBuilder) {
        StringBuilder paragraphText = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String text = run.text();
            if (text != null && !text.isEmpty()) {
                paragraphText.append(text);
            }

            // 处理图片
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            for (XWPFPicture pic : pictures) {
                XWPFPictureData picData = pic.getPictureData();
                byte[] byteData = picData.getData();
                String fileName = picData.getFileName();
                String extension = picData.suggestFileExtension();
//                String imageDescription = imageOcrService.describeImage(byteData, fileName, extension);
//                paragraphText.append(imageDescription);
            }
        }
        String finalParaText = paragraphText.toString().trim();
        if (!finalParaText.isEmpty()) {
            contentBuilder.append(finalParaText).append("\n\n");
        }
    }

    private String convertTableToMarkdown(XWPFTable table) {
        StringBuilder mdTable = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) return "";

        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<XWPFTableCell> cells = row.getTableCells();

            mdTable.append("|");
            for (XWPFTableCell cell : cells) {
                String cellText = cell.getText().replaceAll("[\\r\\n]+", "<br>").trim();
                cellText = cellText.replace("|", "\\|");
                mdTable.append(" ").append(cellText).append(" |");
            }
            mdTable.append("\n");

            if (i == 0) {
                mdTable.append("|");
                for (int j = 0; j < cells.size(); j++) {
                    mdTable.append("---|");
                }
                mdTable.append("\n");
            }
        }
        return mdTable.toString();
    }
}