package top.javarem.omni.utils;

import java.util.*;
import java.util.stream.Collectors;

public class MarkdownUtil {

    /**
     * 直接将 List<Map> 转换为 MD 表格，自动提取 Key 作为表头
     */
    public static String renderTable(List<Map<String, Object>> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return "> ⚠️ 暂无数据内容";
        }

        // 1. 从第一行数据中提取所有 Key 作为表头
        // 使用 Set 保证唯一，如果需要顺序一致，建议 SQL 查询时指定列名
        Set<String> headers = dataList.get(0).keySet();

        StringBuilder sb = new StringBuilder();

        // 2. 生成表头行
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");

        // 3. 生成分割线
        String divider = headers.stream()
                .map(h -> "---")
                .collect(Collectors.joining(" | ", "| ", " |"));
        sb.append(divider).append("\n");

        // 4. 填充每一行数据
        for (Map<String, Object> row : dataList) {
            sb.append("| ");
            for (String header : headers) {
                // 处理 null 值并过滤掉换行符，防止破坏表格结构
                Object val = row.get(header);
                String cell = (val == null) ? "-" : String.valueOf(val).replace("\n", " ");
                sb.append(cell).append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}