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

    private final MinioClient minioClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatModel chatModel;

    @Value("${minio.bucket-name:myagent-docs}")
    private String bucketName;

    public KnowledgeService(MinioClient minioClient,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            ChatModel chatModel) {
        this.minioClient = minioClient;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatModel = chatModel;
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

            // 6. 批量计算向量并整批存入 Qdrant
            log.info("🧠 [KnowledgeService] 正在计算 {} 个切片的语义向量并写入 Qdrant...", segments.size());
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);

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
     * 语义向量检索
     */
    public List<SearchResultItem> search(String query, int maxResults, double minScore) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        return result.matches().stream()
                .map(match -> new SearchResultItem(
                        match.embedded().text(),
                        match.score(),
                        match.embeddingId(),
                        match.embedded().metadata() != null ? match.embedded().metadata().toMap() : Map.of()
                ))
                .toList();
    }

    /**
     * 完整 RAG 检索增强自然语言问答
     */
    public Map<String, Object> ask(String query, int maxResults, double minScore) {
        long startTime = System.currentTimeMillis();

        // 1. 检索阶段 (Retrieval)
        List<SearchResultItem> matches = search(query, maxResults, minScore);

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
}
