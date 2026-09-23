package jin.agent.tool.declarative;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class MathTools {
    private static final Logger log = LoggerFactory.getLogger(MathTools.class);

    @Tool("计算两个浮点数的和 (a +b)")
    public double add(
            @P("第一个加数 a") double a,
            @P("第二个加数 b") double b) {
        log.info("\n[Tool 触发] add:{} + {}", a, b);
        return a + b;
    }

    @Tool("计算两个浮点数的乘积 (a * b)")
    public double multiply(
            @P("被乘数 a") double a,
            @P("乘数 b") double b
    ) {
        log.info("[Tool 触发] multiply: {} * {}", a, b);
        return a * b;
    }

    @Tool("计算圆的面积，半径必须为非负数")
    public double calculateCircleArea(
            @P("圆的半径 radius，必须 >= 0") double radius
    ) {
        log.info("[Tool 触发] calculateCircleArea: 半径={}", radius);

        // 业务校验：半径不能为负数，抛出 IllegalArgumentException 触发外环反思自愈
        if (radius < 0) {
            throw new IllegalArgumentException("半径不能为负数 (当前传入: " + radius + ")，请传入一个 >= 0 的数值。");
        }

        return Math.PI * radius * radius;
    }
}
