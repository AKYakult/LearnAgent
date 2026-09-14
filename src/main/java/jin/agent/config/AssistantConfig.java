package jin.agent.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jin.agent.declarative.Assistant;
import jin.agent.tool.declarative.KnowledgeTools;
import jin.agent.tool.declarative.MathTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶段三与阶段四：声明式智能体配置类
 * 负责通过 AiServices 将大模型、数学工具、私域知识库混合检索工具和会话记忆组装为 Assistant Bean
 */
@Configuration
public class AssistantConfig {

    @Bean
    public Assistant assistant(ChatModel chatModel, MathTools mathTools, KnowledgeTools knowledgeTools) {
        // 1. 初始化基于滑动窗口的会话记忆，保留最近 10 条消息
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);

        // 2. 利用 LangChain4j 提供的 AiServices 建造者，动态代理生成 Assistant 实例
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)                        // 注入大模型客户端
                .tools(mathTools, knowledgeTools)            // 同时挂载数学计算与知识库混合检索工具
                .chatMemory(chatMemory)                      // 注入会话记忆
                .build();
    }
}
