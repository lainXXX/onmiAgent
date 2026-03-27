import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.javarem.skillDemo.Application;
import top.javarem.skillDemo.loader.SkillLoader;
import top.javarem.skillDemo.controller.ChatController;

import static org.junit.jupiter.api.Assertions.*;


@SpringBootTest(classes = Application.class)
@Slf4j
class ApplicationTests {

    @Autowired
    private ChatClient openAiChatClient;

    @Autowired
    private SkillLoader skillLoader;

    @Autowired
    private ChatController chatController;


    @Test
    public void testChatClient() {
        String message = "你是谁";

        String content = openAiChatClient.prompt()
                .user(message)
                .call()
                .content();

        log.info("content: {}", content);
        assertNotNull(content);
    }



}
