package top.javarem.omni.service.rag;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class TokenCounter {

    private Encoding encoding;

    @PostConstruct
    public void init() {
        // 初始化编码器注册表
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();

        // 根据模型选择编码类型：
        // GPT-4, GPT-3.5 使用 CL100K_BASE
        // GPT-4o 使用 O200K_BASE (需 1.1.0+ 版本)
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * 使用 jtokkit 进行 100% 精准的 Token 计数
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 直接将文本编码为 Token 序列并返回长度
        return encoding.countTokens(text);
    }
}