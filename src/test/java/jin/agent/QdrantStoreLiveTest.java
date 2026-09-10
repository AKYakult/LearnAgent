package jin.agent;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class QdrantStoreLiveTest {

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
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Test
    @DisplayName("测试 Ollama Embedding 生成与 Qdrant 向量入库及语义相似度检索")
    void testEmbeddingAndQdrantSemanticSearch() {
        System.out.println("\n========== [步骤 1：准备测试知识切片并向量化写入 Qdrant] ==========");
        List<String> rawTexts = List.of(
                "小明养了一只名叫咪咪的橘猫，它的性格非常温顺，每天最喜欢吃小鱼干和晒太阳。",
                "Spring Boot 是 Java 生态中最流行的微服务脚手架，具备自动配置和快速起步的强大特性。",
                "商汤日日新 SenseNova 是业界领先的大语言模型平台，提供高并发的文本生成与视觉多模态能力。"
        );

        for (String text : rawTexts) {
            TextSegment segment = TextSegment.from(text);
            Embedding embedding = embeddingModel.embed(segment).content();
            assertNotNull(embedding);

            // 基于内容哈希生成确定性 UUID，确保多次执行不会生成重复点
            String deterministicId = java.util.UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            embeddingStore.addAll(List.of(deterministicId), List.of(embedding), List.of(segment));
            System.out.println("成功以确定性 ID [" + deterministicId + "] 存入向量切片: " + text.substring(0, Math.min(20, text.length())) + "...");
        }

        System.out.println("\n========== [步骤 2：测试第 1 个语义检索（宠物问题）] ==========");
        String query1 = "小明平时养了什么宠物？它喜欢吃什么？";
        System.out.println("用户提问: " + query1);

        Embedding queryEmbedding1 = embeddingModel.embed(query1).content();
        EmbeddingSearchRequest request1 = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding1)
                .maxResults(2)
                .minScore(0.5)
                .build();

        EmbeddingSearchResult<TextSegment> result1 = embeddingStore.search(request1);
        List<EmbeddingMatch<TextSegment>> matches1 = result1.matches();

        assertFalse(matches1.isEmpty(), "应当至少检索到一条相似记录");
        EmbeddingMatch<TextSegment> topMatch1 = matches1.get(0);
        System.out.println("Top 1 命中切片内容: " + topMatch1.embedded().text());
        System.out.println("Top 1 相似度得分 (Cosine Score): " + topMatch1.score());

        assertTrue(topMatch1.embedded().text().contains("橘猫"), "Top 1 结果应当准确匹配小明的橘猫知识");
        assertTrue(topMatch1.score() > 0.6, "相似度得分应当显著高于 0.6");

        System.out.println("\n========== [步骤 3：测试第 2 个语义检索（Java 框架问题）] ==========");
        String query2 = "Java 后端开发中最常用的微服务框架是哪一个？";
        System.out.println("用户提问: " + query2);

        Embedding queryEmbedding2 = embeddingModel.embed(query2).content();
        EmbeddingSearchRequest request2 = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding2)
                .maxResults(2)
                .minScore(0.5)
                .build();

        EmbeddingSearchResult<TextSegment> result2 = embeddingStore.search(request2);
        List<EmbeddingMatch<TextSegment>> matches2 = result2.matches();

        assertFalse(matches2.isEmpty(), "应当至少检索到一条相似记录");
        EmbeddingMatch<TextSegment> topMatch2 = matches2.get(0);
        System.out.println("Top 1 命中切片内容: " + topMatch2.embedded().text());
        System.out.println("Top 1 相似度得分 (Cosine Score): " + topMatch2.score());

        assertTrue(topMatch2.embedded().text().contains("Spring Boot"), "Top 1 结果应当准确匹配 Spring Boot 知识");
        assertTrue(topMatch2.score() > 0.6, "相似度得分应当显著高于 0.6");
    }
}
