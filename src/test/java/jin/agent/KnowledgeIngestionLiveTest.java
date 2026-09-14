package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KnowledgeIngestionLiveTest {

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

    private static final String TEST_DOC_ID = "onboarding-guide";

    @Test
    @Order(1)
    @DisplayName("测试场景 1：首次上传文档，验证 MinIO 存储、递归分块与 Qdrant 向量入库")
    void testFirstTimeIngestion() {
        System.out.println("\n========== [场景 1：首次上传入库] ==========");
        String contentV1 = """
                # 新人入职引导指南 (v1.0)
                欢迎加入 AI 智能体实验室团队！
                我们核心技术栈为 Java 21 与 Spring Boot，向量检索底座基于 Qdrant 和 Ollama bge-m3。
                【办公地点】：当前办公地址为科技园 A 座 5 层 502 房间。
                如有领用电脑或门禁权限问题，请联系行政部小美办理。
                """;

        MockMultipartFile fileV1 = new MockMultipartFile(
                "file",
                "onboarding_guide_v1.md",
                "text/markdown",
                contentV1.getBytes(StandardCharsets.UTF_8)
        );

        KnowledgeService.IngestResult result = knowledgeService.ingestDocument(fileV1, TEST_DOC_ID);
        System.out.println("首次上传响应: " + result);

        assertEquals(TEST_DOC_ID, result.documentId());
        assertEquals("success", result.status());
        assertTrue(result.chunkCount() > 0, "切片数量应大于 0");
        assertNotNull(result.contentHash());

        // 验证 MinIO 列表中包含该 documentId
        List<String> docList = knowledgeService.listDocumentIds();
        System.out.println("MinIO 当前文档列表: " + docList);
        assertTrue(docList.contains(TEST_DOC_ID), "MinIO 列表中必须包含刚刚上传的 documentId");

        // 语义检索验证
        List<KnowledgeService.SearchResultItem> matches = knowledgeService.search("新员工办公地点在几层房间？", 2, 0.5);
        assertFalse(matches.isEmpty());
        System.out.println("语义检索命中: " + matches.get(0).text());
        assertTrue(matches.get(0).text().contains("科技园 A 座 5 层"), "首次检索应当准确命中 v1 版本的办公地址");
    }

    @Test
    @Order(2)
    @DisplayName("测试场景 2：相同内容再次上传，验证第一层 MinIO 哈希校验短路（零向量重算）")
    void testDuplicateIngestionShouldSkip() {
        System.out.println("\n========== [场景 2：相同内容重复上传（防重短路）] ==========");
        String contentV1 = """
                # 新人入职引导指南 (v1.0)
                欢迎加入 AI 智能体实验室团队！
                我们核心技术栈为 Java 21 与 Spring Boot，向量检索底座基于 Qdrant 和 Ollama bge-m3。
                【办公地点】：当前办公地址为科技园 A 座 5 层 502 房间。
                如有领用电脑或门禁权限问题，请联系行政部小美办理。
                """;

        // 即便换了一个文件名（如 file_renamed.md），只要 documentId 与内容未变，依然短路跳过
        MockMultipartFile duplicateFile = new MockMultipartFile(
                "file",
                "file_renamed.md",
                "text/markdown",
                contentV1.getBytes(StandardCharsets.UTF_8)
        );

        KnowledgeService.IngestResult result = knowledgeService.ingestDocument(duplicateFile, TEST_DOC_ID);
        System.out.println("重复上传响应: " + result);

        assertEquals("skipped", result.status(), "相同内容重复上传必须被识别为 skipped");
        assertEquals(0, result.chunkCount(), "短路跳过时不应该重新切片与向量化");
        assertTrue(result.message().contains("跳过重复摄取"));
    }

    @Test
    @Order(3)
    @DisplayName("测试场景 3：文档内容变更，验证第二层整文档原子清空旧切片并重建新切片（杜绝版本污染）")
    void testUpdatedIngestionAtomicRebuild() {
        System.out.println("\n========== [场景 3：文档版本变更，整文档原子删除重建] ==========");
        String contentV2 = """
                # 新人入职引导指南 (v2.0 搬迁修订版)
                欢迎加入 AI 智能体实验室团队！
                【重要办公地址变更通知】：
                由于团队规模扩大，全体成员已搬迁至【创新大厦 B 座 18 层 1808 室】办公。
                原科技园 A 座办公室已停止使用，请新入职员工直接前往创新大厦办理入职。
                """;

        MockMultipartFile fileV2 = new MockMultipartFile(
                "file",
                "onboarding_v2_final.md",
                "text/markdown",
                contentV2.getBytes(StandardCharsets.UTF_8)
        );

        KnowledgeService.IngestResult result = knowledgeService.ingestDocument(fileV2, TEST_DOC_ID);
        System.out.println("更新上传响应: " + result);

        assertEquals("success", result.status());
        assertTrue(result.chunkCount() > 0);

        // 语义检索验证：旧地址切片应当已被清空，最新检索必须直接命中 18 层新地址
        List<KnowledgeService.SearchResultItem> matches = knowledgeService.search("新员工办公地点搬到了哪里？", 2, 0.5);
        assertFalse(matches.isEmpty());
        System.out.println("更新后检索命中: " + matches.get(0).text());
        assertTrue(matches.get(0).text().contains("创新大厦 B 座 18 层"), "更新后应当召回最新的创新大厦办公地址");

        // 进一步验证完整 RAG 问答：结合商汤大模型自然语言回答
        Map<String, Object> ragAnswer = knowledgeService.ask("请问新入职员工的办公地点目前在哪个大厦几层？", 2, 0.5);
        System.out.println("商汤大模型基于最新版本回答:\n" + ragAnswer.get("answer"));
        assertNotNull(ragAnswer.get("answer"));
        assertTrue(ragAnswer.get("answer").toString().contains("创新大厦"), "大模型回答应准确依据最新切片说明办公地址在创新大厦");
    }
}
