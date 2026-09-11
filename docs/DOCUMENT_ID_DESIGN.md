# 里程碑 4.2 设计文档：基于 documentId 的文档身份与版本管理（方案 B）

> 本文档聚焦阶段四里程碑 4.2 中「文档摄取流水线」的核心前提问题：**如何判断两次上传是同一篇文档的不同版本**，并给出完整的设计与落地方案。

---

## 一、问题背景

### 1.1 内容哈希无法回答"是否同一篇文档"

第一层去重方案计划用 SHA-256 判断"文件内容是否变化"，但这只能回答**内容层面**的问题：两份字节流是否完全相同。它无法回答**身份层面**的问题：这两份文件是不是同一篇文档的不同版本。

举例：

| 版本 | 文件名 | 内容摘要 | SHA-256 |
| :--- | :--- | :--- | :--- |
| v1 | `guide.md` | "如何配置..." | `abc123` |
| v2 | `guide_v2_final.md` | "如何配置...（补充了一段）" | `def456` |

v1 和 v2 的哈希完全不同，仅凭哈希本身，系统无法判断它们是同一篇文档的演进关系——看起来就是两份毫不相干的文件。

### 1.2 结论：需要一个独立于内容、跨版本保持不变的标识符

这个标识符不能从文件内容或文件名推导出来，必须由某一方**显式声明**并保证其稳定性。这就是 `documentId` 要扮演的角色，类似 Git 用路径 / blob 追踪机制识别"同一个文件"，而不依赖内容本身。

---

## 二、方案选型：为什么选方案 B

| 方案 | 身份来源 | 优点 | 缺点 | 适用场景 |
| :--- | :--- | :--- | :--- | :--- |
| A：文件名即身份 | 上传时的文件名 | 零设计成本 | 改文件名 = 被识别为新文档 | 快速原型、单人学习项目 |
| **B：调用方显式传业务 Key** | 独立于文件名的 `documentId` 参数 | 健壮、贴近生产系统（类似 CMS 的"文章 ID"） | 调用方需自行维护 Key 的稳定性 | 生产级 / 多人协作场景 |
| C：内容相似度自动关联 | 算法推断 | 无需人工声明 | 判断阈值困难、误判成本高，过度设计 | 不推荐用于本项目 |

本项目选择 **方案 B**：接口新增一个必填的 `documentId` 参数，与文件名彻底解耦。

---

## 三、核心设计原则

> **`documentId` 是调用方声明的"业务身份证号"，系统只负责识别与存储，不负责验证这个身份证是否"名副其实"。**

这条原则决定了后续所有设计边界：系统信任调用方传入的 `documentId`，不会去校验"这次传的内容是否真的和上次是同一篇文档"——这个信任边界必须在设计阶段就想清楚（第七节会详细展开风险）。

---

## 四、接口设计

一个端点同时承担"首次上传"与"更新版本"两种语义，靠 `documentId` 是否已存在于 MinIO 中来区分，不拆分成两个接口：

```java
@PostMapping("/knowledge/documents")
public Map<String, Object> ingestDocument(
        @RequestParam("file") MultipartFile file,
        @RequestParam("documentId") String documentId) {
    // documentId 必填；建议做基本格式校验（如仅允许字母、数字、连字符）
    ...
}
```

调用方使用体验：

```
# 首次上传
POST /knowledge/documents
documentId=onboarding-guide
file=guide.md

# 三个月后，内容改了，文件也重命名了
POST /knowledge/documents
documentId=onboarding-guide     ← 保持不变，系统才能识别为"更新"
file=guide_v2_final.md          ← 文件名随便改，系统不关心
```

---

## 五、MinIO 存储设计

### 5.1 Object Key 命名：用 documentId，而非原始文件名

```
myagent-docs/documents/onboarding-guide   ← object key 固定，同 documentId 覆盖上传
```

原始文件名仅作为 MinIO 的 user metadata 存储，只用于展示 / 追溯，不参与任何判断逻辑：

```java
Map<String, String> metadata = Map.of(
    "original-filename", file.getOriginalFilename(),
    "content-hash", newHash
);

minioClient.putObject(PutObjectArgs.builder()
        .bucket("myagent-docs")
        .object("documents/" + documentId)
        .userMetadata(metadata)
        .stream(file.getInputStream(), file.getSize(), -1)
        .build());
```

同一个 `documentId` 再次上传，MinIO 里就是同一个 key 被覆盖。若开启 Bucket Versioning，MinIO 会自动保留历史版本（附加能力，非本次必做项，见第八节）。

### 5.2 为什么不用单独的数据库表存哈希

理论上可以在 Postgres 建 `documents(document_id, latest_content_hash, updated_at)` 表来记录"上一次的哈希"，但本项目现阶段选择**直接复用 MinIO 的 object metadata** 作为轻量注册表，理由：

- 避免同时引入 Postgres JDBC + 表结构 + Repository 层，减少本阶段新增的复杂度
- MinIO 的 `statObject` 本身就能读出 metadata，够用
- Postgres 的持久化职责留到阶段五「会话持久化」时统一引入，避免概念分散

---

## 六、哈希比较逻辑（第一层去重完整闭环）

