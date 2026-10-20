package jin.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

    /**
     * RestClient 是 Spring 6.1+ 引入的同步 HTTP 客户端，
     * 替代老旧的 RestTemplate，API 更流畅，且不需要引入 Spring Cloud。
     */
    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
