package jin.product.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品接口
 *
 * 故意在返回值里带上当前容器的 hostname（在 K8s 中就是 Pod 名字），
 * 这样多次请求时就能直观看到 K8s 把流量轮询分给了不同的 Pod —— 负载均衡一目了然。
 */
@RestController
@RequestMapping("/product")
public class ProductController {

    @Value("${server.port:8081}")
    private int port;

    @GetMapping("/{id}")
    public Map<String, Object> getProduct(@PathVariable String id) throws Exception {
        String pod = InetAddress.getLocalHost().getHostName();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", id);
        data.put("productName", "iPhone " + id);
        data.put("price", 9999);
        data.put("pod", pod);
        data.put("port", port);
        return Map.of("code", 200, "msg", "success", "data", data);
    }
}
