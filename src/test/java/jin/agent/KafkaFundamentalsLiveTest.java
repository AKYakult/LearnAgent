package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.kafka.AgentTaskConsumer;
import jin.agent.kafka.AgentTaskProducer;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段六第四课：Kafka 核心原理与常用实操端到端测试 (KafkaFundamentalsLiveTest)
 *
 * <p>核心考核与验证场景：
 * 1. 基础消息收发与异步非阻塞回调 (CompletableFuture)
 * 2. 分区路由与单分区顺序性保障 (相同 PartitionKey 路由至同一 Partition，单分区严格保序)
 * 3. 消费端手动确认 (Manual Ack) 与位移推进
 * 4. 结合 Redis (SETNX) 的消费端幂等防重防御实战 (基于 X-Message-Id 解耦)
 */
@SpringBootTest
@Import(KafkaFundamentalsLiveTest.KafkaTopicTestConfig.class)
public class KafkaFundamentalsLiveTest {

    private static final String TOPIC_NAME = "agent-task-topic";

    @TestConfiguration
    static class KafkaTopicTestConfig {
        /**
         * 显式声明测试主题并创建 3 个分区，用于验证分区散列路由机制
         */
        @Bean
        public NewTopic testAgentTaskTopic() {
            return TopicBuilder.name(TOPIC_NAME)
                    .partitions(3)
                    .replicas(1)
                    .build();
        }
    }

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
    private AgentTaskProducer producer;

    @Autowired
    private AgentTaskConsumer consumer;

