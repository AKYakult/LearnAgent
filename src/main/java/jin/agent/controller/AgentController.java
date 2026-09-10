package jin.agent.controller;

import jin.agent.declarative.Assistant;
import jin.agent.react.ReActEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 体验接口：提供 HTTP 入口测试 ReAct Agent 的思考与行动
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ReActEngine reActEngine;
    private final Assistant assistant;

    public AgentController(ReActEngine reActEngine, Assistant assistant) {
        this.reActEngine = reActEngine;
        this.assistant = assistant;
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
}
