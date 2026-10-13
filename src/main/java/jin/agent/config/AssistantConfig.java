package jin.agent.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jin.agent.declarative.Assistant;
import jin.agent.memory.PostgresChatMemoryStore;
import jin.agent.tool.declarative.KnowledgeTools;
import jin.agent.tool.declarative.MathTools;
import jin.agent.tool.errorhandler.CustomToolArgumentsErrorHandler;
import jin.agent.tool.errorhandler.CustomToolExecutionErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶段三、阶段四与阶段五：声明式智能体配置类
 * 负责通过 AiServices 将大模型、数学工具、私域知识库混合检索工具、多会话持久化记忆
 * 以及工具调用容错反思处理器组装为 Assistant Bean
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

                // ===== 里程碑 5.3：工具调用容错反思三件套 =====

                // 1. 工具执行异常处理器：拦截 @Tool 方法运行时抛出的异常
                //    - 业务异常 (IllegalArgumentException) -> 脱敏清洗后引导大模型自我修正
                //    - 致命异常 (RuntimeException 等) -> 安全降级，不泄露技术堆栈
                .toolExecutionErrorHandler(new CustomToolExecutionErrorHandler())

                // 2. 工具入参解析异常处理器：拦截 Function Calling 参数 JSON 反序列化/类型转换失败
                //    -> 返回参数格式纠错提示，引导大模型重新构造合法调用
                .toolArgumentsErrorHandler(new CustomToolArgumentsErrorHandler())

                // 3. 防死循环熔断：单轮会话中工具调用往返的最大次数上限
                //    超出此限制后 LangChain4j 将自动终止工具链，防止错误参数无限重试导致的资费爆炸
                //    注：maxSequentialToolsInvocations 已在 1.15.0 中废弃，改用 maxToolCallingRoundTrips
                .maxToolCallingRoundTrips(10)

                .build();
    }
}

