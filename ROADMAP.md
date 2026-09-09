# MyAgent 开发演进与学习路线图 (Roadmap)

> 本文档用于规划从零开发基于 **Spring Boot + LangChain4j** 的 Agent 智能体系统。  
> 路线设计原则：**从最底层的核心原理（手写 ReAct 思考与行动循环）起步，不依赖黑盒，彻底理解 Agent 本质后，再逐步集成现代企业级基础设施（Docker、向量库、对象存储、全文检索引擎）与高级工程化特性。**

---

## 🏗️ 总体架构与阶段全景

```mermaid
flowchart TD
    subgraph Phase1["阶段 1：极简 ReAct 核心（已完成 ✅）"]
        P1_1[接入 OpenAI 兼容 API] --> P1_2[定义工具接口 Tool]
        P1_2 --> P1_3[手写 ReAct 驱动引擎 While Loop]
        P1_3 --> P1_4[输出 Thought-Action-Observation 运行日志]
    end

    subgraph Phase2["阶段 2：现代基础设施 Docker 化（当前进行 🚀）"]
        P2_1[Postgres + pgvector]
        P2_2[Qdrant 向量数据库]
        P2_3[Elasticsearch + Elasticvue]
        P2_4[MinIO S3 对象存储]
    end

    subgraph Phase3["阶段 3：LangChain4j 声明式工程化"]
        P3_1[注解驱动 @AiService & @Tool]
        P3_2[动态代理机制解析]
        P3_3[会话上下文 ChatMemory]
    end

    subgraph Phase4["阶段 4：外挂大脑（RAG 与混合检索）"]
        P4_1[MinIO 原始文档存储] --> P4_2[分块与 Embedding 向量化]
        P4_2 --> P4_3[Qdrant / PgVector 语义向量检索]
        P4_1 --> P4_4[Elasticsearch 全文精准检索]
        P4_3 & P4_4 --> P4_5[混合检索融合作为 Agent 工具]
    end

    subgraph Phase5["阶段 5：进阶实战与生产就绪"]
        P5_1[会话持久化至 PostgreSQL]
        P5_2[流式响应 SSE 打字机]
        P5_3[多工具协调与容错反思]
    end

    Phase1 --> Phase2
    Phase2 --> Phase3
    Phase3 --> Phase4
    Phase4 --> Phase5
```

---

## 阶段一：极简 ReAct 智能体（状态：已完成 ✅）

### 1.1 落地成果与复盘
* **秘钥与环境隔离**：引入 `dotenv-java`，根目录 `.env` 安全生效并被 `.gitignore` 严格保护；提供 `.env.example` 示例模板。
* **模型标准对齐**：使用 LangChain4j 1.x 规范中的现代化核心接口 `ChatModel`；排查解决商汤开放平台 OpenAI 兼容域名路由问题，对齐为 `https://token.sensenova.cn/v1`。
* **工具体系**：
  * `AgentTool`：统一接口抽象。
  * `CalculatorTool`：算术计算工具。
  * `WeatherTool`：模拟天气查询工具。
* **手写 ReAct 引擎**：
  * `ReActEngine`：基于经典 Prompt 模板构造思考规范，利用 Java 正则与 `while` 循环完成了 `Thought -> Action -> Action Input -> Observation -> Final Answer` 的完整推理闭环。
* **测试与验证**：
  * `ReActAgentLiveTest`：成功执行“查询北京气温并做算术计算”的复合任务，多轮思考与工具执行全部调通。
  * 优化 JVM 参数（`-XX:+EnableDynamicAgentLoading` 和 `-Xshare:off`），消除 JDK 21 下的干扰警告。

---

## 阶段二：现代基础设施容器化（状态：当前进行 🚀）

### 2.1 核心目标
* 采用 `docker-compose.yml` 统一编排与拉起现代 AI 智能体开发必备的基础设施全家桶。
* 共享已有的 `.env` 文件，实现数据库账号、MinIO 秘钥的无缝注入。

### 2.2 服务清单与端口规划

