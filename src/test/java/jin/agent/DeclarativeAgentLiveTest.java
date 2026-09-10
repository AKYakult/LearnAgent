package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.declarative.Assistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class DeclarativeAgentLiveTest {

    @BeforeAll
    static void init() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null && System.getenv(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }

    @Autowired
    private Assistant assistant;

    @Test
    @DisplayName("测试声明式 Agent：多轮对话记忆与自动工具调用")
    void testMultiTurnConversationAndTools() {
        System.out.println("\n========== [第 1 轮对话：建立上下文记忆] ==========");
        String reply1 = assistant.chat("你好，我叫李华，我今年准备深入学习 AI 智能体开发。");
        System.out.println("助手回复:\n" + reply1);
        assertNotNull(reply1);

        System.out.println("\n========== [第 2 轮对话：触发自动工具调用] ==========");
        String reply2 = assistant.chat("请帮我计算一个半径为 5.0 的圆的面积是多少？");
        System.out.println("助手回复:\n" + reply2);
        assertNotNull(reply2);

        System.out.println("\n========== [第 3 轮对话：验证跨轮记忆] ==========");
        String reply3 = assistant.chat("你还记得我叫什么名字吗？");
        System.out.println("助手回复:\n" + reply3);
        assertNotNull(reply3);
        assertTrue(reply3.contains("李华"), "助手应该能够准确回想起第一轮对话中的姓名");
    }
}