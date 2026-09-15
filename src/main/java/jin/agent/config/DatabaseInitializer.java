package jin.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 阶段五：数据库表结构自动化幂等初始化
 * 负责在应用启动时检查并自动创建会话快照表 (chat_memory_store) 与消息流水表 (chat_messages)
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("🚀 [DatabaseInitializer] 正在检查并初始化 PostgreSQL 会话记忆表结构...");

        // 1. 创建活跃会话快照表（供 LangChain4j ChatMemoryStore 存储序列化消息）
        String createSnapshotTableSql = """
                CREATE TABLE IF NOT EXISTS chat_memory_store (
                    conversation_id VARCHAR(64) PRIMARY KEY,
                    messages_json TEXT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                );
                """;
        jdbcTemplate.execute(createSnapshotTableSql);

        // 2. 创建可读消息流水表（供人类查阅与前端展示历史会话）
        String createMessagesTableSql = """
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id VARCHAR(64) NOT NULL,
                    sender VARCHAR(32) NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                );
                CREATE INDEX IF NOT EXISTS idx_chat_messages_conv_id ON chat_messages(conversation_id);
                """;
        jdbcTemplate.execute(createMessagesTableSql);

        log.info("✅ [DatabaseInitializer] PostgreSQL 会话记忆表结构初始化完成！");
    }
}
