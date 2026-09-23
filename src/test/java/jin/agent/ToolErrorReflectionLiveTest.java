package jin.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.declarative.Assistant;
import jin.agent.memory.PostgresChatMemoryStore;
import jin.agent.tool.errorhandler.CustomToolArgumentsErrorHandler;
import jin.agent.tool.errorhandler.CustomToolExecutionErrorHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段五·里程碑 5.3：工具容错、反思自愈与智能重试回归测试 (ToolErrorReflectionLiveTest)
 *
 * 验证：
 * 1. CustomToolExecutionErrorHandler 异常分流机制：
 *    - 业务校验异常 (IllegalArgumentException) 脱敏并引导重试；
 *    - 致命系统异常 (RuntimeException) 严格阻断脱敏，绝不泄漏底层堆栈与敏感信息；
 * 2. CustomToolArgumentsErrorHandler 入参解析异常友好拦截；
 * 3. 端到端大模型认知外环（Self-Correction）反思自愈：
 *    - 传入负数半径触发业务校验受阻后，大模型自主反思并将入参修正为正数重新计算成功。
 */
@SpringBootTest
public class ToolErrorReflectionLiveTest {

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
    private PostgresChatMemoryStore postgresChatMemoryStore;

    @Test
    @DisplayName("测试用例 1：CustomToolExecutionErrorHandler 异常分流与安全脱敏单元验证")
    void testErrorHandlerClassificationAndSanitization() {
        CustomToolExecutionErrorHandler executionHandler = new CustomToolExecutionErrorHandler();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-test-123")
                .name("calculateCircleArea")
                .arguments("{\"radius\": -5.0}")
                .build();
        ToolErrorContext context = ToolErrorContext.builder()
                .toolExecutionRequest(request)
                .memoryId("test-session")
                .rawError(new RuntimeException("raw error"))
                .build();

        // 1. 业务校验异常分支 (IllegalArgumentException)
        IllegalArgumentException businessEx = new IllegalArgumentException("半径不能为负数 (当前传入: -5.0)");
        ToolErrorHandlerResult businessResult = executionHandler.handle(businessEx, context);
        assertNotNull(businessResult);
        String businessText = businessResult.text();
        System.out.println("业务校验异常处理结果:\n" + businessText);
        assertTrue(businessText.contains("【工具执行受阻】"), "必须包含业务受阻标记");
        assertTrue(businessText.contains("calculateCircleArea"), "必须指明失败工具名称");
        assertTrue(businessText.contains("调整入参后重试"), "必须具备引导大模型自愈的建议");

        // 2. 致命未预期异常分支 (如底层数据库连接或 NullPointer)
        RuntimeException fatalEx = new RuntimeException("Connection refused: /127.0.0.1:5432 at org.postgresql.Driver");
        ToolErrorHandlerResult fatalResult = executionHandler.handle(fatalEx, context);
        assertNotNull(fatalResult);
        String fatalText = fatalResult.text();
        System.out.println("致命异常脱敏处理结果:\n" + fatalText);
        assertTrue(fatalText.contains("【工具暂时不可用】"), "必须包含统一降级不可用标记");
        assertFalse(fatalText.contains("127.0.0.1"), "严禁泄漏底层网络 IP、端口或技术堆栈");
        assertFalse(fatalText.contains("org.postgresql"), "严禁向大模型泄漏内部代码包名与驱动信息");
    }

    @Test
    @DisplayName("测试用例 2：CustomToolArgumentsErrorHandler 参数解析异常拦截验证")
    void testArgumentsErrorHandler() {
        CustomToolArgumentsErrorHandler argumentsHandler = new CustomToolArgumentsErrorHandler();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-test-arg-fail")
                .name("multiply")
                .arguments("{\"a\": \"invalid_str\", \"b\": 10}")
                .build();
        ToolErrorContext context = ToolErrorContext.builder()
                .toolExecutionRequest(request)
                .memoryId("test-session")
                .rawError(new RuntimeException("raw error"))
                .build();

        IllegalArgumentException parseEx = new IllegalArgumentException("Cannot deserialize string to double");
        ToolErrorHandlerResult result = argumentsHandler.handle(parseEx, context);
        assertNotNull(result);
        String resultText = result.text();
        System.out.println("入参解析异常处理结果:\n" + resultText);
        assertTrue(resultText.contains("【入参解析失败】"), "必须包含入参解析失败标记");
        assertTrue(resultText.contains("multiply"), "必须指明工具名称");
        assertTrue(resultText.contains("严格对照该工具的参数类型说明重新构造调用请求"), "必须给出纠错引导");
    }

    @Test
    @DisplayName("测试用例 3：端到端 LLM 认知外环反思自愈（负数半径 -> 拦截受阻 -> 反思修正 -> 计算成功）")
    void testEndToEndToolReflectionAndSelfHealing() {
        String sessionId = "test-reflection-" + System.currentTimeMillis();
        try {
            System.out.println("\n========== [发送诱发工具业务异常并包含自愈引导的请求] ==========");
            // 故意要求计算负数半径圆面积，并在被拒绝时自行反思调整为正数 5.0 重新计算
            String query = "请计算半径为 -5.0 的圆的面积。注意：如果工具调用受阻并提示半径不能为负数，请主动反思并将其修正为绝对值 5.0 重新调用工具计算并输出结果。";

            String reply = null;
            // 容错重试以防御大模型平台限频 (Rate Limit / 429)
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    reply = assistant.chat(sessionId, query);
                    break;
                } catch (Exception e) {
                    if (attempt < 3 && e.getMessage() != null && e.getMessage().contains("429")) {
                        System.out.println("⚠️ 遇到 429 速率限制，等待 3 秒后重试 (第 " + attempt + " 次)...");
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {}
                    } else {
                        throw e;
                    }
                }
            }

            System.out.println("智能体自愈回复:\n" + reply);

            assertNotNull(reply);
            // 半径 5 的圆面积为 25 * PI ≈ 78.54
            assertTrue(reply.contains("78.5") || reply.contains("25π") || reply.contains("78.539") || reply.contains("78.54"),
                    "智能体在工具拦截后应成功自愈并计算出半径为 5 的圆面积（约 78.5）");

        } finally {
            assistant.evictChatMemory(sessionId);
            postgresChatMemoryStore.deleteMessages(sessionId);
        }
    }
}
