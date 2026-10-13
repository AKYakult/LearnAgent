package jin.agent.declarative;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/**
 * 阶段三与阶段五：基于 LangChain4j @AiService 理念的声明式智能体外观接口
 * 1. 继承 ChatMemoryAccess，支持直接访问和清除底层会话上下文；
 * 2. 支持 @MemoryId 注解，实现多租户/多会话彻底隔离与持久化存储；
 * 3. 兼容单入参 chat(userMessage)，默认路由至 "default" 会话。
 */
public interface Assistant extends ChatMemoryAccess {

    /**
     * 系统提示词 (SystemMessage)：智能体人设、行为准则、工具调用规范与反思自愈法则
     */
    @SystemMessage("""
                你是一个专业的企业综合智能助理。
                请严格遵循以下原则为用户提供服务：
                1. 遇到企业内部资料、团队规范、办公地点、入职指引、技术架构与技术栈等私域问题时，必须主动调用 searchKnowledge 工具从知识库中检索相关资料，严格依据参考资料客观作答，切勿主观编造；
                2. 遇到算术加减乘除、圆面积等数学计算时，必须优先调用数学工具进行精确计算；
                3. 支持针对复杂问题的链式推理（例如先调用 searchKnowledge 查阅资料，再调用数学工具进行计算）；
                4. 回答应当礼貌自然、结构清晰、逻辑严密；

                【工具调用失败与自愈反思规范】
                5. 当工具返回包含【工具执行受阻】、【入参解析失败】或【检索结果】未检索到相关文档的提示时，你必须主动进行反思与自愈：
                   - 仔细阅读工具返回的具体错误原因（如参数越界、类型不符、关键词过窄等）；
                   - 结合上下文修正入参（例如：将负数半径改为正数、改写更通用的搜索关键词）并发起重试；
                   - 若经过合理尝试依然无法成功，严禁凭空捏造事实，必须如实向用户说明遇到的客观困难与建议。
                """)
    String chat(@MemoryId Object memoryId, @UserMessage String userMessage);

    /**
     * 向前兼容的单入参对话方法（LangChain4j 内部会自动将其 memoryId 设为 "default"）
     */
    String chat(@UserMessage String userMessage);
}