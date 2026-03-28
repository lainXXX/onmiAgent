package top.javarem.omni.model;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AskUserQuestionRequest JSON 反序列化测试
 * TDD: 先写测试，再实现功能
 */
@Slf4j
public class AskUserQuestionRequestTest {

    @Test
    void testFromJson_SingleQuestion() {
        String json = """
            {
                "questions": [{
                    "header": "技术栈",
                    "question": "你希望使用什么技术栈？",
                    "options": [
                        {"label": "Java", "description": "企业级"},
                        {"label": "Python", "description": "快速开发"}
                    ],
                    "multiSelect": false
                }],
                "metadata": {"source": "test"}
            }
            """;

        AskUserQuestionRequest request = AskUserQuestionRequest.fromJson(json);

        assertNotNull(request);
        assertNotNull(request.questions());
        assertEquals(1, request.questions().size());

        Question q = request.questions().get(0);
        assertEquals("技术栈", q.header());
        assertEquals("你希望使用什么技术栈？", q.question());
        assertEquals(2, q.options().size());
        assertFalse(q.multiSelect());

        assertEquals("Java", q.options().get(0).label());
        assertEquals("企业级", q.options().get(0).description());
    }

    @Test
    void testFromJson_MultipleQuestions() {
        String json = """
            {
                "questions": [
                    {
                        "header": "技术栈",
                        "question": "你希望使用什么技术栈？",
                        "options": [
                            {"label": "Java", "description": "企业级"},
                            {"label": "Python", "description": "快速开发"}
                        ],
                        "multiSelect": false
                    },
                    {
                        "header": "功能",
                        "question": "需要哪些功能？",
                        "options": [
                            {"label": "用户管理", "description": "CRUD"},
                            {"label": "权限控制", "description": "RBAC"}
                        ],
                        "multiSelect": true
                    }
                ]
            }
            """;

        AskUserQuestionRequest request = AskUserQuestionRequest.fromJson(json);

        assertNotNull(request);
        assertEquals(2, request.questions().size());

        Question q1 = request.questions().get(0);
        assertFalse(q1.multiSelect());

        Question q2 = request.questions().get(1);
        assertTrue(q2.multiSelect());
    }

    @Test
    void testFromJson_WithPreview() {
        String json = """
            {
                "questions": [{
                    "header": "方案",
                    "question": "选择哪种方案？",
                    "options": [
                        {"label": "方案A", "description": "高性能", "preview": "代码片段..."},
                        {"label": "方案B", "description": "低成本"}
                    ],
                    "multiSelect": false
                }]
            }
            """;

        AskUserQuestionRequest request = AskUserQuestionRequest.fromJson(json);

        Question q = request.questions().get(0);
        assertEquals("方案A", q.options().get(0).label());
        assertEquals("代码片段...", q.options().get(0).preview());
        assertNull(q.options().get(1).preview());
    }

    @Test
    void testFromJson_InvalidJson_ThrowsException() {
        String invalidJson = "not valid json";

        assertThrows(RuntimeException.class, () -> {
            AskUserQuestionRequest.fromJson(invalidJson);
        });
    }

    @Test
    void testFromJson_EmptyQuestions() {
        String json = """
            {
                "questions": [],
                "metadata": {}
            }
            """;

        AskUserQuestionRequest request = AskUserQuestionRequest.fromJson(json);

        assertNotNull(request);
        assertTrue(request.questions().isEmpty());
    }
}
