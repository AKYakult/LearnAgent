# 阶段四：RAG 知识库与混合检索学习笔记与实战指南

> 本文档用于记录阶段四（RAG 知识库与混合检索）的核心概念、踩坑与复盘、已实现代码架构以及后续工业级演进方案。

---

## 一、 核心概念解析

### 1. 为什么大模型需要 RAG？
* **大模型的局限**：大模型的知识截至于其训练时刻，无法掌握企业内部私有数据，且容易产生一本正经胡说八道的“幻觉”。
* **RAG（Retrieval-Augmented Generation，检索增强生成）**：给大模型配备外部“海马体”。在回答问题前，先去知识库里搜索相关的真实材料，以“开卷考试”的方式交给大模型阅读，再生成可靠回答。

### 2. LLM 与 Embedding Model 的本质区别

| 维度 | 大语言模型（LLM，如商汤 SenseNova） | 向量模型（Embedding，如 Ollama bge-m3） |
| :--- | :--- | :--- |
| **主要功能** | 文本生成、逻辑推理、工具调度、自然语言对话 | **不说话**，将文本转换为高维浮点数坐标（如 1024 维数组） |
| **应用场景** | 负责最终理解上下文并用人话回答问题 | 负责在知识入库和提问时，建立语义相似度计算的数学底座 |
| **计算产物** | 字符串（String） | 浮点数组（`float[]`） |

### 3. 从纯检索 (R) 到完整 RAG (R + A + G)

* **R (Retrieval 检索)**：用户提问 ➔ Ollama 计算向量 ➔ Qdrant 计算余弦距离 ➔ 找到 Top-K 原始参考文本。
* **A (Augmentation 增强)**：把检索到的文本组装成 Prompt 中的 Context（参考背景）。
* **G (Generation 生成)**：将 Prompt 喂给商汤 ChatModel，输出条理清晰的自然语言回答。

---

## 二、 核心实战踩坑与架构演进

### 1. Qdrant 重复数据问题的本质与破解
* **现象**：多次运行测试或点击录入相同文本时，Qdrant 控制台中同一句话出现了多条记录。
* **根因**：Qdrant 的唯一键是 Point ID，LangChain4j 默认通过 `UUID.randomUUID()` 随机生成 ID。ID 不同时，Qdrant 视为新数据。
* **解决方案**：
  * **工业级文档版本管理与去重演进（里程碑 4.2 规划，详见 [`DOCUMENT_ID_DESIGN.md`](file:///D:/vibe/LearnAgent/docs/DOCUMENT_ID_DESIGN.md)）**：
    1. **文档身份解耦**：采用方案 B，由调用方显式传入业务 Key `documentId`（如 `onboarding-guide`），与文件名解耦，解决重命名导致的版本断裂问题。
    2. **第一层（MinIO 元数据哈希防重）**：Object Key 统一为 `documents/{documentId}`，元数据记录 `content-hash`。上传时通过 `statObject` 比对哈希，未变动直接短路跳过，实现零向量计算开销。
    3. **第二层（整文档原子删除重建）**：若内容发生变动，直接执行 `embeddingStore.removeAll(metadataKey("document_id").isEqualTo(documentId))` 清空该文档旧切片，再重新分块批量写入。无需维护复杂的切片槽位与孤儿切片序号，彻底杜绝历史版本污染。

### 2. Qdrant Client 与 Server 版本兼容性警告
* **原因**：服务端镜像 `qdrant/qdrant:latest` 为 1.19.1，而框架默认传递的 `io.qdrant:client` 为 1.17.0，次版本跨度 $\ge 2$ 触发警告。
* **解决**：在 `build.gradle.kts` 中显式指定 `implementation("io.qdrant:client:1.19.0")`，覆盖旧版本，完全对齐消除警告。

---

## 三、 已开放的 HTTP 体验端点（对照 test.http）

### 1. 工业级文档摄取流水线（里程碑 4.2 核心成果）
* **上传/更新真实文档**：
  * `POST http://localhost:8080/knowledge/documents`
  * Form 字段：`file`（文件二进制流）、`documentId`（业务标识，如 `onboarding-guide`）
  * 内置两层去重：第一层哈希相同短路跳过；第二层哈希不同原子删除重建。
* **查看已入库文档清单**：
  * `GET http://localhost:8080/knowledge/documents`
* **跨文档语义向量检索**：
  * `GET http://localhost:8080/knowledge/search?query=...&maxResults=3`
* **基于私域文档的完整 RAG 问答**：
  * `GET http://localhost:8080/knowledge/ask?query=...`

### 2. 存量学习端点与可视化控制台
* **单句知识切片快速录入**：
  * `GET http://localhost:8080/api/agent/knowledge/ingest?text=...`
* **Qdrant 向量数据库可视化仪表盘**：
  * `http://localhost:6333/dashboard`
* **MinIO 对象存储 Web 控制台**：
  * `http://localhost:9101`（账号: `minioadmin`，密码: `minioadmin_password`）
* **Elasticvue Elasticsearch 控制台**：
  * `http://localhost:8088`

