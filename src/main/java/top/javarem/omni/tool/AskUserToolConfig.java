package top.javarem.omni.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import top.javarem.omni.model.Option;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 向用户提问工具
 * 按照 Spring AI Tool 开发规范实现
 * 支持多问题结构化提问，包含选项单选/多选
 */
@Component
@Slf4j
public class AskUserToolConfig implements AgentTool {

    /**
     * 等待用户输入超时时间（秒）
     */
    private static final int INPUT_TIMEOUT_SECONDS = 600;

    /**
     * 共享的 Scanner 实例，避免重复关闭 System.in
     */
    private static final java.util.Scanner SCANNER = new java.util.Scanner(System.in);

    /**
     * 向用户提问（支持多问题结构化提问）
     *
     * @param questions 问题数组，支持多个问题
     * @return 用户的回答
     */
    public record AskUserRequest(
            @ToolParam(description = "问题数组，支持多个问题。每个问题包含: header(分类标签), question(问题文本), options(选项列表), multiSelect(是否多选)") List<Question> questions
    ) {}
    @Tool(description = "向用户提问。适用：信息不足/高风险确认/需要选择。调用后暂停等待用户选择")
    public String askUser(AskUserRequest request) {

        // 1. 归一化输入
        if (request.questions == null || request.questions.isEmpty()) {
            return "问题数组不能为空，请提供有效的问题内容。";
        }

        StringBuilder resultBuilder = new StringBuilder();

        // 2. 渐进式提问：逐个处理问题
        for (int i = 0; i < request.questions.size(); i++) {
            Question q = request.questions.get(i);
            String answer = processQuestion(q, i + 1, request.questions.size());

            resultBuilder.append(answer);

            // 如果不是最后一个问题，添加分隔符
            if (i < request.questions.size() - 1) {
                resultBuilder.append("\n\n---\n\n");
            }
        }

        return resultBuilder.toString();
    }

    /**
     * 处理单个问题
     */
    private String processQuestion(Question question, int current, int total) {
        // 打印问题 UI
        printQuestionUI(question, current, total);

        // 获取用户输入
        String answer = readUserInput();

        if (answer == null || answer.isBlank()) {
            return String.format("⏱️ 问题 [%d/%d] 等待用户输入超时或未收到有效回答。", current, total);
        }

        // 验证并处理答案
        return validateAndFormatAnswer(question, answer, current);
    }

    /**
     * 打印问题 UI
     */
    private void printQuestionUI(Question question, int current, int total) {
        System.out.println();
        System.out.println();

        // 进度指示
        if (total > 1) {
            System.out.printf("📍 进度: %d/%d\n\n", current, total);
        }

        // 分类标签
        if (question.header() != null && !question.header().isBlank()) {
            System.out.println("🏷️  " + question.header());
        }

        // 问题文本
        System.out.println("❓ " + question.question());
        System.out.println();

        // 选项列表
        List<Option> options = question.options();
        if (options != null && !options.isEmpty()) {
            System.out.println("───────────────────────────────────────────────────────");
            for (int i = 0; i < options.size(); i++) {
                Option opt = options.get(i);
                System.out.printf("  [%d] %s%n", i + 1, opt.label());
                if (opt.description() != null && !opt.description().isBlank()) {
                    System.out.printf("      └─ %s%n", opt.description());
                }
            }
            System.out.println("───────────────────────────────────────────────────────");
            System.out.println();
        }

        // 输入提示
        String inputHint = question.multiSelect()
                ? "👉 请输入选项编号（单选输入数字，如：1；多选输入逗号分隔，如：1,3）："
                : "👉 请输入选项编号（直接输入数字，如：1）：";
        System.out.print(inputHint);
        System.out.flush();
    }

    /**
     * 验证并格式化答案
     */
    private String validateAndFormatAnswer(Question question, String answer, int current) {
        List<Option> options = question.options();

        if (options == null || options.isEmpty()) {
            return String.format("✅ [问题 %d] 用户的回答：%s", current, answer);
        }

        // 解析输入的选项编号
        String[] parts = answer.split(",");
        StringBuilder result = new StringBuilder();
        boolean valid = true;

        for (String part : parts) {
            String trimmed = part.trim();
            try {
                int index = Integer.parseInt(trimmed);
                if (index < 1 || index > options.size()) {
                    valid = false;
                    break;
                }
                Option selected = options.get(index - 1);
                if (result.length() > 0) {
                    result.append(", ");
                }
                result.append(selected.label());
            } catch (NumberFormatException e) {
                valid = false;
                break;
            }
        }

        if (!valid || result.length() == 0) {
            return String.format("⚠️ [问题 %d] 输入无效，将记录原始输入：%s", current, answer);
        }

        return String.format("✅ [问题 %d] 用户的回答：%s", current, result);
    }

    /**
     * 读取用户输入（带超时）
     */
    private String readUserInput() {
        AtomicReference<String> answerRef = new AtomicReference<>();
        Thread inputReader = new Thread(() -> {
            try {
                // 使用共享的 Scanner，避免重复创建和关闭
                synchronized (SCANNER) {
                    if (SCANNER.hasNextLine()) {
                        String line = SCANNER.nextLine();
                        answerRef.set(line);
                    }
                }
            } catch (IllegalStateException e) {
                log.warn("System.in 流已关闭或不可用", e);
            } catch (Exception e) {
                log.warn("读取用户输入失败", e);
            }
        });

        inputReader.setDaemon(true);
        inputReader.start();

        try {
            inputReader.join(INPUT_TIMEOUT_SECONDS * 1000L);
            return answerRef.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // 内部记录类型：问题定义
    public record Question(
            @ToolParam(description = "分类标签。例如：'个人信息'、'支付确认'、'偏好设置'")
            String header,

            @ToolParam(description = "向用户展示的具体问题文本。例如：'请问您的联系电话是多少？'")
            String question,

            @ToolParam(description = "可选。如果提供此项，用户将通过按钮选择。例如：['微信', '支付宝']", required = false)
            List<Option> options,

            @ToolParam(description = "是否支持多选。默认为 false", required = false)
            Boolean multiSelect
    ) {}
}
