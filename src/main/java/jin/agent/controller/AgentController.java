package jin.agent.controller;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jin.agent.declarative.Assistant;
import jin.agent.react.ReActEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 体验接口：提供 HTTP 入口测试 ReAct Agent、声明式 Agent 以及 Qdrant 向量检索与 RAG 问答
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ReActEngine reActEngine;
    private final Assistant assistant;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public AgentController(ReActEngine reActEngine,
                           Assistant assistant,
                           ChatModel chatModel,
                           EmbeddingModel embeddingModel,
                           EmbeddingStore<TextSegment> embeddingStore) {
        this.reActEngine = reActEngine;
        this.assistant = assistant;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 测试手写 ReAct Agent
     * 示例：http://localhost:8080/api/agent/react?query=请查询北京现在的天气气温，并计算如果气温乘以2.5再加上10等于多少？
     */
    @GetMapping("/react")
    public Map<String, Object> askReAct(
            @RequestParam(defaultValue = "请查询北京现在的天气气温，并计算如果气温乘以2.5再加上10等于多少？") String query,
            @RequestParam(defaultValue = "5") int maxSteps) {

        long startTime = System.currentTimeMillis();
        String answer = reActEngine.run(query, maxSteps);
        long cost = System.currentTimeMillis() - startTime;

        return Map.of(
                "query", query,
                "answer", answer,
                "costMs", cost,
                "status", "success"
        );
    }

    /**
     * 阶段三：声明式 @AiService 智能体端点
     */
    @GetMapping("/declarative")
    public Map<String, Object> askDeclarative(
            @RequestParam(defaultValue = "请计算半径为 4.5 的圆的面积是多少？")
            String query) {
        long startTime = System.currentTimeMillis();
        String answer = assistant.chat(query);
        long cost = System.currentTimeMillis() - startTime;
        return Map.of(
                "query", query,
                "answer", answer,
                "costMs", cost,
                "status", "success"
        );
    }

    /**
     * 阶段四：知识切片录入端点（基于内容指纹幂等去重）
     * 根据文本内容计算确定性 UUID，无论重复点击录入多少次，相同内容在向量库中永远只保留一条
     */
    @GetMapping("/knowledge/ingest")
    public Map<String, Object> ingestKnowledge(
            @RequestParam(defaultValue = "小明养了一只名叫咪咪的橘猫，它的性格非常温顺，每天最喜欢吃小鱼干和晒太阳。")
            String text) {
        long startTime = System.currentTimeMillis();
        TextSegment segment = TextSegment.from(text);
        Embedding embedding = embeddingModel.embed(segment).content();

        // 基于文本内容计算确定性 UUID（内容指纹）
        String id = java.util.UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        embeddingStore.addAll(List.of(id), List.of(embedding), List.of(segment));

        long cost = System.currentTimeMillis() - startTime;
        return Map.of(
                "status", "success",
                "id", id,
                "text", text,
                "costMs", cost
        );
    }

    /**
     * 阶段四：知识库语义相似度检索端点
     * 将提问通过 Ollama 转化为向量，在 Qdrant 中根据余弦距离搜索最相关的 Top-K 切片
     */
    @GetMapping("/knowledge/search")
    public Map<String, Object> searchKnowledge(
            @RequestParam(defaultValue = "小明平时养了什么宠物？")
            String query,
            @RequestParam(defaultValue = "3")
            int maxResults) {
        long startTime = System.currentTimeMillis();
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        long cost = System.currentTimeMillis() - startTime;

        List<Map<String, Object>> matches = result.matches().stream()
                .map(match -> Map.<String, Object>of(
                        "text", match.embedded().text(),
                        "score", match.score(),
                        "embeddingId", match.embeddingId()
                ))
                .toList();

        return Map.of(
                "query", query,
                "count", matches.size(),
                "matches", matches,
                "costMs", cost,
                "status", "success"
        );
    }

    /**
     * 阶段四：完整 RAG（检索增强生成）问答端点
     * 完整闭环链路：
     * 1. Retrieval (检索)：Ollama 将 query 向量化并在 Qdrant 搜出相关切片；
     * 2. Augmentation (增强)：将切片作为 Context 注入 Prompt；
     * 3. Generation (生成)：商汤大模型结合资料阅读理解，输出自然语言回答。
     */
    @GetMapping("/knowledge/ask")
    public Map<String, Object> askRag(
            @RequestParam(defaultValue = "小明平时养了什么宠物？它喜欢吃什么？")
            String query) {
        long startTime = System.currentTimeMillis();

        // 1. 检索阶段 (R)
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(2)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        // 拼接检索到的外部参考资料
        String context = result.matches().stream()
                .map(m -> "- " + m.embedded().text())
                .collect(Collectors.joining("\n"));

        // 2. 增强阶段 (A) 与 3. 生成阶段 (G)
        String prompt = """
                你是一个严谨客观的知识库智能助理。请严格根据以下提供的【参考资料】用通俗、流畅、自然的中文回答用户的【问题】。
                如果参考资料中没有提及相关答案，请如实告知“知识库中未找到相关答案”，切勿凭空捏造。

                【参考资料】：
                %s

                【用户问题】：
                %s
                """.formatted(context.isBlank() ? "无相关资料" : context, query);

        // 调用商汤 SenseNova 大模型生成人类语言回答
        String answer = chatModel.chat(prompt);
        long cost = System.currentTimeMillis() - startTime;

        return Map.of(
                "query", query,
                "answer", answer,
                "referencedDocs", result.matches().stream().map(m -> m.embedded().text()).toList(),
                "costMs", cost,
                "status", "success"
        );
    }
}
