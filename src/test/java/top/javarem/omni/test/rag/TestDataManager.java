package top.javarem.onmi.test.rag;

import org.springframework.stereotype.Component;
import top.javarem.onmi.test.rag.model.GoldenQAPair;
import top.javarem.onmi.test.rag.model.QuestionType;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试数据管理器 - 管理黄金问答对
 */
@Component
public class TestDataManager {

    private final List<GoldenQAPair> testCases = new ArrayList<>();

    public TestDataManager() {
        registerDefaultTestCases();
    }

    private void registerDefaultTestCases() {
        // 首期测试用例：基于产品手册
        // 注意：expectedParentIds 需要在实际运行测试前替换为真实的母块 ID
        testCases.add(new GoldenQAPair(
            "产品的退换货政策是什么？",
            List.of("期望的母块ID"),
            List.of("退换货", "退货政策", "退款", "30天"),
            "退换货政策为收货后30天内可申请",
            QuestionType.FACTUAL
        ));

        testCases.add(new GoldenQAPair(
            "保修期是多久？",
            List.of("期望的母块ID"),
            List.of("保修", "保修期", "1年"),
            "产品保修期为1年",
            QuestionType.FACTUAL
        ));

        testCases.add(new GoldenQAPair(
            "如何联系客服？",
            List.of("期望的母块ID"),
            List.of("客服", "联系方式", "电话"),
            "客服联系方式为400-xxx-xxxx",
            QuestionType.FACTUAL
        ));

        testCases.add(new GoldenQAPair(
            "产品支持哪些支付方式？",
            List.of("期望的母块ID"),
            List.of("支付", "支付宝", "微信", "银行卡"),
            "支持支付宝、微信、银行卡支付",
            QuestionType.FACTUAL
        ));

        testCases.add(new GoldenQAPair(
            "订单多久发货？",
            List.of("期望的母块ID"),
            List.of("发货", "物流", "24小时"),
            "订单24小时内发货",
            QuestionType.FACTUAL
        ));
    }

    /**
     * 获取所有测试用例
     */
    public List<GoldenQAPair> getAllTestCases() {
        return new ArrayList<>(testCases);
    }

    /**
     * 注册新的测试用例
     */
    public void registerTestCase(GoldenQAPair testCase) {
        testCases.add(testCase);
    }

    /**
     * 清空所有测试用例
     */
    public void clearTestCases() {
        testCases.clear();
    }

    /**
     * 更新测试用例中的期望母块 ID
     * 用于在实际运行测试前，将"期望的母块ID"替换为真实的数据库 ID
     */
    public void updateExpectedParentIds(int index, List<String> newParentIds) {
        if (index >= 0 && index < testCases.size()) {
            GoldenQAPair original = testCases.get(index);
            testCases.set(index, new GoldenQAPair(
                original.question(),
                newParentIds,
                original.keywords(),
                original.expectedSummary(),
                original.type()
            ));
        }
    }
}
