package jin.agent.kafka;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体任务 Kafka 消费者 (Consumer)
 *
 * <p>核心知识点解析：
 * 1. 手动确认机制 (Manual Immediate Ack)：
 *    生产环境中必须关闭自动提交 (enable-auto-commit = false)。
 *    通过方法入参注入 Acknowledgment，在业务逻辑完全成功后再显式调用 ack.acknowledge() 提交 Offset。
 *
 * 2. 消费端幂等防重 (结合 Redis SETNX 与 X-Message-Id)：
 *    消息的防重判断优先基于全局唯一的 X-Message-Id，而非直接绑定 PartitionKey。
 *    这样即便同一订单或实体的连续事件通过相同的 PartitionKey 保序路由，也不会被防重机制误杀。
 */
@Service
public class AgentTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskConsumer.class);

    private final RedissonClient redissonClient;

    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger duplicateSkippedCount = new AtomicInteger(0);
    private final List<String> receivedPayloads = new CopyOnWriteArrayList<>();

    public AgentTaskConsumer(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 监听任务主题并安全消费
     *
     * @param messagePayload 消息体
     * @param messageId      消息头中的全局唯一消息 ID
     * @param partitionKey   Kafka 分区键
     * @param partition      来自哪个分区
     * @param offset         位移编号
     * @param ack            手动提交句柄
     */
    @KafkaListener(topics = "agent-task-topic", groupId = "agent-task-worker-group")
    public void onMessage(
            String messagePayload,
            @Header(name = "X-Message-Id", required = false) String messageId,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String partitionKey,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack
    ) {
        log.info("【Kafka 消费端接收】partition={}, offset={}, partitionKey={}, messageId={}, payload={}",
                partition, offset, partitionKey, messageId, messagePayload);

        // 确定幂等防重键：优先采用消息全局唯一流水号 X-Message-Id
        String deduplicateKey;
        if (messageId != null && !messageId.isBlank()) {
            deduplicateKey = messageId;
        } else if (partitionKey != null && !partitionKey.isBlank()) {
            deduplicateKey = partitionKey;
        } else {
            deduplicateKey = "hash:" + messagePayload.hashCode();
        }

        String redisKey = "idempotency:kafka:task:" + deduplicateKey;
        RBucket<String> bucket = redissonClient.getBucket(redisKey);

        // 步骤 1：利用 Redis SETNX 原子占位（有效期 1 小时，防止长期占用内存）
        boolean isFirstExecution = bucket.setIfAbsent("PROCESSED", Duration.ofHours(1));

        if (!isFirstExecution) {
            log.warn("【检测到重复消息】dedupKey={} 已在 Redis 中存在记录，判定为重复投递，跳过业务并直接提交 Offset！", deduplicateKey);
            duplicateSkippedCount.incrementAndGet();
            // 重点：必须提交 Offset，否则当前 Consumer 会反复拉取该重复消息阻塞消费队列
            ack.acknowledge();
            return;
        }

        try {
            // 步骤 2：执行实际业务逻辑
            executeBusinessTask(messagePayload);

            // 记录测试观察指标
            receivedPayloads.add(messagePayload);
            processedCount.incrementAndGet();

            // 步骤 3：业务逻辑无异常，手动确认提交 Offset
            ack.acknowledge();
            log.info("【业务处理完成，位移已手动提交】dedupKey={}, offset={}", deduplicateKey, offset);

        } catch (Exception ex) {
            log.error("【业务处理发生异常】dedupKey={}, error={}", deduplicateKey, ex.getMessage(), ex);
            // 关键容错：若业务失败，应释放 Redis 占位，允许随后的重试逻辑再次执行
            bucket.delete();
            // 抛出异常，不执行 ack.acknowledge()
            throw ex;
        }
    }

    private void executeBusinessTask(String payload) {
        log.info("正在执行业务处理: payload={}", payload);
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getDuplicateSkippedCount() {
        return duplicateSkippedCount.get();
    }

    public List<String> getReceivedPayloads() {
        return receivedPayloads;
    }

    public void resetMetrics() {
        processedCount.set(0);
        duplicateSkippedCount.set(0);
        receivedPayloads.clear();
    }
}
