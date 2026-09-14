package jin.agent.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置类
 * 负责构建 MinioClient Bean 并自动检查与创建知识库原始文档存储桶 (Bucket)
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Value("${minio.endpoint:http://localhost:9100}")
    private String endpoint;

    @Value("${minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${minio.secret-key:minioadmin_password}")
    private String secretKey;

    @Value("${minio.bucket-name:myagent-docs}")
    private String bucketName;

    @Bean
    public MinioClient minioClient() {
        // 1. 初始化 MinioClient 客户端
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        // 2. 自动探活并创建存储桶，避免初学者手动登录控制台建桶
        ensureBucketExists(client);

        return client;
    }

    /**
     * 检查并自动创建存储桶
     */
    private void ensureBucketExists(MinioClient client) {
        try {
            boolean found = client.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucketName)
                    .build());

            if (found) {
                log.info("✅ [MinIO] 存储桶 '{}' 已存在，无需重复创建", bucketName);
            } else {
                log.info("🚀 [MinIO] 存储桶 '{}' 不存在，正在自动创建...", bucketName);
                client.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucketName)
                        .build());
                log.info("✅ [MinIO] 成功创建存储桶 '{}'", bucketName);
            }
        } catch (Exception e) {
            log.warn("⚠️ [MinIO] 自动检查/创建存储桶失败（请确认 MinIO 容器已启动）: {}", e.getMessage());
        }
    }
}
