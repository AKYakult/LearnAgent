package jin.agent.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶段六：Redisson 客户端显式配置类
 *
 * 面试与架构设计要点：
 * 1. 显式构建 RedissonClient Bean，拒绝 starter 自动装配黑盒，保持与 Spring Data Redis 完全解耦互不干扰。
 * 2. 默认启用看门狗（Watchdog）续期机制（lockWatchdogTimeout = 30000ms），每隔 10 秒（1/3）自动检查与续期。
 * 3. 合理配置单节点连接池参数（最小空闲连接数、最大连接数），避免高并发下频繁创建连接导致网络抖动。
 */
@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;

        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(address)
                .setTimeout(3000)
                .setConnectionMinimumIdleSize(8)
                .setConnectionPoolSize(32);

        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }

        // 看门狗兜底超时时间（毫秒），默认 30 秒
        config.setLockWatchdogTimeout(30000L);

        log.info("RedissonClient 正在初始化，连接目标地址: {}", address);
        return Redisson.create(config);
    }
}
