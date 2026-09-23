package jin.agent.tool.errorhandler;

import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 里程碑 5.3：工具执行异常处理器 (Tool Execution Error Handler)
 *
 * 职责：拦截 @Tool 方法执行过程中抛出的所有异常，根据异常类型分流处理：
 *
 * 1. 业务参数校验异常 (IllegalArgumentException)：
 *    视为"可恢复的认知层问题"，将原始异常信息脱敏清洗后，转换为带纠错引导的文本
 *    反馈给大模型，驱动模型反思并修正入参后重试（走认知外环）。
 *
 * 2. 其他未预期的运行时异常（如 NullPointerException、数据库连接失败等）：
 *    视为"致命硬故障"，严格脱敏后返回统一的降级说明，
 *    绝不向大模型暴露内部堆栈、SQL 错误、文件路径等敏感信息。
 *
 * 设计原则：
 * - 所有异常都会被记录到服务端日志（含完整堆栈），供开发者排查；
 * - 返回给大模型的文本只包含"做什么"和"怎么修"的引导性建议，不含技术细节。
 */
public class CustomToolExecutionErrorHandler implements ToolExecutionErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomToolExecutionErrorHandler.class);

    @Override
    public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
        // 提取工具名称，方便日志定位
        String toolName = context.toolExecutionRequest() != null
                ? context.toolExecutionRequest().name()
                : "未知工具";

        log.warn("[ToolExecutionErrorHandler] 工具 [{}] 执行发生异常: {}", toolName, error.getMessage(), error);

        // ---- 分流 1：业务参数或前置校验异常（可引导模型自愈） ----
        // 典型场景：计算圆面积传入负数半径、查询的 documentId 格式不合法等
        if (error instanceof IllegalArgumentException) {
            String sanitizedMsg = "【工具执行受阻】工具 '" + toolName + "' 报告: " + error.getMessage()
                    + "。请仔细检查输入条件并调整入参后重试。";
            return ToolErrorHandlerResult.text(sanitizedMsg);
        }

        // ---- 分流 2：致命未预期异常（脱敏降级，防泄露内部实现） ----
        // 典型场景：Qdrant 连接超时、MinIO 磁盘满、NullPointerException 等
        // 注意：完整的异常堆栈已经在上方 log.warn 中记录，此处只返回安全的提示文本
        return ToolErrorHandlerResult.text(
                "【工具暂时不可用】工具 '" + toolName + "' 执行时发生内部服务异常。"
                + "请尝试换用其他方案回答用户，或如实向用户说明该功能暂时无法使用。"
        );
    }
}
