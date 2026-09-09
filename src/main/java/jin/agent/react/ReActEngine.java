package jin.agent.react;

import dev.langchain4j.model.chat.ChatModel;
import jin.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 手写 ReAct (Reasoning + Acting) 核心执行引擎
 * 
 * 核心原理：
 * 1. Prompt 约束：告知大模型工具说明以及 Thought / Action / Action Input / Observation 格式
 * 2. 轮询推理：调用大模型获取推理决策
 * 3. 工具分发：正则捕获模型想要调用的工具，在 Java 层面执行真实代码
 * 4. 观察回传：将工具执行结果作为 Observation 拼回上下文中，让大模型基于观察结果继续推理
 * 5. 收敛退出：当模型输出 Final Answer 或达到最大步数上限时退出
 */
@Service
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile("Action Input:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile("Final Answer:\\s*([\\s\\S]+)", Pattern.CASE_INSENSITIVE);

    private final ChatModel chatModel;
    private final Map<String, AgentTool> toolMap = new HashMap<>();

    // 经典 ReAct 提示词模板
    private static final String REACT_SYSTEM_PROMPT_TEMPLATE = """
            你是一个具备推理（Reasoning）与行动（Acting）能力的智能体助手。
            你可以使用以下工具来解决问题：
            %s
            
            请严格按照以下格式进行思考和行动：
            Question: 用户的输入问题
            Thought: 思考你当前应该做什么，需要调用哪个工具，缺少什么信息
            Action: 必须严格从 [%s] 中选择一个工具名称
            Action Input: 传递给该工具的输入参数
            Observation: 工具执行后的返回结果（此内容由系统执行后提供，你切勿自己伪造）
            ... (这个 Thought / Action / Action Input / Observation 过程可以根据需要重复多轮)
            Thought: 我现在已经获得了足够的信息，知道最终答案了
            Final Answer: 针对原始问题的最终完整回答
            
            规则约束：
            1. 每次你输出完 Action Input 后，请立刻停止生成，等待系统返回 Observation。
            2. 如果无需使用工具即可回答，可直接输出 Thought 和 Final Answer。
            
            开始！
            """;

    public ReActEngine(ChatModel chatModel, List<AgentTool> tools) {
        this.chatModel = chatModel;
        for (AgentTool tool : tools) {
            this.toolMap.put(tool.name().toLowerCase(), tool);
        }
        log.info("✅ [ReActEngine] 已注册可用工具数量: {}, 工具列表: {}", toolMap.size(), toolMap.keySet());
    }

    /**
     * 执行智能体提问并驱动 ReAct 循环
     *
     * @param question 用户的原始问题
     * @param maxSteps 最大允许的思考循环步数（防止死循环）
     * @return 智能体最终回答
     */
    public String run(String question, int maxSteps) {
        log.info("\n=======================================================");
        log.info("🤖 [ReAct Agent 启动] 收到用户提问: {}", question);
        log.info("=======================================================");

        // 1. 组装可用工具描述与名称列表
        String toolDescriptions = toolMap.values().stream()
                .map(tool -> String.format("- %s: %s", tool.name(), tool.description()))
                .collect(Collectors.joining("\n"));
        String toolNames = String.join(", ", toolMap.keySet());

        String systemPrompt = String.format(REACT_SYSTEM_PROMPT_TEMPLATE, toolDescriptions, toolNames);

        // 2. 初始化思考轨迹记录器 (Scratchpad)
        StringBuilder scratchpad = new StringBuilder();
        scratchpad.append(systemPrompt);
        scratchpad.append("Question: ").append(question).append("\n");

        // 3. 开启 ReAct 驱动循环
        for (int step = 1; step <= maxSteps; step++) {
            log.info("\n----------------- [第 {} 轮思考与决策] -----------------", step);

            // 请求大模型生成下一步
            String llmOutput = chatModel.chat(scratchpad.toString());
            log.info("🧠 大模型思考输出:\n{}", llmOutput.trim());

            // 将大模型本轮输出追加到上下文中
            scratchpad.append(llmOutput).append("\n");

            // 检查是否已经得出最终结论 Final Answer
            Matcher finalAnswerMatcher = FINAL_ANSWER_PATTERN.matcher(llmOutput);
            if (finalAnswerMatcher.find()) {
                String finalAnswer = finalAnswerMatcher.group(1).trim();
                log.info("\n🎯 [任务完成] Final Answer:\n{}", finalAnswer);
                return finalAnswer;
            }

            // 检查大模型是否做出调用工具的决策
            Matcher actionMatcher = ACTION_PATTERN.matcher(llmOutput);
            Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(llmOutput);

            if (actionMatcher.find() && inputMatcher.find()) {
                String toolName = actionMatcher.group(1).trim().toLowerCase();
                String toolInput = inputMatcher.group(1).trim();

                log.info("⚡ [工具调用指令] 工具: {}, 参数: {}", toolName, toolInput);

                // 在 Java 中查找并执行对应的工具
                AgentTool tool = toolMap.get(toolName);
                String observation;
                if (tool != null) {
                    try {
                        observation = tool.execute(toolInput);
                    } catch (Exception e) {
                        observation = "工具执行异常: " + e.getMessage();
                    }
                } else {
                    observation = String.format("未找到名为 [%s] 的工具，当前可用工具列表为: %s", toolName, toolMap.keySet());
                }

                log.info("👁️ [环境反馈 Observation]: {}", observation);

                // 把 Observation 喂回给大模型，引导下一轮推理
                scratchpad.append("Observation: ").append(observation).append("\n");
            } else {
                // 如果模型既没有给出 Action 也没有给出 Final Answer
                log.warn("⚠️ 模型回复未匹配到 Action 或 Final Answer 格式，提前结束或直接返回。");
                return llmOutput;
            }
        }

        log.warn("⚠️ 超过最大迭代步数 ({})，强制结束循环。", maxSteps);
        return "抱歉，由于任务复杂度过高或未能在限定步数内收敛，Agent 已停止思考。";
    }
}
