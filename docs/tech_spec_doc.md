# 智能体研发团队核心技术规范 (v3.2)
团队研发代号为【Project-Nova-99】。

## 一、 办公地点与房间号
全体核心架构研发人员位于【创新智造大厦 C 座 21 层 2108-A 会议室】集中办公。

## 二、 向量底座规范
1. 统一使用本地 Ollama 驱动的 bge-m3 模型生成 1024 维向量；
2. 向量检索库统一采用 Qdrant，集合名称为 myagent_knowledge；
3. 稀疏索引采用 Qdrant 1.15+ 原生多语言分词（tokenizer: multilingual）与 BM25 modifier: idf 机制实现单引擎混合检索。
