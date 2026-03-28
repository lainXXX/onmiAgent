package top.javarem.onmi.test.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.javarem.onmi.test.rag.model.TestCaseResult;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
public class TestReportGenerator {

    private static final String REPORT_DIR = "docs/test-reports/";

    /**
     * 生成 Markdown 测试报告
     */
    public void generateMarkdownReport(List<TestCaseResult> results, String reportName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = REPORT_DIR + reportName + "_" + timestamp + ".md";

        double avgRecall = results.stream().mapToDouble(TestCaseResult::recallScore).average().orElse(0);
        double avgPrecision = results.stream().mapToDouble(TestCaseResult::precisionScore).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(TestCaseResult::mrrScore).average().orElse(0);
        long passedCount = results.stream().filter(TestCaseResult::passed).count();

        StringBuilder md = new StringBuilder();
        md.append("# RAG Tool 测试报告\n\n");
        md.append("**生成时间**: ").append(timestamp).append("\n\n");
        md.append("## 测试概览\n\n");
        md.append("| 指标 | 值 |\n");
        md.append("|------|------|\n");
        md.append("| 总用例数 | ").append(results.size()).append(" |\n");
        md.append("| 通过数 | ").append(passedCount).append(" |\n");
        md.append("| 通过率 | ").append(String.format("%.1f%%", (double) passedCount / results.size() * 100)).append(" |\n");
        md.append("| 平均召回率 | ").append(String.format("%.2f%%", avgRecall * 100)).append(" |\n");
        md.append("| 平均精确率 | ").append(String.format("%.2f%%", avgPrecision * 100)).append(" |\n");
        md.append("| 平均 MRR | ").append(String.format("%.2f", avgMrr)).append(" |\n\n");

        md.append("## 详细结果\n\n");
        md.append("| 问题 | 类型 | 召回率 | 精确率 | MRR | 结果 |\n");
        md.append("|------|------|--------|--------|-----|------|\n");

        for (TestCaseResult result : results) {
            md.append("| ").append(result.question()).append(" | ");
            md.append(result.type()).append(" | ");
            md.append(String.format("%.2f%%", result.recallScore() * 100)).append(" | ");
            md.append(String.format("%.2f%%", result.precisionScore() * 100)).append(" | ");
            md.append(String.format("%.2f", result.mrrScore())).append(" | ");
            md.append(result.passed() ? "✅ PASS" : "❌ FAIL").append(" |\n");
        }

        try {
            // 确保目录存在
            new java.io.File(REPORT_DIR).mkdirs();

            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(md.toString());
            }
            log.info("测试报告已生成: {}", filename);
        } catch (IOException e) {
            log.error("生成测试报告失败", e);
        }
    }

    /**
     * 生成 JSON 测试报告（便于程序解析）
     */
    public String generateJsonReport(List<TestCaseResult> results) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"timestamp\": \"").append(LocalDateTime.now()).append("\",\n");
        json.append("  \"totalCases\": ").append(results.size()).append(",\n");
        json.append("  \"passedCases\": ").append(results.stream().filter(TestCaseResult::passed).count()).append(",\n");
        json.append("  \"avgRecall\": ").append(String.format("%.4f", results.stream().mapToDouble(TestCaseResult::recallScore).average().orElse(0))).append(",\n");
        json.append("  \"avgPrecision\": ").append(String.format("%.4f", results.stream().mapToDouble(TestCaseResult::precisionScore).average().orElse(0))).append(",\n");
        json.append("  \"avgMrr\": ").append(String.format("%.4f", results.stream().mapToDouble(TestCaseResult::mrrScore).average().orElse(0))).append(",\n");
        json.append("  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            TestCaseResult r = results.get(i);
            json.append("    {\n");
            json.append("      \"question\": \"").append(escapeJson(r.question())).append("\",\n");
            json.append("      \"type\": \"").append(r.type()).append("\",\n");
            json.append("      \"recall\": ").append(String.format("%.4f", r.recallScore())).append(",\n");
            json.append("      \"precision\": ").append(String.format("%.4f", r.precisionScore())).append(",\n");
            json.append("      \"mrr\": ").append(String.format("%.4f", r.mrrScore())).append(",\n");
            json.append("      \"passed\": ").append(r.passed()).append("\n");
            json.append("    }").append(i < results.size() - 1 ? ",\n" : "\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        return json.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
