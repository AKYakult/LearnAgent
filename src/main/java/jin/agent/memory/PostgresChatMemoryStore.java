package jin.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * 阶段五：基于 PostgreSQL 的会话历史持久化存储实现 (PostgresChatMemoryStore)
 *
 * 【底层架构原理】：
 * 1. 本实现对接 LangChain4j 的 ChatMemoryStore 标准接口；
 * 2. 状态存储本质上属于 K-V 快照模型，通过 ChatMessageSerializer.messagesToJson
 *    将滑动窗口内的多态消息（UserMessage、AiMessage、ToolExecutionRequest、ToolExecutionResultMessage 等）
 *    完整保真序列化为 JSON 字符串；
 * 3. 利用 PostgreSQL 原生 Upsert 特性 (ON CONFLICT DO UPDATE)，单条 SQL 即可完成原子更新，
 *    避免复杂的分布式事务，极大提升读写吞吐并杜绝 Function Calling 上下文丢失。
 */
@Repository
public class PostgresChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresChatMemoryStore.class);

    private final JdbcTemplate jdbcTemplate;

    public PostgresChatMemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        log.debug("📥 [ChatMemoryStore] 从 PostgreSQL 读取会话记忆: conversationId={}", conversationId);

        String sql = "SELECT messages_json FROM chat_memory_store WHERE conversation_id = ?";
        List<String> list = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("messages_json"),
                conversationId
        );

        if (list.isEmpty() || list.get(0) == null || list.get(0).isBlank()) {
            log.debug("ℹ️ [ChatMemoryStore] 会话 {} 暂无历史记忆，返回空列表", conversationId);
            return Collections.emptyList();
        }

        // 利用 LangChain4j 官方反序列化器还原为多态消息列表
        List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(list.get(0));
        log.debug("✅ [ChatMemoryStore] 成功恢复会话 {} 历史消息 {} 条", conversationId, messages.size());
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String conversationId = memoryId.toString();
        if (messages == null || messages.isEmpty()) {
            deleteMessages(memoryId);
            return;
        }

        // 1. 将包含工具调用细节的多态消息列表序列化为完整保真 JSON
        String json = ChatMessageSerializer.messagesToJson(messages);

        log.debug("💾 [ChatMemoryStore] 持久化会话记忆至 PostgreSQL: conversationId={}, messageCount={}",
                conversationId, messages.size());

        // 2. 利用 PostgreSQL 原生 Upsert 语法进行幂等写入
        String upsertSql = """
                INSERT INTO chat_memory_store (conversation_id, messages_json, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (conversation_id) DO UPDATE SET
                    messages_json = EXCLUDED.messages_json,
                    updated_at = CURRENT_TIMESTAMP
                """;

        jdbcTemplate.update(upsertSql, conversationId, json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        log.info("🗑️ [ChatMemoryStore] 清空 PostgreSQL 会话记忆: conversationId={}", conversationId);

        String sql = "DELETE FROM chat_memory_store WHERE conversation_id = ?";
        jdbcTemplate.update(sql, conversationId);
    }
}
