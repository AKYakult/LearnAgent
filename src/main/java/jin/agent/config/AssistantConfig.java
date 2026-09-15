package jin.agent.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jin.agent.declarative.Assistant;
import jin.agent.memory.PostgresChatMemoryStore;
import jin.agent.tool.declarative.KnowledgeTools;
import jin.agent.tool.declarative.MathTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶段三、阶段四与阶段五：声明式智能体配置类
 * 负责通过 AiServices 将大模型、数学工具、私域知识库混合检索工具和多会话持久化记忆组装为 Assistant Bean
 */
@Configuration
public class AssistantConfig {

    /**
     * 阶段五：按会话维度动态提供滑动窗口记忆 (ChatMemoryProvider)
     * 结合 PostgresChatMemoryStore，实现每个 conversationId 独立的 10 条消息滑动窗口，并在 PostgreSQL 中落盘
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(PostgresChatMemoryStore postgresChatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(postgresChatMemoryStore)
                .build();
    }

    @Bean
    public Assistant assistant(ChatModel chatModel,
                               MathTools mathTools,
                               KnowledgeTools knowledgeTools,
                               ChatMemoryProvider chatMemoryProvider) {
        // 利用 LangChain4j 提供的 AiServices 建造者，动态代理生成 Assistant 实例
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)                        // 注入大模型客户端
                .tools(mathTools, knowledgeTools)            // 同时挂载数学计算与知识库混合检索工具
                .chatMemoryProvider(chatMemoryProvider)      // 注入多会话隔离与持久化 Provider
                .build();
    }
}
