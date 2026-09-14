package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.declarative.Assistant;
import jin.agent.service.KnowledgeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KnowledgeHybridSearchLiveTest {

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
    private KnowledgeService knowledgeService;

    @Autowired
    private Assistant assistant;

    @Autowired
    private io.minio.MinioClient minioClient;

    private static final String TEST_DOC_ID = "company-tech-spec";

    @Test
    @Order(1)
    @DisplayName("测试场景 1：上传包含专有名词、编号与技术规范的文档，验证多向量（Dense + BM25）摄取流水线")
    void testIngestDocumentWithMultiVectors() {
        System.out.println("\n========== [场景 1：摄取多向量技术文档] ==========");
        // 清理历史同名文档，确保测试必定执行完整摄取而非防重跳过
        try {
            minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                    .bucket("myagent-docs")
                    .object("documents/" + TEST_DOC_ID)
                    .build());
        } catch (Exception ignored) {}

        String content = """
                # 智能体研发团队核心技术规范 (v3.2)
                团队研发代号为【Project-Nova-99】。
                【办公地点与房间号】：
                全体核心架构研发人员位于【创新智造大厦 C 座 21 层 2108-A 会议室】集中办公。
                【向量底座规范】：
                统一使用本地 Ollama 驱动的 bge-m3 模型生成 1024 维向量；
                向量检索库统一采用 Qdrant，集合名称为 myagent_knowledge；
                稀疏索引采用 BM25 配合 Qdrant modifier: idf 机制实现原生混合检索。
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "tech_spec_v3.md",
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );

        KnowledgeService.IngestResult result = knowledgeService.ingestDocument(file, TEST_DOC_ID);
        System.out.println("多向量上传响应: " + result);

        assertEquals(TEST_DOC_ID, result.documentId());
        assertEquals("success", result.status());
        assertTrue(result.chunkCount() > 0);
    }

    @Test
    @Order(2)
    @DisplayName("测试场景 2：验证 Qdrant 原生 Universal Query API 混合检索（专有名词与精确编号精确召回）")
    void testHybridSearchPrecision() {
        System.out.println("\n========== [场景 2：混合检索精确专有名词] ==========");
        // 测试精确编号 "2108-A" 和技术代号 "Project-Nova-99"
        List<KnowledgeService.SearchResultItem> matches = knowledgeService.hybridSearch("研发代号 Project-Nova-99 的团队房间号是多少？", 2);

        assertFalse(matches.isEmpty(), "混合检索应当成功召回文档");
        System.out.println("混合检索最佳命中: " + matches.get(0).text());
        System.out.println("匹配得分 (RRF): " + matches.get(0).score());

        assertTrue(matches.get(0).text().contains("2108-A"), "应当精准命中房间号 2108-A");
        assertTrue(matches.get(0).text().contains("Project-Nova-99"), "应当精准命中代号 Project-Nova-99");
    }

    @Test
    @Order(3)
    @DisplayName("测试场景 3：验证声明式 Agent（Assistant）自主调用 searchKnowledge 与数学工具链式推理")
    void testAgentAutonomousKnowledgeRetrievalAndCalculation() {
        System.out.println("\n========== [场景 3：Agent 智能体自主查库并执行复合计算] ==========");
        // 复合问题：先需要从知识库查出研发团队所在的楼层（21层），然后计算如果每层楼层高 3.5 米，21 层楼总高度是多少米
        String userQuery = "请查阅团队技术规范文档，告诉我我们研发团队在几层办公？如果每层楼高 3.5 米，第 21 层离地面大约有多少米？";
        System.out.println("用户提问: " + userQuery);

        String reply = assistant.chat(userQuery);
        System.out.println("智能体最终回复:\n" + reply);

        assertNotNull(reply);
        assertTrue(reply.contains("21") || reply.contains("二十一"), "回答中应包含查出的 21 层");
        assertTrue(reply.contains("73.5") || reply.contains("70"), "回答中应包含通过数学工具计算出的高度 (73.5 或 70 米)");
    }
}
