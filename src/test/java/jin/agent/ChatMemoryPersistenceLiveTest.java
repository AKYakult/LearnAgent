package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.declarative.Assistant;
import jin.agent.memory.PostgresChatMemoryStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段五：会话隔离与 PostgreSQL 持久化回归测试 (ChatMemoryPersistenceLiveTest)
 * 验证：
 * 1. 多用户多会话彻底隔离（不同 conversationId 上下文互不污染）
 * 2. PostgreSQL 物理落盘与冷启动记忆恢复（evict 驱逐内存后仍能读取 DB 恢复记忆）
 * 3. 复杂工具调用 (Function Calling) 结构化上下文保真存盘
 * 4. 会话清理与物理删除
 */
@SpringBootTest
public class ChatMemoryPersistenceLiveTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgresChatMemoryStore postgresChatMemoryStore;

    @Test
    @DisplayName("测试用例 1：多会话彻底隔离性（不同 conversationId 上下文互不干扰）")
    void testMultiSessionIsolation() {
        String sessionAlice = "test-session-alice-" + System.currentTimeMillis();
        String sessionBob = "test-session-bob-" + System.currentTimeMillis();

        try {
            System.out.println("\n========== [1. 爱丽丝向会话 A 建立人设] ==========");
            String replyAlice1 = assistant.chat(sessionAlice, "你好，我是爱丽丝，我最喜欢的颜色是紫色。");
            System.out.println("爱丽丝助手回复: " + replyAlice1);
            assertNotNull(replyAlice1);

            System.out.println("\n========== [2. 鲍勃向会话 B 建立人设] ==========");
            String replyBob1 = assistant.chat(sessionBob, "你好，我是鲍勃，我最喜欢的颜色是天蓝色。");
            System.out.println("鲍勃助手回复: " + replyBob1);
            assertNotNull(replyBob1);

            System.out.println("\n========== [3. 交叉询问：会话 A 验证自己是谁] ==========");
            String queryAlice = assistant.chat(sessionAlice, "请问我叫什么名字？我最喜欢什么颜色？请简短回答。");
            System.out.println("爱丽丝询问回复: " + queryAlice);
            assertTrue(queryAlice.contains("爱丽丝"), "会话 A 必须能够回答出爱丽丝");
            assertFalse(queryAlice.contains("鲍勃"), "会话 A 绝对不能出现鲍勃的信息（防止串戏）");

            System.out.println("\n========== [4. 交叉询问：会话 B 验证自己是谁] ==========");
            String queryBob = assistant.chat(sessionBob, "请问我叫什么名字？我最喜欢什么颜色？请简短回答。");
            System.out.println("鲍勃询问回复: " + queryBob);
            assertTrue(queryBob.contains("鲍勃"), "会话 B 必须能够回答出鲍勃");
            assertFalse(queryBob.contains("爱丽丝"), "会话 B 绝对不能出现爱丽丝的信息（防止串戏）");

        } finally {
            // 清理测试会话
            assistant.evictChatMemory(sessionAlice);
            assistant.evictChatMemory(sessionBob);
            postgresChatMemoryStore.deleteMessages(sessionAlice);
            postgresChatMemoryStore.deleteMessages(sessionBob);
        }
    }

    @Test
    @DisplayName("测试用例 2：PostgreSQL 物理存盘与内存冷驱逐恢复（Cold Restart Recovery）")
    void testPostgresPersistenceAndColdRestartRecovery() {
        String sessionId = "test-persist-session-" + System.currentTimeMillis();
        String projectCode = "Project-Galaxy-9988";
        String petName = "阿奇";

        try {
            System.out.println("\n========== [1. 发送包含关键业务特征的对话信息] ==========");
            String reply1 = assistant.chat(sessionId, "请记住我目前负责的重点研发代号是 " + projectCode + "，我养了一只可爱的金毛名叫 " + petName + "。");
            System.out.println("助手回复: " + reply1);
            assertNotNull(reply1);

            System.out.println("\n========== [2. 物理查验 PostgreSQL 数据库中的快照表] ==========");
            String sql = "SELECT messages_json FROM chat_memory_store WHERE conversation_id = ?";
            String messagesJson = jdbcTemplate.queryForObject(sql, String.class, sessionId);
            System.out.println("数据库中存储的快照 JSON 片段: " + (messagesJson.length() > 120 ? messagesJson.substring(0, 120) + "..." : messagesJson));
            assertNotNull(messagesJson, "PostgreSQL 中必须存在该会话的持久化记录");
            assertTrue(messagesJson.contains(projectCode), "数据库 JSON 快照中必须包含项目代号");
            assertTrue(messagesJson.contains(petName), "数据库 JSON 快照中必须包含宠物姓名");

            System.out.println("\n========== [3. 强制驱逐 JVM 内存缓存（模拟服务重启）] ==========");
            boolean evicted = assistant.evictChatMemory(sessionId);
            System.out.println("内存缓存驱逐成功: " + evicted);

            System.out.println("\n========== [4. 冷启动后再次提问，验证从 DB 反序列化恢复] ==========");
            String reply2 = assistant.chat(sessionId, "我负责的项目代号叫什么？我的宠物叫什么名字？请简短回答。");
            System.out.println("冷恢复后助手回复: " + reply2);
            assertTrue(reply2.contains(projectCode) || reply2.contains("Galaxy-9988"), "助手在内存被清空后，必须能从 PostgreSQL 读取历史并回答正确项目代号");
            assertTrue(reply2.contains(petName), "助手在内存被清空后，必须能从 PostgreSQL 读取历史并回答正确宠物名字");

        } finally {
            assistant.evictChatMemory(sessionId);
            postgresChatMemoryStore.deleteMessages(sessionId);
        }
    }

    @Test
    @DisplayName("测试用例 3：工具调用（Function Calling）复杂上下文保真持久化")
    void testToolExecutionPersistence() {
        String sessionId = "test-tool-persist-" + System.currentTimeMillis();

        try {
            System.out.println("\n========== [1. 触发数学计算工具调用] ==========");
            String reply1 = assistant.chat(sessionId, "请计算半径为 3.0 的圆的面积是多少？");
            System.out.println("工具调用助手回复: " + reply1);
            assertNotNull(reply1);

            System.out.println("\n========== [2. 查验数据库中是否完整持久化了工具调用消息] ==========");
            String sql = "SELECT messages_json FROM chat_memory_store WHERE conversation_id = ?";
            String messagesJson = jdbcTemplate.queryForObject(sql, String.class, sessionId);
            assertNotNull(messagesJson);
            // LangChain4j 会将 AiMessage (含 toolExecutionRequests) 以及 ToolExecutionResultMessage 保存在同一个 JSON 数组中
            System.out.println("包含工具调用的 JSON 快照片段: " + (messagesJson.length() > 200 ? messagesJson.substring(0, 200) + "..." : messagesJson));
            assertTrue(messagesJson.contains("calculateCircleArea") || messagesJson.contains("toolExecutionRequests"),
                    "持久化快照中必须包含工具调用的元数据结构");

            System.out.println("\n========== [3. 跨轮次基于工具计算结果追问] ==========");
            String reply2 = assistant.chat(sessionId, "把刚才计算出的面积结果再乘以 2 是多少？");
            System.out.println("追问助手回复: " + reply2);
            assertNotNull(reply2);

        } finally {
            assistant.evictChatMemory(sessionId);
            postgresChatMemoryStore.deleteMessages(sessionId);
        }
    }
}
