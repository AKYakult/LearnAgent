package jin.agent.tool.declarative;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jin.agent.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 阶段四：企业私域知识库混合检索声明式工具
 * 供大模型通过 Function Calling 自主决定何时调用，实现外挂知识库与意图路由闭环
 */
@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);

    private final KnowledgeService knowledgeService;

    public KnowledgeTools(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Tool("从企业私域知识库中查询团队规范、办公地点、入职指引、技术架构与技术栈等内部文档资料")
    public String searchKnowledge(
            @P("搜索关键词或自然语言问题")
            String query) {
        log.info("🔍 [Tool 触发] searchKnowledge: query='{}'", query);

        List<KnowledgeService.SearchResultItem> matches = knowledgeService.hybridSearch(query, 3);
        if (matches.isEmpty()) {
            return "知识库中未检索到与 '" + query + "' 相关的文档片段。";
        }

        return matches.stream()
                .map(m -> "【参考资料片段】:\n" + m.text())
                .collect(Collectors.joining("\n\n"));
    }
}
