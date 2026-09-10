package jin.agent.controller;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
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

/**
 * 体验接口：提供 HTTP 入口测试 ReAct Agent、声明式 Agent 以及 Qdrant 向量检索
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ReActEngine reActEngine;
    private final Assistant assistant;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public AgentController(ReActEngine reActEngine,
                           Assistant assistant,
                           EmbeddingModel embeddingModel,
                           EmbeddingStore<TextSegment> embeddingStore) {
        this.reActEngine = reActEngine;
        this.assistant = assistant;
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
     * 阶段四：知识切片录入端点
     * 将输入文本通过 Ollama bge-m3 转化为 1024 维向量，写入 Qdrant 向量库
     */
    @GetMapping("/knowledge/ingest")
    public Map<String, Object> ingestKnowledge(
            @RequestParam(defaultValue = "小明养了一只名叫咪咪的橘猫，它的性格非常温顺，每天最喜欢吃小鱼干和晒太阳。")
            String text) {
        long startTime = System.currentTimeMillis();
        TextSegment segment = TextSegment.from(text);
        Embedding embedding = embeddingModel.embed(segment).content();
        String id = embeddingStore.add(embedding, segment);
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
}
