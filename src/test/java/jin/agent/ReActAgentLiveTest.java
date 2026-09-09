package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.react.ReActEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class ReActAgentLiveTest {

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
    private ReActEngine reActEngine;

    @Test
    @DisplayName("测试手写 ReAct 智能体多步推理：查天气并进行复合计算")
    void testReActAgent() {
        String query = "请帮我查一下北京现在的气温是多少度？如果把这个气温乘以 2.5 再加上 10，结果是多少？";
        String answer = reActEngine.run(query, 5);

        System.out.println("\n🎉🎉🎉 [最终测试结果] 🎉🎉🎉");
        System.out.println(answer);
        System.out.println("=============================\n");

        assertNotNull(answer);
    }
}
