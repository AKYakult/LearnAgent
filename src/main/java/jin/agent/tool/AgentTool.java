package jin.agent.tool;

/**
 * 智能体工具统一抽象接口
 * 任何能够被 Agent 调用的能力（查库、算术、调API、检索知识库等）都需要实现该接口
 */
public interface AgentTool {

    /**
     * 工具唯一标识名称 (供大模型在 Action 阶段决定调用哪个工具)
     */
    String name();

    /**
     * 工具的功能描述与输入参数说明 (提示词中告知大模型此工具的用途与入参格式)
     */
    String description();

    /**
     * 工具具体执行逻辑
     *
     * @param input 大模型在 Action Input 中解析出的参数字符串
     * @return 工具执行后的输出结果，后续将作为 Observation 喂回给大模型
     */
    String execute(String input);
}
