package jin.agent.declarative;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 阶段三：基于 LangChain4j @AiService 理念的声明式智能体外观接口
 * 注意：不需要写任何实现类 (Impl)，LangChain4j 运行时会自动使用动态代理生成实现
 */
public interface Assistant {

    /**
     * 系统提示词 (SystemMessage)：智能体人设、行为准则与工具调用规范
     */
    @SystemMessage("""
                你是一个专业的企业综合智能助理。
                请严格遵循以下原则为用户提供服务：
                1. 遇到企业内部资料、团队规范、办公地点、入职指引、技术架构与技术栈等私域问题时，必须主动调用 searchKnowledge 工具从知识库中检索相关资料，严格依据参考资料客观作答，切勿主观编造；
                2. 遇到算术加减乘除、圆面积等数学计算时，必须优先调用数学工具进行精确计算；
                3. 支持针对复杂问题的链式推理（例如先调用 searchKnowledge 查阅资料，再调用数学工具进行计算）；
                4. 回答应当礼貌自然、结构清晰、逻辑严密。
                """)
    String chat(@UserMessage String userMessage);
}