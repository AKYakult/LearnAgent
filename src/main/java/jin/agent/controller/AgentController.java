package jin.agent.controller;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jin.agent.declarative.Assistant;
import jin.agent.react.ReActEngine;
import jin.agent.service.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 体验接口：提供 HTTP 入口测试 ReAct Agent、声明式 Agent 以及 Qdrant 向量检索与 RAG 问答
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ReActEngine reActEngine;
    private final Assistant assistant;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KnowledgeService knowledgeService;

    public AgentController(ReActEngine reActEngine,
                           Assistant assistant,
                           EmbeddingModel embeddingModel,
                           EmbeddingStore<TextSegment> embeddingStore,
                           KnowledgeService knowledgeService) {
        this.reActEngine = reActEngine;
        this.assistant = assistant;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.knowledgeService = knowledgeService;
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
     * 阶段四（4.1 存量端点）：单句知识切片录入（基于内容指纹幂等去重）
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
     * 阶段四：知识库语义相似度检索端点（下沉至 KnowledgeService）
     */
    @GetMapping("/knowledge/search")
    public Map<String, Object> searchKnowledge(
            @RequestParam(defaultValue = "小明平时养了什么宠物？")
            String query,
            @RequestParam(defaultValue = "3")
            int maxResults) {
        long startTime = System.currentTimeMillis();
        List<KnowledgeService.SearchResultItem> matches = knowledgeService.search(query, maxResults, 0.5);
        long cost = System.currentTimeMillis() - startTime;

        return Map.of(
                "query", query,
                "count", matches.size(),
                "matches", matches,
                "costMs", cost,
                "status", "success"
        );
    }

    /**
     * 阶段四：完整 RAG（检索增强生成）问答端点（下沉至 KnowledgeService）
     */
    @GetMapping("/knowledge/ask")
    public Map<String, Object> askRag(
            @RequestParam(defaultValue = "小明平时养了什么宠物？它喜欢吃什么？")
            String query) {
        return knowledgeService.ask(query, 2, 0.5);
    }
}
