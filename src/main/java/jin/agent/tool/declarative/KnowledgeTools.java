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
            // 自解释型返回：明确告知未命中，并提供换词重试的引导建议，驱动大模型自主调整检索策略
            return "【检索结果】知识库中未检索到与 '" + query + "' 相关的文档切片。\n"
                    + "【建议】请尝试以下策略后重新调用 searchKnowledge：\n"
                    + "  1. 去除专有名词中的标点修饰或特殊符号；\n"
                    + "  2. 换用近义词或范围更广的关键词；\n"
                    + "  3. 将复合问题拆分为更简短的子查询。";
        }

        return matches.stream()
                .map(m -> "【参考资料片段】:\n" + m.text())
                .collect(Collectors.joining("\n\n"));
    }
}
