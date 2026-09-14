# 企业研发团队开发规范与入职指引

## 一、 团队技术选型
- 编程语言：Java 21 (JDK 21)
- 框架基石：Spring Boot 3.x
- 智能体框架：LangChain4j 1.x
- 存储与数据库：PostgreSQL 16 (带 pgvector 扩展)、Qdrant 向量数据库、MinIO 对象存储、Elasticsearch 8.x

## 二、 核心研发约定
1. 所有代码均禁止硬编码敏感 Key，统一使用根目录 `.env` 文件进行环境隔离。
2. 向量模型采用本地 Ollama 驱动的 `bge-m3` 多语言嵌入模型（1024 维度），本地执行零 API 费用。
3. 大语言模型采用商汤日日新 SenseNova，具备高并发对话推理与 Function Calling 工具调用能力。
4. 文档知识库统一由 MinIO 进行源文件存储与元数据指纹校验，切片以原子删除重建策略维护在 Qdrant 中。
