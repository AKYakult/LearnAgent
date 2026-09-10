package jin.agent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Qdrant 向量数据库配置类
 * 负责自动初始化集合 (Collection) 并构建 EmbeddingStore Bean
 */
@Configuration
public class QdrantConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantConfig.class);

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int grpcPort;

    @Value("${qdrant.collection-name:myagent_knowledge}")
    private String collectionName;

    // Ollama bge-m3 模型的标准输出向量维度
    private static final int VECTOR_DIMENSION = 1024;

    /**
     * 构建并注册 QdrantEmbeddingStore Bean
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 1. 检查并确保 Qdrant 中已创建对应的集合
        ensureCollectionExists();

        // 2. 构造 LangChain4j 的 QdrantEmbeddingStore
        return QdrantEmbeddingStore.builder()
                .host(host)
                .port(grpcPort)
                .collectionName(collectionName)
                .build();
    }

    /**
     * 通过 Qdrant REST API (端口 6333) 检查并自动创建集合
     * 避免初学开发者手动去控制台建表的繁琐操作
     */
    private void ensureCollectionExists() {
        int restPort = 6333;
        String collectionUrl = "http://" + host + ":" + restPort + "/collections/" + collectionName;

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {

            // 1. 发起 GET 请求检查集合是否存在
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(collectionUrl))
                    .GET()
                    .build();

            HttpResponse<String> getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

            if (getResponse.statusCode() == 200) {
                log.info("✅ [Qdrant] 向量集合 '{}' 已存在，无需重复创建", collectionName);
                return;
            }

            // 2. 集合不存在，发起 PUT 请求创建（指定 1024 维，余弦距离 Cosine）
            log.info("🚀 [Qdrant] 正在自动创建向量集合 '{}' (维度: {}, 距离度量: Cosine)...", collectionName, VECTOR_DIMENSION);
            String createJson = """
                    {
                        "vectors": {
                            "size": %d,
                            "distance": "Cosine"
                        }
                    }
                    """.formatted(VECTOR_DIMENSION);

            HttpRequest putRequest = HttpRequest.newBuilder()
                    .uri(URI.create(collectionUrl))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(createJson))
                    .build();

            HttpResponse<String> putResponse = client.send(putRequest, HttpResponse.BodyHandlers.ofString());
            if (putResponse.statusCode() == 200) {
                log.info("✅ [Qdrant] 成功创建向量集合: '{}'", collectionName);
            } else {
                log.warn("⚠️ [Qdrant] 创建集合响应异常: HTTP {}, Body: {}", putResponse.statusCode(), putResponse.body());
            }

        } catch (Exception e) {
            log.warn("⚠️ [Qdrant] 自动检查/创建集合失败（可能容器未启动）: {}", e.getMessage());
        }
    }
}
