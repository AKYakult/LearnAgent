package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyAgentApplication {

    public static void main(String[] args) {
        // 1. 在 Spring 容器初始化前，优先加载根目录的 .env 文件
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        for (DotenvEntry entry : dotenv.entries()) {
            // 如果系统环境变量或属性中尚未设置，则注入到 System Property 中
            if (System.getProperty(entry.getKey()) == null && System.getenv(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }

        // 2. 启动 Spring Boot 应用
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
