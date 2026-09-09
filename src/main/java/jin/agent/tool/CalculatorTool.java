package jin.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Stack;

/**
 * 示例工具：高精度算术计算器
 * 用于解决大模型自身的弱项：复杂数学计算
 */
@Component
public class CalculatorTool implements AgentTool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "用于进行数学四则运算的计算器。输入参数为一个算术表达式字符串，例如 '12 + 34' 或 '26 * 2.5 + 10'。";
    }

    @Override
    public String execute(String input) {
        if (input == null || input.isBlank()) {
            return "错误：计算表达式不能为空";
        }
        try {
            // 清理可能包含的空格、反引号或多余字符
            String expr = input.replace("`", "").replace(" ", "").trim();
            double result = evaluateExpression(expr);
            return String.valueOf(result);
        } catch (Exception e) {
            return "计算错误：" + e.getMessage() + "，请检查输入的表达式：" + input;
        }
    }

    /**
     * 基础算术表达式求值器 (支持 + - * / 与浮点数)
     */
    private double evaluateExpression(String expression) {
        char[] tokens = expression.toCharArray();
        Stack<Double> values = new Stack<>();
        Stack<Character> ops = new Stack<>();

        for (int i = 0; i < tokens.length; i++) {
            char c = tokens[i];

            // 解析数字（包括小数）
            if ((c >= '0' && c <= '9') || c == '.') {
                StringBuilder sb = new StringBuilder();
                while (i < tokens.length && ((tokens[i] >= '0' && tokens[i] <= '9') || tokens[i] == '.')) {
                    sb.append(tokens[i++]);
                }
                i--;
                values.push(Double.parseDouble(sb.toString()));
            } else if (c == '(') {
                ops.push(c);
            } else if (c == ')') {
                while (ops.peek() != '(') {
                    values.push(applyOp(ops.pop(), values.pop(), values.pop()));
                }
                ops.pop();
            } else if (c == '+' || c == '-' || c == '*' || c == '/') {
                while (!ops.empty() && hasPrecedence(c, ops.peek())) {
                    values.push(applyOp(ops.pop(), values.pop(), values.pop()));
                }
                ops.push(c);
            }
        }

        while (!ops.empty()) {
            values.push(applyOp(ops.pop(), values.pop(), values.pop()));
        }

        return values.pop();
    }

    private boolean hasPrecedence(char op1, char op2) {
        if (op2 == '(' || op2 == ')') {
            return false;
        }
        return (op1 != '*' && op1 != '/') || (op2 != '+' && op2 != '-');
    }

    private double applyOp(char op, double b, double a) {
        return switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '/' -> {
                if (b == 0) throw new UnsupportedOperationException("除数不能为0");
                yield a / b;
            }
            default -> 0.0;
        };
    }
}
