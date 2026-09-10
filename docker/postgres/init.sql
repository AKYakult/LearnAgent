-- ==============================================================================
-- MyAgent 数据库初始化脚本
-- 在容器首次启动时由 PostgreSQL 自动执行 (/docker-entrypoint-initdb.d/init.sql)
-- ==============================================================================

-- 1. 开启 pgvector 扩展插件（用于支持向量存储与欧式距离、余弦相似度检索）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 预备会话记忆持久化表（供阶段 5 会话历史存盘使用）
CREATE TABLE IF NOT EXISTS chat_memory (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_memory_conv_id ON chat_memory(conversation_id);

-- 3. 预备文档知识片段向量表（供阶段 4 关系+向量混合存储演示备选）
CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_name VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024), -- 预留 1024 维通用文本向量（如 bge-large 或 text-embedding-3）
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
