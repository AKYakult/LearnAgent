package jin.order.controller;

import jin.order.client.ProductClient;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/order")
public class OrderController {

    private final ProductClient productClient;

    public OrderController(ProductClient productClient) {
        this.productClient = productClient;
    }

    /**
     * 下单接口：先远程调用商品服务拿到商品信息，再"创建订单"。
     *
     * 在 K8s 里，这一发请求的完整链路：
     *   浏览器 → K8s NodePort(order-service) → order Pod
     *          → K8s Service(product-service) → 某个 product Pod（负载均衡轮询）
     */
    @GetMapping("/create/{productId}")
    public Map<String, Object> createOrder(@PathVariable String productId) throws Exception {
        Map<String, Object> productInfo = productClient.fetchProduct(productId);
        String orderPod = InetAddress.getLocalHost().getHostName();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("msg", "订单创建成功");
        result.put("orderPod", orderPod);
        result.put("productInfo", productInfo);
        return result;
    }
}
