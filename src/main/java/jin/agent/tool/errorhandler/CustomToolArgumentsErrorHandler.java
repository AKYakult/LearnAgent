package jin.agent.tool.errorhandler;

import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 里程碑 5.3：工具入参解析异常处理器 (Tool Arguments Error Handler)
 *
 * 职责：拦截大模型在输出 Function Calling 参数时产生的反序列化/Schema 校验失败。
 *
 * 典型触发场景：
 * - 大模型生成了非合法 JSON（例如遗漏引号、多余逗号）
 * - 把数值参数传为字符串（例如 radius="abc"）
 * - 遗漏了必填字段
 * - JSON 结构与方法签名不匹配
 *
 * 处理策略：
 * 将错误转换为引导性提示文本，喂回大模型，让模型根据参数规范说明重新构造调用请求。
 * 不暴露 Jackson/Gson 反序列化的内部堆栈细节。
 */
public class CustomToolArgumentsErrorHandler implements ToolArgumentsErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomToolArgumentsErrorHandler.class);

    @Override
    public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
        String toolName = context.toolExecutionRequest() != null
                ? context.toolExecutionRequest().name()
                : "未知工具";

        log.warn("[ToolArgumentsErrorHandler] 工具 [{}] 入参解析失败: {}", toolName, error.getMessage(), error);

        // 返回清晰的纠错提示，引导模型严格按照工具参数类型说明重新生成调用请求
        return ToolErrorHandlerResult.text(
                "【入参解析失败】工具 '" + toolName + "' 接收到的参数格式有误: " + error.getMessage()
                + "。请严格对照该工具的参数类型说明重新构造调用请求。"
        );
    }
}
