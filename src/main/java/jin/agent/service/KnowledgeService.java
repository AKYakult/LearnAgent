package jin.agent.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.PointStruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识库核心业务服务类
 * 实现企业级工业文档摄取流水线：
 * 1. 业务身份解耦（调用方显式声明 documentId）
 * 2. 第一层防重：MinIO 对象元数据哈希校验短路（文件未变时跳过）
 * 3. 第二层重建：整文档原子删除历史切片 + 递归重新切片写入
 * 4. 向量语义检索与 RAG 问答服务
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    // 允许 documentId 包含字母、数字、下划线、短横线与点
    private static final Pattern DOCUMENT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.]+$");

    // Qdrant 1.15+ 服务端原生多语言 BM25 配置选项：启用 multilingual 分词器，中日韩原生切词
    private static final Map<String, JsonWithInt.Value> BM25_OPTIONS = Map.of(
            "tokenizer", ValueFactory.value("multilingual"),
            "stemmer", ValueFactory.value(Map.of("type", ValueFactory.value("none"))),
            "stopwords", ValueFactory.value(Map.of())
    );

    private final MinioClient minioClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatModel chatModel;
    private final QdrantClient qdrantClient;

    @Value("${minio.bucket-name:myagent-docs}")
    private String bucketName;

    @Value("${qdrant.collection-name:myagent_knowledge}")
    private String collectionName;

    public KnowledgeService(MinioClient minioClient,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            ChatModel chatModel,
                            QdrantClient qdrantClient) {
        this.minioClient = minioClient;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatModel = chatModel;
        this.qdrantClient = qdrantClient;
    }

    /**
     * 文档摄取结果封装
     */
    public record IngestResult(
            String documentId,
            String status,         // "success" | "skipped"
            String message,
            String contentHash,
            int chunkCount,
            long costMs
    ) {}

    /**
     * 文档检索匹配项封装
     */
    public record SearchResultItem(
            String text,
            double score,
            String embeddingId,
            Map<String, Object> metadata
    ) {}

    /**
     * 工业级文档摄取流水线
     *
     * @param file       上传的文件流
     * @param documentId 调用方声明的业务唯一标识（如 "onboarding-guide"）
     * @return 摄取结果
     */
    public IngestResult ingestDocument(MultipartFile file, String documentId) {
        long startTime = System.currentTimeMillis();

        // 1. 校验入参
        validateDocumentId(documentId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件内容不能为空");
        }

        try {
            byte[] bytes = file.getBytes();
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : documentId;
            String newHash = computeSha256(bytes);
            String objectKey = "documents/" + documentId;

            // 2. 第一层：读取 MinIO 历史元数据，对比 content-hash 实现快速短路跳过
            String oldHash = getExistingContentHash(objectKey);

            if (newHash.equals(oldHash)) {
                log.info("⏭️ [KnowledgeService] 文档 '{}' (哈希: {}) 内容未发生变动，短路跳过向量重算", documentId, newHash);
                long cost = System.currentTimeMillis() - startTime;
                return new IngestResult(documentId, "skipped", "内容未发生变化，跳过重复摄取", newHash, 0, cost);
            }

            // 3. 上传原始文件至 MinIO，并写入 content-hash 和 original-filename 元数据
            log.info("📦 [KnowledgeService] 正在将文档 '{}' 存入 MinIO: {}/{}", documentId, bucketName, objectKey);
            Map<String, String> userMetadata = new HashMap<>();
            userMetadata.put("content-hash", newHash);
            userMetadata.put("original-filename", originalFilename);

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .userMetadata(userMetadata)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(file.getContentType() != null ? file.getContentType() : "text/plain; charset=utf-8")
                    .build());

            // 4. 第二层：在 Qdrant 中整文档原子清空旧切片（彻底杜绝孤儿切片与版本污染）
            log.info("🧹 [KnowledgeService] 正在清空 Qdrant 中文档 '{}' 的历史切片...", documentId);
            Filter documentIdFilter = MetadataFilterBuilder.metadataKey("document_id").isEqualTo(documentId);
            embeddingStore.removeAll(documentIdFilter);

            // 5. 递归分块与切片元数据组装
            String text = new String(bytes, StandardCharsets.UTF_8);
            Metadata docMetadata = Metadata.from(Map.of(
                    "document_id", documentId,
                    "content_hash", newHash,
                    "original_filename", originalFilename
            ));
            Document document = Document.from(text, docMetadata);

            // 采用 400 字符分块，50 字符重叠的递归切片策略
            List<TextSegment> rawSegments = DocumentSplitters.recursive(400, 50).split(document);

            // 为每一个切片丰富切片序号（chunk_index）
            List<TextSegment> segments = new ArrayList<>(rawSegments.size());
            for (int i = 0; i < rawSegments.size(); i++) {
                TextSegment raw = rawSegments.get(i);
                Metadata meta = raw.metadata().copy();
                meta.put("chunk_index", i);
                segments.add(TextSegment.from(raw.text(), meta));
            }

            // 6. 批量计算向量，整批存入 Qdrant (多向量：Dense + Qdrant 1.15+ 原生多语言 BM25)
            log.info("🧠 [KnowledgeService] 正在计算 {} 个切片的语义向量并由 Qdrant 服务端原生生成 BM25 稀疏特征...", segments.size());
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            List<PointStruct> points = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                Embedding denseEmb = embeddings.get(i);
                java.util.UUID pointId = java.util.UUID.randomUUID();

                // 构造 Qdrant 1.15+ 原生 Document，由服务端执行 multilingual 中文分词与稀疏特征提取
                Points.Document bm25Doc = Points.Document.newBuilder()
                        .setText(segment.text())
                        .setModel("qdrant/bm25")
                        .putAllOptions(BM25_OPTIONS)
                        .build();

                // 构造多向量："" 对应默认 Dense 向量（与 LangChain4j 标准命名对齐），"bm25" 对应服务端原生稀疏向量
                Points.Vectors vectors = VectorsFactory.namedVectors(Map.of(
                        "", VectorFactory.vector(denseEmb.vector()),
                        "bm25", VectorFactory.vector(bm25Doc)
                ));

                // 构造 Payload 负载元数据（包含 LangChain4j 标准 text_segment 键）
                Map<String, JsonWithInt.Value> payload = Map.of(
                        "text_segment", ValueFactory.value(segment.text()),
                        "document_id", ValueFactory.value(documentId),
                        "chunk_index", ValueFactory.value(i),
                        "content_hash", ValueFactory.value(newHash),
                        "original_filename", ValueFactory.value(originalFilename)
                );

                points.add(PointStruct.newBuilder()
                        .setId(PointIdFactory.id(pointId))
                        .setVectors(vectors)
                        .putAllPayload(payload)
                        .build());
            }

            qdrantClient.upsertAsync(collectionName, points).get();

            long cost = System.currentTimeMillis() - startTime;
            String msg = (oldHash == null) ? "文档首次摄取并向量化成功" : "文档更新成功，旧切片已整体替换重建";
            log.info("✅ [KnowledgeService] 文档 '{}' 摄取完成，共生成 {} 个切片，耗时 {} ms", documentId, segments.size(), cost);

            return new IngestResult(documentId, "success", msg, newHash, segments.size(), cost);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ [KnowledgeService] 摄取文档 '{}' 失败: {}", documentId, e.getMessage(), e);
            throw new RuntimeException("文档摄取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询 MinIO 中已有的所有 documentId 列表（供管理与调试）
     */
    public List<String> listDocumentIds() {
        List<String> docIds = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .prefix("documents/")
                    .recursive(true)
                    .build());

            for (Result<Item> r : results) {
                Item item = r.get();
                String objectName = item.objectName();
                if (objectName.startsWith("documents/")) {
                    String id = objectName.substring("documents/".length());
                    if (!id.isBlank()) {
                        docIds.add(id);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ [KnowledgeService] 列出已上传文档失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取文档列表失败: " + e.getMessage(), e);
        }
        return docIds;
    }

    /**
     * 语义向量检索 (纯 Dense 向量)
     */
    public List<SearchResultItem> search(String query, int maxResults, double minScore) {
        long startTime = System.currentTimeMillis();
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        Points.QueryPoints queryPoints = Points.QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .setUsing("")
                .setQuery(QueryFactory.nearest(queryEmbedding.vector()))
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setScoreThreshold((float) minScore)
                .setLimit(maxResults)
                .build();

        try {
            List<Points.ScoredPoint> scoredPoints = qdrantClient.queryAsync(queryPoints).get();

            List<SearchResultItem> items = new ArrayList<>();
            for (Points.ScoredPoint sp : scoredPoints) {
                Map<String, JsonWithInt.Value> payload = sp.getPayloadMap();
                String text = payload.containsKey("text_segment") ? payload.get("text_segment").getStringValue() : "";
                Map<String, Object> meta = new HashMap<>();
                for (Map.Entry<String, JsonWithInt.Value> entry : payload.entrySet()) {
                    if (!"text_segment".equals(entry.getKey())) {
                        meta.put(entry.getKey(), extractValue(entry.getValue()));
                    }
                }
                String pointId = sp.getId().hasUuid() ? sp.getId().getUuid() : String.valueOf(sp.getId().getNum());
                items.add(new SearchResultItem(text, sp.getScore(), pointId, meta));
            }

            long cost = System.currentTimeMillis() - startTime;
            log.info("🔍 [KnowledgeService] Dense 语义检索完成 (Query: '{}'), 召回 {} 条, 耗时 {} ms", query, items.size(), cost);
            return items;

        } catch (Exception e) {
            log.error("❌ [KnowledgeService] Qdrant Dense 检索失败: {}", e.getMessage(), e);
            throw new RuntimeException("语义向量检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 纯 BM25 稀疏特征检索 (Sparse BM25 Only)
     * 依靠 Qdrant 1.15+ 服务端原生 multilingual CJK 分词与 modifier: idf 动态打分
     * 专长：精确匹配专有名词、代号、编号、货号、人名，杜绝向量语义漂移
     *
     * @param query      检索关键词或自然语言问题
     * @param maxResults 返回的最大结果数
     * @return 匹配项列表（得分体系为 BM25 统计得分）
     */
    public List<SearchResultItem> bm25Search(String query, int maxResults) {
        long startTime = System.currentTimeMillis();

        Points.Document queryDoc = Points.Document.newBuilder()
                .setText(query)
                .setModel("qdrant/bm25")
                .putAllOptions(BM25_OPTIONS)
                .build();

        Points.QueryPoints queryPoints = Points.QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .setUsing("bm25")
                .setQuery(QueryFactory.nearest(queryDoc))
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setLimit(maxResults)
                .build();

        try {
            List<Points.ScoredPoint> scoredPoints = qdrantClient.queryAsync(queryPoints).get();

            List<SearchResultItem> items = new ArrayList<>();
            for (Points.ScoredPoint sp : scoredPoints) {
                Map<String, JsonWithInt.Value> payload = sp.getPayloadMap();
                String text = payload.containsKey("text_segment") ? payload.get("text_segment").getStringValue() : "";
                Map<String, Object> meta = new HashMap<>();
                for (Map.Entry<String, JsonWithInt.Value> entry : payload.entrySet()) {
                    if (!"text_segment".equals(entry.getKey())) {
                        meta.put(entry.getKey(), extractValue(entry.getValue()));
                    }
                }
                String pointId = sp.getId().hasUuid() ? sp.getId().getUuid() : String.valueOf(sp.getId().getNum());
                items.add(new SearchResultItem(text, sp.getScore(), pointId, meta));
            }

            long cost = System.currentTimeMillis() - startTime;
            log.info("🔤 [KnowledgeService] BM25 关键词检索完成 (Query: '{}'), 召回 {} 条, 耗时 {} ms", query, items.size(), cost);
            return items;

        } catch (Exception e) {
            log.error("❌ [KnowledgeService] Qdrant BM25 检索失败: {}", e.getMessage(), e);
            throw new RuntimeException("BM25 检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * Qdrant 原生单引擎混合检索（Dense 语义向量 + BM25 稀疏向量 + 服务端 RRF 倒数排名融合）
     * 单次 RPC 完成并发两路召回与融合重排
     *
     * @param query      检索关键词或自然语言问题
     * @param maxResults 返回的最大结果数
     * @return 融合排序后的匹配项列表
     */
    public List<SearchResultItem> hybridSearch(String query, int maxResults) {
        long startTime = System.currentTimeMillis();

        // 1. 计算 query 的 Dense 向量 (Ollama bge-m3)
        Embedding queryDenseEmbedding = embeddingModel.embed(query).content();

        // 2. 构造 Qdrant 1.15+ 原生 Document，由服务端自动执行 multilingual 中文分词与 BM25 检索
        Points.Document queryDoc = Points.Document.newBuilder()
                .setText(query)
                .setModel("qdrant/bm25")
                .putAllOptions(BM25_OPTIONS)
                .build();

        // 3. 构建两个并行的 PrefetchQuery (预检采样候选集，取 maxResults * 3 或至少 20 个)
        int prefetchLimit = Math.max(20, maxResults * 3);

        // 槽位 1：Dense 语义粗排（"" 匹配默认 Dense 空间）
        Points.PrefetchQuery densePrefetch = Points.PrefetchQuery.newBuilder()
                .setUsing("")
                .setQuery(QueryFactory.nearest(queryDenseEmbedding.vector()))
                .setLimit(prefetchLimit)
                .build();

        // 槽位 2：Sparse BM25 精准粗排（"bm25" 匹配稀疏空间，由 Qdrant 服务端原生 multilingual 切词）
        Points.PrefetchQuery sparsePrefetch = Points.PrefetchQuery.newBuilder()
                .setUsing("bm25")
                .setQuery(QueryFactory.nearest(queryDoc))
                .setLimit(prefetchLimit)
                .build();

        // 4. 发起 Universal Query API，指定 Fusion.RRF 倒数排名融合
        Points.QueryPoints queryPoints = Points.QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .addPrefetch(densePrefetch)
                .addPrefetch(sparsePrefetch)
                .setQuery(QueryFactory.fusion(Points.Fusion.RRF))
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setLimit(maxResults)
                .build();

        try {
            List<Points.ScoredPoint> scoredPoints = qdrantClient.queryAsync(queryPoints).get();

            List<SearchResultItem> items = new ArrayList<>();
            for (Points.ScoredPoint sp : scoredPoints) {
                Map<String, JsonWithInt.Value> payload = sp.getPayloadMap();
                String text = payload.containsKey("text_segment") ? payload.get("text_segment").getStringValue() : "";
                Map<String, Object> meta = new HashMap<>();
                for (Map.Entry<String, JsonWithInt.Value> entry : payload.entrySet()) {
                    if (!"text_segment".equals(entry.getKey())) {
                        meta.put(entry.getKey(), extractValue(entry.getValue()));
                    }
                }
                String pointId = sp.getId().hasUuid() ? sp.getId().getUuid() : String.valueOf(sp.getId().getNum());
                items.add(new SearchResultItem(text, sp.getScore(), pointId, meta));
            }

            long cost = System.currentTimeMillis() - startTime;
            log.info("🎯 [KnowledgeService] Qdrant 混合检索完成 (Query: '{}'), 召回 {} 条, 耗时 {} ms", query, items.size(), cost);
            return items;

        } catch (Exception e) {
            log.error("❌ [KnowledgeService] Qdrant 混合检索失败: {}", e.getMessage(), e);
            throw new RuntimeException("混合检索执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 完整 RAG 检索增强自然语言问答（优先使用混合检索召回）
     */
    public Map<String, Object> ask(String query, int maxResults, double minScore) {
        long startTime = System.currentTimeMillis();

        // 1. 优先使用 Qdrant 原生混合检索 (Dense + BM25 + RRF)
        List<SearchResultItem> matches = hybridSearch(query, maxResults);
        if (matches.isEmpty()) {
            // 降级使用纯稠密向量检索兜底
            matches = search(query, maxResults, minScore);
        }

        String context = matches.stream()
                .map(m -> "- " + m.text())
                .collect(Collectors.joining("\n"));

        // 2. 增强 (Augmentation) 与生成 (Generation)
        String prompt = """
                你是一个严谨客观的知识库智能助理。请严格根据以下提供的【参考资料】用通俗、流畅、自然的中文回答用户的【问题】。
                如果参考资料中没有提及相关答案，请如实告知“知识库中未找到相关答案”，切勿凭空捏造。

                【参考资料】：
                %s

                【用户问题】：
                %s
                """.formatted(context.isBlank() ? "无相关资料" : context, query);

        String answer = chatModel.chat(prompt);
        long cost = System.currentTimeMillis() - startTime;

        return Map.of(
                "query", query,
                "answer", answer,
                "matchesCount", matches.size(),
                "referencedDocs", matches.stream().map(SearchResultItem::text).toList(),
                "costMs", cost,
                "status", "success"
        );
    }

    /**
     * 从 MinIO 读取对象元数据中的 content-hash
     */
    private String getExistingContentHash(String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
            return stat.userMetadata() != null ? stat.userMetadata().get("content-hash") : null;
        } catch (ErrorResponseException e) {
            // 404 / NoSuchKey 表示首次上传
            if ("NoSuchKey".equalsIgnoreCase(e.errorResponse().code()) || e.response().code() == 404) {
                return null;
            }
            log.warn("⚠️ [KnowledgeService] 读取 MinIO 对象元数据失败: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("⚠️ [KnowledgeService] 读取 MinIO 对象元数据异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 校验 documentId 规范
     */
    private void validateDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空，必须显式声明业务标识");
        }
        if (!DOCUMENT_ID_PATTERN.matcher(documentId).matches()) {
            throw new IllegalArgumentException("documentId 格式非法，仅允许包含英文字母、数字、下划线、短横线或点: " + documentId);
        }
    }

    /**
     * 计算字节流的 SHA-256 哈希值
     */
    private String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法未找到", e);
        }
    }
    /**
     * 解析 Qdrant Protobuf Payload 中的 Value 对象为 Java 基础类型
     */
    private Object extractValue(JsonWithInt.Value v) {
        if (v == null) {
            return null;
        }
        return switch (v.getKindCase()) {
            case STRING_VALUE -> v.getStringValue();
            case INTEGER_VALUE -> v.getIntegerValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case BOOL_VALUE -> v.getBoolValue();
            default -> v.toString();
        };
    }
}

