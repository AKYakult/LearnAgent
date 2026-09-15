-- ==============================================================================
-- MyAgent 数据库初始化脚本
-- 在容器首次启动时由 PostgreSQL 自动执行 (/docker-entrypoint-initdb.d/init.sql)
-- ==============================================================================

-- 1. 开启 pgvector 扩展插件（用于支持向量存储与欧式距离、余弦相似度检索）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 活跃会话记忆快照表（供阶段 5 LangChain4j ChatMemoryStore 存储多态消息序列化 JSON）
CREATE TABLE IF NOT EXISTS chat_memory_store (
    conversation_id VARCHAR(64) PRIMARY KEY,
    messages_json TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. 人类可读的会话消息审计流水表（供阶段 5 前端与管理端查询历史记录）
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    sender VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_conv_id ON chat_messages(conversation_id);