```java
String newHash = sha256(file.getBytes());

String oldHash = null;
try {
    StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
            .bucket("myagent-docs")
            .object("documents/" + documentId)
            .build());
    oldHash = stat.userMetadata().get("content-hash");
} catch (ErrorResponseException e) {
    // 404：说明该 documentId 是第一次上传，oldHash 保持 null
}

if (newHash.equals(oldHash)) {
    return Map.of("status", "skipped", "reason", "内容未变化，跳过重复摄取");
}

// 走完整摄取流程：MinIO 落盘 → 删除该 documentId 下所有旧切片 → 重新分块向量化写入
```

说明：`oldHash == null`（首次上传）与 `oldHash != null 但不相等`（内容更新）走的是**同一条代码路径**，都进入摄取流程；只有"哈希相等"这一种情况才短路跳过。这样不需要为"首次 / 更新"写两套分支。

---

## 七、Qdrant 切片设计（配合"删除重建"策略）

### 7.1 切片 metadata 结构

```java
Map<String, Object> chunkMetadata = Map.of(
    "document_id", documentId,                       // 稳定不变的业务 Key
    "chunk_index", i,                                 // 切片序号
    "content_hash", newHash,                          // 整篇文档哈希，标记这批切片对应哪个版本
    "original_filename", file.getOriginalFilename()   // 仅展示用
);
```

### 7.2 更新时的处理流程：整体删除重建，取代"孤儿切片清理"

不再采用"比较新旧切片数量、清理多余槽位"的方案，而是统一执行：

```java
// 1. 删除该 documentId 下所有旧切片
Filter filter = MetadataFilterBuilder.metadataKey("document_id").isEqualTo(documentId);
embeddingStore.removeAll(filter);

// 2. 对新内容重新分块、向量化，整批插入
List<TextSegment> segments = DocumentSplitters.recursive(400, 50).split(document);
List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
embeddingStore.addAll(embeddings, segments);
```

> `EmbeddingStore.removeAll(Filter)` 是 LangChain4j 核心接口方法，Qdrant 的实现已在官方仓库中支持（合并于 2024 年底），本项目当前使用的 `langchain4j-bom 1.20.0` 已包含该能力，可直接使用。

这样无论新版本切片数量是变多、变少还是不变，只要内容有变化就会被完整替换，不存在"孤儿切片"的概念，也不再需要 `chunk_index` 确定性 UUID 承担"覆盖同槽位"的职责（旧切片已经先被整体删除）。

---

## 八、风险与信任边界（必须提前知道的代价）

方案 B 的核心代价是：**系统无法验证 `documentId` 的语义正确性**，以下风险需要调用方自行承担：

1. **同名冲突（误用）**：若不小心把两篇不相关的文档都传成同一个 `documentId`，系统会认为这是"该文档的新版本"，直接清空重建——没有机制能识别这是误操作还是真实更新。
2. **拼写不一致导致版本断裂**：这次传 `onboarding-guide`，下次误写成 `onboarding_guide`（下划线），系统会视为两篇完全独立的文档，各自累积，版本覆盖失效。

对当前单人学习项目而言，这两个风险发生概率极低，可以不做额外防护。若未来扩展为多人协作场景，可考虑：

- 首次创建后"锁定" `documentId`，仅允许通过专门的更新接口修改
- 或退化为「方案 A + 系统自动生成 ID」混合模式：上传时系统返回一个 ID，后续更新必须携带该 ID

这两项均**不在本次实现范围内**，留作后续演进方向记录。

---

## 九、辅助端点（建议顺手实现）

因为 `documentId` 需要调用方主动记忆，建议提供一个查询端点方便调试：

```java
@GetMapping("/knowledge/documents")
public List<String> listDocumentIds() {
    // 列出 MinIO "documents/" 前缀下所有 object key
}
```

不影响核心逻辑，但能显著改善调试体验。

---

## 十、实施顺序建议

1. **MinIO 存取 + 哈希比较层**：先实现 `statObject` 读旧哈希、`putObject` 写入新文件与 metadata 的逻辑，这一层最独立，最容易单测。
2. **分块与向量化层**：接入 `DocumentSplitters.recursive(400, 50)`，产出 `TextSegment` 列表。
3. **Qdrant 删除重建层**：`removeAll(Filter)` + `addAll`，完成第二层落地。
4. **端点暴露与联调**：`POST /knowledge/documents`（摄取）+ `GET /knowledge/documents`（列表），编写 `KnowledgeIngestionLiveTest` 覆盖"首次上传 / 内容未变跳过 / 内容变化触发重建"三种场景。

---

## 附：与此前规划的差异对照

| 项目 | 此前规划（ROADMAP.md 里程碑 4.2 初版） | 本文档最终方案 |
| :--- | :--- | :--- |
| 文档身份来源 | 未明确定义 | 调用方显式传入 `documentId`，与文件名解耦 |
| 去重层数 | 三层（文件级 + 槽位级 + 孤儿清理） | 两层（文件级哈希 + 删除重建） |
| 切片 ID 生成 | `UUID.nameUUIDFromBytes(docId:chunkIndex)` 承担覆盖同槽位职责 | 不再需要靠 ID 生成承担覆盖职责，可选保留仅为可读性 |
| 版本变化处理 | 仅处理"切片数量减少"场景 | 统一处理任意变化（数量增/减/不变） |