| 服务名称 | 镜像与版本 | 宿主机端口 | 用途与说明 |
| :--- | :--- | :--- | :--- |
| **PostgreSQL + pgvector** | `pgvector/pgvector:pg16` | `5432:5432` | 业务元数据存储 + 关系型向量索引，用于长期会话历史与关系数据 |
| **Qdrant** | `qdrant/qdrant:latest` | `6333:6333`, `6334:6334` | 专为海量高并发设计的向量数据库，自带 Web Dashboard（`http://localhost:6333/dashboard`） |
| **Elasticsearch** | `elasticsearch:8.x` | `9200:9200` | 关键词/全文倒排索引，用于混合检索（与向量检索互补） |
| **Elasticvue** | `cars10/elasticvue:latest` | `8080:8080` (或 `8088:8080`) | Elasticsearch 轻量级可视化 Web 管理控制台 |
| **MinIO** | `minio/minio:latest` | `9000:9000`, `9001:9001` | S3 兼容的高性能对象存储，存知识库原始 PDF/Word 文件；Web 控制台在 `9001` |

### 2.3 计划任务清单
1. **编写 `docker-compose.yml`**：定义 PostgreSQL(pgvector)、Qdrant、Elasticsearch、Elasticvue、MinIO 容器配置与数据卷持久化目录。
2. **编写 PostgreSQL 初始化脚本**：`docker/postgres/init.sql`，预先开启 `vector` 扩展并建立基础库。
3. **编写服务验证与启动脚本**：一键命令启动与健康检查。
4. **引入 Spring Boot 基础设施客户端依赖**：在 `build.gradle.kts` 中预备对应的客户端 SDK。

---

## 阶段三：LangChain4j 声明式工程化演进（待开始 ⏳）

### 3.1 核心目标
* 对比阶段一中手写的 `while` 循环，体验 LangChain4j 的声明式封装。
* 理解框架背后的工作原理：**框架不是魔法，只是用动态代理帮我们自动做了阶段一我们手写的事**。

### 3.2 演进内容
1. **注解驱动**：
   * 将自定义工具方法加上 `@Tool` 注解。
   * 使用 `@AiService` 接口声明智能体外观。
2. **会话记忆接入**：
   * 引入 `ChatMemory`（如基于滑动窗口的 `MessageWindowChatMemory`）。
   * 实现跨轮次多轮对话的状态保持。
3. **架构分层**：
   * Controller（对外 HTTP 接口） -> Service（Agent 协调层） -> Tools（领域能力层）。

---

## 阶段四：知识库外挂与混合检索增强（RAG）（待开始 ⏳）

### 4.1 核心目标
* 让 Agent 拥有外部“海马体”与私域知识，解决大模型幻觉与信息滞后问题。

### 4.2 模块实现
1. **文件上传与入库流水线**：
   * 用户上传 PDF / Markdown / TXT 文档至 **MinIO**。
   * 使用 LangChain4j `DocumentSplitter` 切分文本块（Chunks）。
   * 调用 `EmbeddingModel` 转化为多维向量。
   * 向量写入 **Qdrant**（或 **PgVector**）。
   * 文本写入 **Elasticsearch** 建立分词倒排索引。
2. **混合检索工具（Hybrid Search Tool）**：
   * 同时发起向量语义检索与 Elasticsearch 全文检索。
   * 融合结果并排重，封装为 `@Tool` 暴露给 Agent 使用。

---

## 阶段五：进阶实战与生产能力（待开始 ⏳）

### 5.1 核心能力
1. **会话历史持久化**：
   * 将 `ChatMemory` 中的对话消息序列化并存入 PostgreSQL，支持服务重启后恢复会话上下文。
2. **流式打字机响应（SSE）**：
   * 使用 `StreamingChatModel` + Spring WebFlux / `SseEmitter`，实现类似 ChatGPT 的逐字输出效果。
3. **异常反思与自愈（Self-Correction）**：
   * 当 Agent 调用的工具返回错误异常时，引导模型反思参数或换用其他工具尝试。
