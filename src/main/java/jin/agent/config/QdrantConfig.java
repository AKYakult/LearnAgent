package jin.agent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
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
 * 负责自动初始化集合 (Collection) 并构建 EmbeddingStore 与 QdrantClient Bean
 * 支持默认 Dense 向量（1024 维）与 BM25 Sparse 向量（modifier: idf）共存
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
     * 构建并注册 Qdrant 官方原生 gRPC 客户端 Bean
     * 用于执行 Universal Query API（Dense + Sparse BM25 + RRF 融合）以及高级多向量操作
     */
    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient() {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(host, grpcPort, false).build()
        );
    }

    /**
     * 构建并注册 LangChain4j 的 QdrantEmbeddingStore Bean
     * 保持与 LangChain4j 标准抽象平滑兼容（操作默认 Dense 向量）
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 1. 检查并确保 Qdrant 中已创建对应的集合（同时支持 Dense 和 Sparse）
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
     * 自动配置：
     * 1. vectors: 1024 维余弦距离 Dense 向量空间（供 bge-m3 等稠密向量使用）
     * 2. sparse_vectors.bm25: modifier="idf" 稀疏向量空间（供 BM25 词频检索使用）
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
                String body = getResponse.body();
                // 检查已存在的集合是否包含 sparse_vectors / bm25
                if (body != null && body.contains("\"bm25\"")) {
                    log.info("✅ [Qdrant] 向量集合 '{}' 已存在且已支持 bm25 稀疏向量，无需重复创建", collectionName);
                    return;
                }

                // 如果存在但没有 bm25 稀疏向量，说明是旧版仅含稠密向量的集合
                // 重建集合以保证环境统一支持 Dense + Sparse 混合检索
                log.info("🔄 [Qdrant] 检测到集合 '{}' 尚未配置 bm25 稀疏向量，正在升级重建...", collectionName);
                HttpRequest deleteRequest = HttpRequest.newBuilder()
                        .uri(URI.create(collectionUrl))
                        .DELETE()
                        .build();
                client.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
            }

            // 2. 集合不存在（或已删除旧版），发起 PUT 请求创建（指定 1024 维 Dense + bm25 Sparse）
            log.info("🚀 [Qdrant] 正在自动创建多向量集合 '{}' (Dense: 1024 维 Cosine, Sparse: bm25 with IDF)...", collectionName);
            String createJson = """
                    {
                        "vectors": {
                            "size": %d,
                            "distance": "Cosine"
                        },
                        "sparse_vectors": {
                            "bm25": {
                                "modifier": "idf"
                            }
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
                log.info("✅ [Qdrant] 成功创建原生混合检索集合: '{}'", collectionName);
            } else {
                log.warn("⚠️ [Qdrant] 创建集合响应异常: HTTP {}, Body: {}", putResponse.statusCode(), putResponse.body());
            }

        } catch (Exception e) {
            log.warn("⚠️ [Qdrant] 自动检查/创建集合失败（可能容器未启动）: {}", e.getMessage());
        }
    }
}
