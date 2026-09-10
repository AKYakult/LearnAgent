package jin.agent.declarative;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 阶段三：基于 LangChain4j @AiService 理念的声明式智能体外观接口
 * 注意：不需要写任何实现类 (Impl)，LangChain4j 运行时会自动使用动态代理生成实现
 */
public interface Assistant {

    /**
     * 系统提示词 (SystemMessage)：相当于智能体的人设与全局行为准则
     */
    @SystemMessage("""
                你是一个专业的数学与通识助手。
                请在遇到算术、几何、乘法计算时，必须优先使用提供的工具进行计算，以保证结果的绝对精确。
                回答应当礼貌、结构清晰。
                """)
    String chat(@UserMessage String userMessage);
}