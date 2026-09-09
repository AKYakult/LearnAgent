package jin.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例工具：天气与环境查询工具（模拟外部 API / 数据库查询）
 * 用于弥补大模型无法感知实时外部世界的弱项
 */
@Component
public class WeatherTool implements AgentTool {

    private static final Map<String, String> WEATHER_DATA = Map.of(
            "北京", "北京今日天气：晴朗，气温 26°C，湿度 42%，微风，空气质量优",
            "上海", "上海今日天气：小雨转阴，气温 22°C，湿度 78%，东风 3级，建议携带雨具",
            "广州", "广州今日天气：多云转雷阵雨，气温 31°C，湿度 85%，南风 2级",
            "深圳", "深圳今日天气：晴间多云，气温 30°C，湿度 75%，微风",
            "杭州", "杭州今日天气：阴天，气温 24°C，湿度 65%，东北风 2级"
    );

    @Override
    public String name() {
        return "weather_query";
    }

    @Override
    public String description() {
        return "用于查询指定城市的实时天气预报信息。输入参数为城市名称，例如 '北京' 或 '上海'。";
    }

    @Override
    public String execute(String input) {
        if (input == null || input.isBlank()) {
            return "错误：查询城市名称不能为空";
        }
        String city = input.trim().replace("市", "").replace("`", "").replace("\"", "");
        String info = WEATHER_DATA.get(city);
        if (info != null) {
            return info;
        }
        return "暂未收录城市 [" + city + "] 的气象数据。已有城市包括：北京、上海、广州、深圳、杭州。";
    }
}
