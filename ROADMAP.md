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

    subgraph Phase2["阶段 2：现代基础设施 Docker 化（已完成 ✅）"]
        P2_1[Postgres + pgvector]
        P2_2[Qdrant 向量数据库]
        P2_3[Elasticsearch + Elasticvue]
        P2_4[MinIO S3 对象存储]
    end

    subgraph Phase3["阶段 3：LangChain4j 声明式工程化（已完成 ✅）"]
        P3_1[注解驱动 @AiService & @Tool]
        P3_2[动态代理机制解析]
        P3_3[会话上下文 ChatMemory]
    end

    subgraph Phase4["阶段 4：外挂大脑（RAG 与混合检索）（下一阶段 🚀）"]
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

## 阶段二：现代基础设施容器化（状态：已完成 ✅）

### 2.1 核心目标
* 采用 `docker-compose.yml` 统一编排与拉起现代 AI 智能体开发必备的基础设施全家桶。
* 共享已有的 `.env` 文件，实现数据库账号、MinIO 秘钥的无缝注入。

### 2.2 服务清单与端口规划

| 服务名称 | 镜像与版本 | 宿主机端口 | 用途与说明 |
| :--- | :--- | :--- | :--- |
| **PostgreSQL + pgvector** | `pgvector/pgvector:pg18` | `5432:5432` | 业务元数据存储 + 关系型向量索引，用于长期会话历史与关系数据 |
| **Qdrant** | `qdrant/qdrant:latest` | `6333:6333`, `6334:6334` | 专为海量高并发设计的向量数据库，自带 Web Dashboard（`http://localhost:6333/dashboard`） |
| **Elasticsearch** | `docker.elastic.co/elasticsearch/elasticsearch:8.15.0` | `9200:9200` | 关键词/全文倒排索引，用于混合检索（与向量检索互补） |
| **Elasticvue** | `cars10/elasticvue:latest` | `8088:8080` | Elasticsearch 轻量级可视化 Web 管理控制台 |
| **MinIO** | `minio/minio:latest` | `9100:9000`, `9101:9001` | S3 兼容的高性能对象存储；Web 控制台在 `9101`（避开本地已有项目的 9000/9001） |

### 2.3 落地成果
1. **编写 `docker-compose.yml`**：定义 PostgreSQL(pgvector)、Qdrant、Elasticsearch、Elasticvue、MinIO 容器配置与数据卷持久化目录。（已完成 ✅）
2. **编写 PostgreSQL 初始化脚本**：`docker/postgres/init.sql`，预先开启 `vector` 扩展并建立基础库。（已完成 ✅）
3. **编写服务验证与启动脚本**：`scripts/check-infra.ps1`，一键命令启动与健康检查，5 大中间件全绿运行。（已完成 ✅）

---

## 阶段三：LangChain4j 声明式工程化演进（状态：已完成 ✅）

### 3.1 核心目标
* 对比阶段一中手写的 `while` 循环与单出入参限制，体验 LangChain4j 的声明式封装。
* 理解框架背后的工作原理：**框架不是魔法，只是用 JDK 动态代理 + OpenAI Function Calling 规范帮我们自动完成了阶段一我们手写的事**。

### 3.2 演进内容：从手写接口到声明式 @Tool
1. **工具定义的范式跃迁 (Tool Evolution)**：
   * **阶段一痛点**：入参只能是单个 `String`（需要自己解析字符串），大模型容易传错格式。
   * **阶段三升级**：
     * 普通 Java 方法直接标注 `@Tool("方法用途描述")`。
     * 参数使用 `@P("参数说明与格式约束")` 进行精确类型定义（支持 `int`、`double`、`String`、乃至复杂 JavaBean）。
     * LangChain4j 自动通过反射提取方法签名，生成符合 OpenAI 标准的 JSON Schema 传递给模型。
2. **声明式智能体外观 (@AiService)**：
   * 仅需定义一个 Java 接口（如 `Assistant` 或 `DeclarativeAgent`），声明对话方法签名。
   * 支持 `@SystemMessage` 静态/动态模板注入人设与规则约束。
3. **智能体会话记忆 (ChatMemory)**：
   * 接入 `MessageWindowChatMemory`（滑动窗口记忆），实现跨轮次多轮连续对话上下文保持。
4. **架构装配与验证**：
   * 采用 `AiServices.builder()` 工厂组装 `ChatModel`、`Tools` 与 `ChatMemory`。
   * 编写 Controller 对外暴露端点，并编写多轮对话测试用例验证效果。

