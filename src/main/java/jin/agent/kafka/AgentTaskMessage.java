package jin.agent.kafka;

/**
 * 智能体异步任务消息载荷 (Message Payload)
 *
 * <p>核心设计要点：
 * 1. taskId: 全局唯一的任务流水号，消费端利用此字段在 Redis 中进行防重幂等校验 (SETNX)。
 * 2. entityKey: 业务实体分组 Key (例如 userId 或 conversationId)，作为 Kafka Message Key 发送，
 *    Kafka 据此进行哈希路由，确保同一实体的全部事件被路由到同一 Partition，实现单实体严格有序消费。
 * 3. payload: 实际的业务数据或指令内容。
 *
 * @param taskId     全局唯一任务 ID
 * @param entityKey  实体路由 Key (分区有序依据)
 * @param payload    业务负载内容
 */
public record AgentTaskMessage(
        String taskId,
        String entityKey,
        String payload
) {}
