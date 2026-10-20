package jin.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 商品远程调用客户端
 *
 * 核心理解点：
 *   URL 里的 "product-service" 不是 IP 地址，而是 K8s Service 的名字。
 *   K8s 集群内置 DNS 会把它解析成 Service 的 ClusterIP，
     再由 Service 把请求负载均衡到后端多个商品 Pod。
 *   —— 这就是 K8s 原生的"服务发现 + 负载均衡"，不需要 Nacos/Eureka 注册中心。
 *
 * 本地运行时：product.service.host = localhost（application.yml 默认值）
 * K8s 运行时：环境变量 PRODUCT_SERVICE_HOST = product-service（k8s-deploy.yaml 覆盖）
 */
@Component
public class ProductClient {

    private final RestClient restClient;

    @Value("${product.service.host}")
    private String productHost;

    @Value("${product.service.port:8081}")
    private int productPort;

    public ProductClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchProduct(String id) {
        return restClient.get()
                .uri("http://" + productHost + ":" + productPort + "/product/" + id)
                .retrieve()
                .body(Map.class);
    }
}