    @Autowired
    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        consumer.resetMetrics();
    }

    // =========================================================================
    // 1. 基础收发与异步非阻塞回调
    // 面试要点：
    // - 生产者的 send() 是非阻塞的，消息首先进入本地 RecordAccumulator 缓冲池批量攒批
    // - CompletableFuture 回调由 Kafka 的 Sender 专属 I/O 线程触发，不阻塞业务主线程
    // =========================================================================
    @Test
    @DisplayName("1. 基础消息收发：异步非阻塞发送 + 回调元数据检查")
    void testAsyncSendAndReceive() throws Exception {
        String taskId = "task-" + UUID.randomUUID();
        String messageKey = "user-1001";
        String payload = "{\"action\":\"ANALYZE_REPORT\",\"taskId\":\"" + taskId + "\"}";

        // 异步发送
        CompletableFuture<SendResult<String, String>> future =
                producer.sendTask(TOPIC_NAME, messageKey, taskId, payload);

        // 验证元数据凭证
        SendResult<String, String> result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result, "发送结果不应为空");
        assertEquals(TOPIC_NAME, result.getRecordMetadata().topic());
        assertTrue(result.getRecordMetadata().offset() >= 0, "位移 Offset 必须大于等于 0");

        // 等待消费端拉取并处理成功
        boolean consumed = waitForCondition(() -> consumer.getProcessedCount() >= 1, Duration.ofSeconds(10));
        assertTrue(consumed, "消费者应当成功接收并处理该消息");
        assertTrue(consumer.getReceivedPayloads().contains(payload), "接收到的内容应当与发送一致");
    }

    // =========================================================================
    // 2. 分区路由与单分区顺序性保证
    // 面试要点：
    // - Kafka 无法保证全局有序，仅保证【单分区内】严格有序
    // - 默认分区器对消息的 Key 计算 Murmur2 Hash，Key 相同的消息必定路由到同一 Partition
    // - 区分 PartitionKey（用于路由到相同分区）与 MessageId（用于消费端幂等防重）
    // =========================================================================
    @Test
    @DisplayName("2. 分区路由保序性：相同 Key 的有序事件必按原顺序被消费")
    void testPartitionRoutingAndOrdering() throws Exception {
        String orderEntityKey = "order-group-" + UUID.randomUUID();
        List<String> expectedSequence = new ArrayList<>();

        // 连续发送同一订单实体的 5 个生命周期事件（相同 PartitionKey，不同 MessageId）
        String[] actions = {"CREATED", "PAID", "STOCK_LOCKED", "SHIPPED", "COMPLETED"};
        for (int i = 0; i < actions.length; i++) {
            String eventMessageId = orderEntityKey + "-step-" + i;
            String payload = String.format("{\"seq\":%d,\"orderId\":\"%s\",\"event\":\"%s\"}", i, orderEntityKey, actions[i]);
            expectedSequence.add(payload);
            producer.sendTask(TOPIC_NAME, orderEntityKey, eventMessageId, payload).get(3, TimeUnit.SECONDS);
        }

        // 等待消费端全部 5 条消息消费完毕
        boolean allConsumed = waitForCondition(() -> consumer.getProcessedCount() >= 5, Duration.ofSeconds(10));
        assertTrue(allConsumed, "5 条顺序消息应全部被消费完成");

        // 截取收到的消息中属于本测试用例的列表
        List<String> actualReceived = consumer.getReceivedPayloads().stream()
                .filter(p -> p.contains(orderEntityKey))
                .toList();

        assertEquals(5, actualReceived.size(), "收到的本组消息数量必须为 5");
        // 关键断言：接收顺序与发送顺序必须 100% 保持一致！
        for (int i = 0; i < 5; i++) {
            assertEquals(expectedSequence.get(i), actualReceived.get(i), "第 " + i + " 条消息必须保序一致");
        }
    }

    // =========================================================================
    // 3. 消费端幂等防重机制 (结合 Redis SETNX)
    // 面试要点：
    // - 为什么 Kafka 不能 100% 避免重复？因为网络抖动、超时或 Rebalance 时可能触发消息重试
    // - 如何在消费端兜底？在执行业务前利用 Redis SETNX 原子占位，重复投递时直接跳过并 ACK
    // =========================================================================
    @Test
    @DisplayName("3. 消费端幂等防重：重复发送相同业务 Key 时，业务仅执行 1 次并直接跳过")
    void testRedisIdempotencyDeduplication() throws Exception {
        String uniqueTaskId = "idempotent-task-" + UUID.randomUUID();
        String payload = "{\"taskId\":\"" + uniqueTaskId + "\",\"operation\":\"RECHARGE_WALLET\"}";

        // 1. 第一次投递
        producer.sendTask(TOPIC_NAME, "user-555", uniqueTaskId, payload).get(3, TimeUnit.SECONDS);

        // 等待第一次被成功消费
        boolean firstProcessed = waitForCondition(() -> consumer.getProcessedCount() == 1, Duration.ofSeconds(10));
        assertTrue(firstProcessed, "首次投递必须被正常执行");

        // 检查 Redis 中是否存在防重 Key
        String redisKey = "idempotency:kafka:task:" + uniqueTaskId;
        RBucket<String> bucket = redissonClient.getBucket(redisKey);
        assertTrue(bucket.isExists(), "首次处理成功后 Redis 中应当存在幂等占位键");

        // 2. 模拟网络异常导致的重复投递 (相同 MessageId 与 Payload)
        producer.sendTask(TOPIC_NAME, "user-555", uniqueTaskId, payload).get(3, TimeUnit.SECONDS);

        // 等待重试消息被消费端拦截 (duplicateSkippedCount 增加)
        boolean duplicateSkipped = waitForCondition(() -> consumer.getDuplicateSkippedCount() >= 1, Duration.ofSeconds(10));
        assertTrue(duplicateSkipped, "消费端应当识别出重复消息并予以跳过");

        // 核心断言：业务逻辑处理总次数依然保持为 1，未被重复执行！
        assertEquals(1, consumer.getProcessedCount(), "业务处理逻辑有且仅应执行 1 次！");
    }

    // =========================================================================
    // 辅助轮询等待工具
    // =========================================================================
    private boolean waitForCondition(ConditionSupplier condition, Duration timeout) throws InterruptedException {
        long end = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @FunctionalInterface
    interface ConditionSupplier {
        boolean getAsBoolean();
    }
}
