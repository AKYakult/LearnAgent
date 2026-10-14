package jin.agent.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * 智能体任务 Kafka 生产者 (Producer)
 *
 * <p>核心知识点解析：
 * 1. 异步非阻塞发送：
 *    kafkaTemplate.send() 不会阻塞调用线程，而是立即返回 CompletableFuture。
 *    底层由 KafkaProducer 的 RecordAccumulator 收集器批量打包（Batching），由专属网络 I/O 线程异步刷入 Broker。
 *
 * 2. 分区键 (Partition Key) vs 消息唯一 ID (Message ID)：
 *    - Partition Key（如 orderId / userId）：用于 Murmur2 哈希路由，决定落入哪一个 Partition，保证单实体事件顺序。
 *    - Message ID（如 X-Message-Id 请求头 / taskId）：每条消息唯一的流水号，供消费端在 Redis 中做 SETNX 幂等防重。
 *    两者职能清晰解耦，避免因保序使用同一 Key 而导致后续事件被错误当成重复消息拦截。
 *
 * 3. 消息防丢机制（生产端）：
 *    配合 acks=all 与 retries=3，只有当所有 ISR 副本写入成功后才回调。
 */
@Service
public class AgentTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public AgentTaskProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 发送带唯一 MessageId 与 PartitionKey 的异步任务消息
     *
     * @param topic        目标主题
     * @param partitionKey 分区路由键 (相同 Key 必定进入同一 Partition 保证有序)
     * @param messageId    每条消息唯一的流水号 (用于消费端 Redis 幂等防重)
     * @param jsonPayload  消息负载 JSON 文本
     * @return CompletableFuture 结果凭证
     */
    public CompletableFuture<SendResult<String, String>> sendTask(
            String topic,
            String partitionKey,
            String messageId,
            String jsonPayload
    ) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, partitionKey, jsonPayload);

        // 将消息唯一流水号附加到 Kafka 记录头 (Headers) 中
        if (messageId != null && !messageId.isBlank()) {
            record.headers().add("X-Message-Id", messageId.getBytes(StandardCharsets.UTF_8));
        }

        log.info("【Kafka 生产端准备发送】topic={}, partitionKey={}, messageId={}, payload={}",
                topic, partitionKey, messageId, jsonPayload);

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("【Kafka 生产端发送成功】topic={}, partition={}, offset={}, partitionKey={}, messageId={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        partitionKey,
                        messageId);
            } else {
                log.error("【Kafka 生产端发送异常】topic={}, partitionKey={}, messageId={}, error={}",
                        topic, partitionKey, messageId, ex.getMessage(), ex);
            }
        });

        return future;
    }

    /**
     * 兼容性简易重载
     */
    public CompletableFuture<SendResult<String, String>> sendTask(String topic, String partitionKey, String jsonPayload) {
        return sendTask(topic, partitionKey, null, jsonPayload);
    }
}