### 3.3 阶段三动手任务清单
- [x] **任务 3.1**：编写声明式工具类（如 `MathTools`），掌握 `@Tool` 与 `@P` 注解用法。
- [x] **任务 3.2**：定义智能体接口 `Assistant`，编写 `@SystemMessage` 系统提示词。
- [x] **任务 3.3**：在 Spring 配置类中通过 `AiServices.builder(...)` 装配 Bean，注入 `ChatMemory`。
- [x] **任务 3.4**：编写 Web 控制器与多轮对话测试，观察大模型 Function Calling 底层日志与上下文保持能力。

### 3.4 落地成果与复盘
1. **声明式强类型工具**：编写 `MathTools`，使用 `@Tool` 与 `@P` 支持精准的多入参类型自动绑定与调用，无需手动反序列化入参。
2. **声明式外观与系统人设**：定义 `Assistant` 接口，配合 `@SystemMessage` 约束优先使用工具计算。
3. **会话记忆与工厂装配**：`AssistantConfig` 接入 `MessageWindowChatMemory(10)`，并以 `AiServices.builder(Assistant.class)` 动态代理生成 Bean。
4. **端点暴露与多轮测试**：
   * `AgentController` 暴露 `/api/agent/declarative` 端点。
   * `DeclarativeAgentLiveTest` 自动化执行 3 轮交互（建立上下文 -> 触发工具调用 -> 跨轮跨工具回忆记忆），测试全部通过。
   * `test.http` 补充声明式接口的快捷请求样例。

---

## 阶段四：知识库外挂与混合检索增强（RAG）（状态：当前进行 🚀）

### 4.1 核心目标
* 让 Agent 拥有外部“海马体”与私域知识，解决大模型幻觉与信息滞后问题。
* 从底层的向量语义距离、高维向量存储，到文档解析分块与混合检索（RRF），逐层打通。

### 4.2 阶段四拆解任务清单
- [x] **里程碑 4.1：向量基石与 Qdrant 语义检索闭环（已完成 ✅）**
  * 引入 `langchain4j-bom` 统一版本管理，添加 `langchain4j-ollama` 与 `langchain4j-qdrant`。
  * 接入本地 Ollama 驱动的 `bge-m3` 多语言嵌入模型（1024 维高维向量）。
  * 编写 `QdrantConfig` 自动探活与建表（REST 探活创建 `myagent_knowledge` 集合，Cosine 度量）。
  * 编写 `QdrantStoreLiveTest`，完成向量切片写入与跨领域语义检索验证（相似度得分 > 0.8）。
- [ ] **里程碑 4.2：工业级文档摄取流水线（MinIO + 智能切片 + 三层幂等去重架构）（待开始 ⏳）**
  * **第一层（文档级去重）**：上传 PDF / Markdown 文档至 **MinIO** 对象存储，先计算文件级 SHA-256 校验和。已入库且内容未变更的文件直接跳过，避免昂贵的重复解析与向量计算。
  * **第二层（稳定切片定位与覆盖）**：采用 LangChain4j `DocumentSplitter` 切分文本（设置合理的 Chunk Size 与 Overlap 重叠区），切片 ID 绑定 `documentId:chunkIndex` 生成合法确定性 UUID。文档重导时，精准覆盖同名序号旧切片。
  * **第三层（切片指纹与孤儿切片清理）**：在 Qdrant Payload 中记录 `document_id`、`chunk_index` 和 `content_hash`。当文档新版本切片总数减少时，能够通过 Qdrant 过滤器精准清理过时陈旧的切片，彻底杜绝知识库历史版本污染。
  * **流水线装配**：编写完整的文档上传与流式摄取 Service，打通从“MinIO 文件落地 -> 文本解析 -> 批量向量化 -> Qdrant 幂等入库”的全链路。
- [ ] **里程碑 4.3：Elasticsearch 全文检索与混合检索融合（待开始 ⏳）**
  * 文本切片同步入库 Elasticsearch 建立分词倒排索引。
  * 实现双路召回（向量语义 + ES 倒排）与 RRF（倒数排名融合）排序。
  * 封装为 `@Tool` 挂载到 Agent，让智能体具备查阅企业知识库回答问题的能力。

---

## 阶段五：进阶实战与生产能力（待开始 ⏳）

### 5.1 核心能力
1. **会话历史持久化**：
   * 将 `ChatMemory` 中的对话消息序列化并存入 PostgreSQL，支持服务重启后恢复会话上下文。
2. **流式打字机响应（SSE）**：
   * 使用 `StreamingChatModel` + Spring WebFlux / `SseEmitter`，实现类似 ChatGPT 的逐字输出效果。
3. **异常反思与自愈（Self-Correction）**：
   * 当 Agent 调用的工具返回错误异常时，引导模型反思参数或换用其他工具尝试。
