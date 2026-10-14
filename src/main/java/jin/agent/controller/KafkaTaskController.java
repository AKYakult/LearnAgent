package jin.agent.controller;

import jin.agent.kafka.AgentTaskProducer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 阶段六：Kafka 异步任务调度控制器
 *
 * <p>提供 HTTP 触发入口，用于验证：
 * 1. 异步非阻塞任务派发（HTTP 202 Accepted 快速响应，下游 Worker 异步消费）
 * 2. 基于 partitionKey 的单分区有序散列
 * 3. 基于 messageId 的消费端 Redis 幂等防重拦截
 */
@RestController
@RequestMapping("/api/kafka")
public class KafkaTaskController {

    private final AgentTaskProducer producer;

    public KafkaTaskController(AgentTaskProducer producer) {
        this.producer = producer;
    }

    /**
     * 投递异步任务
     *
     * @param topic        目标 Topic (默认: agent-task-topic)
     * @param partitionKey 分区路由键 (默认: user-1001)
     * @param messageId    全局唯一消息 ID (缺省时自动生成 UUID)
     * @param payload      业务内容
     */
    @GetMapping("/send")
    public ResponseEntity<Map<String, Object>> sendTask(
            @RequestParam(defaultValue = "agent-task-topic") String topic,
            @RequestParam(defaultValue = "user-1001") String partitionKey,
            @RequestParam(required = false) String messageId,
            @RequestParam(defaultValue = "{\"action\":\"DOC_EMBEDDING\",\"docId\":\"doc_001\"}") String payload
    ) {
        String finalMsgId = (messageId != null && !messageId.isBlank()) ? messageId : "task-" + UUID.randomUUID();

        // 异步派发至 Kafka（立即返回，不阻塞 Web 请求）
        producer.sendTask(topic, partitionKey, finalMsgId, payload);

        return ResponseEntity.accepted().body(Map.of(
                "code", 202,
                "message", "任务已提交至 Kafka 异步处理流水线",
                "topic", topic,
                "partitionKey", partitionKey,
                "messageId", finalMsgId,
                "payload", payload
        ));
    }
}
