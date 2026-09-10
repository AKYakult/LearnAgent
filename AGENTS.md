# Antigravity Agent Guidelines & Rules (AGENTS.md)

本文档是当前项目 (`MyAgent`) 中 Antigravity AI 编程助手的全局行为准则与项目规则。每次交互时智能体均会自动加载并严格遵循以下约束。

---

## 🚨 核心准则与行为约束 (Priority 0)

1. **必须主动联网检索查证（严格防幻觉）**
   * **当遇到不确定的第三方库版本、新版 API 接口签名、包名路径变动、语法细节或配置项时，必须第一时间主动使用联网搜索工具 (`search_web`) 进行检索确认**。
   * **严禁凭旧记忆、模糊印象或臆测盲目编写代码**。特别是对于处于高速迭代的框架（如 LangChain4j 1.x 的重构与更名、Spring Boot 升级等），必须以官方最新文档和 Maven 仓库实际发布情况为准。

2. **信息安全与敏感数据隔离**
   * 严禁在任何 Java 代码、配置文件或 Git 提交中硬编码敏感 API Key、Token 或数据库密码。
   * 所有密钥统一通过项目根目录 `.env` 文件或系统环境变量注入。
   * 必须确保 `.env` 保持在 `.gitignore` 保护之中。

3. **代码规范与对新手友好**
   * 当前项目作者为 Java / Spring 初学开发者，代码力求结构清晰、易懂、规范。
   * 关键设计模式（如 ReAct 思考循环、Prompt 模板、工具调度）需要提供详尽通俗的中文注释。
   * 避免滥用晦涩的隐式黑盒魔法，在引入高级封装前务必先讲清底层运行机制。
   * 避免滥用emoji

---

## 🛠️ 项目技术栈约定

* **运行环境**：Java 21 (JDK 21)
* **工程构建**：Gradle (Kotlin DSL, `build.gradle.kts`)
* **核心框架**：Spring Boot 
* **AI 框架**：LangChain4j (遵循 1.x 现代化规范，如 `dev.langchain4j.model.chat.ChatModel`)
* **模型适配**：OpenAI 兼容协议 (当前主力为商汤日日新 SenseNova，OpenAI 兼容 Base URL: `https://token.sensenova.cn/v1`)
* **计划基础设施**：
  * PostgreSQL 16 + pgvector（向量与关系业务数据）
  * Qdrant（专用向量数据库）
  * Elasticsearch 8.x + Elasticvue（全文检索与管理控制台）
  * MinIO（S3 兼容对象存储）
