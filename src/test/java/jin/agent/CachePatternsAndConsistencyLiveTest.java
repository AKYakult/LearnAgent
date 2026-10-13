package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段六第二课：缓存三大痛点与双写一致性实战 (CachePatternsAndConsistencyLiveTest)
 *
 * 核心考点：
 * 1. 缓存穿透防范：空值缓存 (Cache Null) 与短 TTL
 * 2. 缓存击穿防范：互斥锁 (Mutex Lock / SETNX) + 双重检查锁定 (DCL)
 * 3. 缓存雪崩防范：TTL 随机抖动散列 (Jitter)
 * 4. 数据双写一致性：标准 Cache-Aside 模式 (先更新 DB，再删除缓存)
 */
@SpringBootTest
public class CachePatternsAndConsistencyLiveTest {

    @BeforeAll
    static void init() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null && System.getenv(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 模拟数据库调用次数的计数器
    private final AtomicInteger dbQueryCount = new AtomicInteger(0);

    // =========================================================================
    // 1. 缓存穿透防范：空值缓存 (Cache Null)
    // 场景：恶意用户或黑客疯狂查询数据库和缓存中根本不存在的 ID (如 id = -999)
    // 痛点：每次请求都绕过 Redis 直击底层数据库，引发数据库宕机
    // 解法：首次回源 DB 查不到时，将空标记 ("__NULL__") 写入缓存，设置一个极短的过期时间 (如 30 秒)
    // =========================================================================
    @Test
    @DisplayName("1. 缓存穿透防护：通过空值缓存阻断恶意穿透请求直击 DB")
    void testCachePenetrationProtection() {
        String nonExistentId = "user:not_exist:99999";
        String cacheKey = "cache:user:" + nonExistentId;
        redisTemplate.delete(cacheKey);
        dbQueryCount.set(0);

        // 第一次请求：缓存未命中 -> 查询 DB -> DB 也为空 -> 写入空标记并设置短过期时间
        String result1 = queryUserWithNullCache(cacheKey, nonExistentId);
        assertNull(result1, "不存在的数据应返回 null");
        assertEquals(1, dbQueryCount.get(), "第一次必须真实查询了一次 DB");

        // 第二次请求：缓存命中空标记 ("__NULL__") -> 直接拦截返回 null，根本不查 DB！
        String result2 = queryUserWithNullCache(cacheKey, nonExistentId);
        assertNull(result2);
        assertEquals(1, dbQueryCount.get(), "第二次请求被空值缓存成功拦截，DB 查询次数依然为 1，保护了 DB");

        redisTemplate.delete(cacheKey);
    }

    private String queryUserWithNullCache(String cacheKey, String id) {
        // 1. 查缓存
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if ("__NULL__".equals(cached)) {
            return null; // 命中了空标记，直接短路返回，绝不去查 DB
        }
        if (cached != null) {
            return cached; // 命中有效数据
        }

        // 2. 查模拟数据库
        dbQueryCount.incrementAndGet();
        String dbData = mockQueryDatabase(id); // 查无此人，返回 null

        // 3. 防穿透核心：若数据库为 null，向 Redis 缓存一个空标记，过期时间设短 (比如 30 秒)
        if (dbData == null) {
            redisTemplate.opsForValue().set(cacheKey, "__NULL__", Duration.ofSeconds(30));
            return null;
        }

        // 4. 若有数据则正常缓存
        redisTemplate.opsForValue().set(cacheKey, dbData, Duration.ofMinutes(10));
        return dbData;
    }

    // =========================================================================
    // 2. 缓存击穿防范：互斥锁 (Mutex Lock / SETNX)
    // 场景：单一超级热点 Key (如“春晚热搜”) 恰好过期的那一刹那，数十万并发请求涌来
    // 痛点：所有并发请求同时去查 DB 并试图重建缓存，导致数据库瞬间被打爆
    // 解法：只允许拿到互斥锁的 1 个线程去查 DB 重建缓存，其余线程等待重试
    // =========================================================================
    @Test
    @DisplayName("2. 缓存击穿防护：互斥锁保证热点 Key 失效时只有 1 个线程回源查 DB")
    void testCacheBreakdownWithMutexLock() {
        String hotKey = "cache:hot:item:2026";
        String lockKey = "lock:hot:item:2026";
        redisTemplate.delete(hotKey);
        redisTemplate.delete(lockKey);
        dbQueryCount.set(0);

        // 模拟多个请求同时获取该热点数据
        // 线程 1 查询：未命中缓存，抢锁成功，查 DB 并回写缓存
        String data1 = queryHotItemWithMutex(hotKey, lockKey, "2026");
        assertEquals("HotItemData-2026", data1);
        assertEquals(1, dbQueryCount.get());

        // 线程 2 查询：此时缓存已被线程 1 重建好，直接读取缓存返回，不查 DB
        String data2 = queryHotItemWithMutex(hotKey, lockKey, "2026");
        assertEquals("HotItemData-2026", data2);
        assertEquals(1, dbQueryCount.get(), "热点数据已就绪，DB 查询次数依然是 1");

        redisTemplate.delete(hotKey);
        redisTemplate.delete(lockKey);
    }

    private String queryHotItemWithMutex(String cacheKey, String lockKey, String id) {
        // 1. 尝试从缓存拿
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 缓存失效，尝试获取互斥锁 (SETNX 锁 10 秒)
        Boolean hasLock = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(hasLock)) {
            try {
                // 双重检查锁定 (DCL)：拿到锁后再查一次缓存，避免前一个拿到锁的人刚写好缓存
                String recheck = redisTemplate.opsForValue().get(cacheKey);
                if (recheck != null) {
                    return recheck;
                }

                // 真实查 DB
                dbQueryCount.incrementAndGet();
                String dbData = "HotItemData-" + id;

                // 重建缓存并写回
                redisTemplate.opsForValue().set(cacheKey, dbData, Duration.ofMinutes(10));
                return dbData;
            } finally {
                // 确保释放锁
                redisTemplate.delete(lockKey);
            }
        } else {
            // 没抢到锁的线程休眠 50ms 后递归重试读缓存
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return queryHotItemWithMutex(cacheKey, lockKey, id);
        }
    }

    // =========================================================================
    // 3. 缓存雪崩防范：TTL 随机抖动 (Jitter)
    // 场景：系统在某个时刻批量把大量商品/文章存入缓存，设置了相同的 TTL (如 30 分钟)
    // 痛点：30 分钟一到，成千上万个 Key 同时集体过期，全部请求瞬间倾泻到底层 DB
    // 解法：在基础过期时间上加上一个随机偏移量 (例如 30 分钟 + 随机 1~5 分钟)
    // =========================================================================
    @Test
    @DisplayName("3. 缓存雪崩防护：随机过期时间打散过期洪峰")
    void testCacheAvalancheRandomTTL() {
        String baseKey = "cache:item:random_ttl:";
        Random random = new Random();

        // 批量缓存 5 个商品，每个商品赋予不同的随机过期时间
        for (int i = 1; i <= 5; i++) {
            String key = baseKey + i;
            // 基础时间 60 秒 + 随机 1~30 秒偏移量
            int randomOffsetSeconds = random.nextInt(30) + 1;
            Duration expireDuration = Duration.ofSeconds(60 + randomOffsetSeconds);

            redisTemplate.opsForValue().set(key, "Value-" + i, expireDuration);

            Long ttl = redisTemplate.getExpire(key);
            assertNotNull(ttl);
            assertTrue(ttl >= 60 && ttl <= 90, "TTL 应该在 [60, 90] 秒之间打散");
            redisTemplate.delete(key);
        }
    }

    // =========================================================================
    // 4. 数据双写一致性：Cache-Aside 经典模式
    // 面试灵魂问题：更新数据时，是“先更新数据库再删缓存”，还是“先删缓存再改数据库”？
    // 正确答案：Cache-Aside 模式：先更新数据库，再删除缓存！
    // 核心考点：
    // - 为什么先更新 DB 再删缓存：如果先删缓存，并发读线程会立即把 DB 旧数据重新读进缓存，造成持久脏数据
    // - 为什么是“删除缓存”而不是“更新缓存”：懒加载 (Lazy) 思想，修改频繁但读取不频繁时，
    //   频繁更新缓存是纯粹的性能浪费；等到有人查时再计算加载。
    // =========================================================================
    @Test
    @DisplayName("4. 双写一致性：Cache-Aside (先更新 DB，再删除缓存)")
    void testCacheAsidePattern() {
        String cacheKey = "cache:order:status:10086";
        redisTemplate.opsForValue().set(cacheKey, "UNPAID", Duration.ofMinutes(5));

        // 业务场景：用户完成了订单支付，需要将状态从 UNPAID 改为 PAID
        // 步骤 1：先更新数据库
        String newStatus = "PAID";
        mockUpdateDatabaseOrder("10086", newStatus);

        // 步骤 2：再删除缓存 (让旧缓存失效)
        redisTemplate.delete(cacheKey);

        // 步骤 3：后续下一次读请求到来时，发现缓存已空，主动回源数据库查出最新 "PAID" 并重新载入缓存
        String reloadedStatus = mockReadOrder("10086", cacheKey);
        assertEquals("PAID", reloadedStatus, "下一次读取必能拿到最新的数据库状态并回写缓存");

        redisTemplate.delete(cacheKey);
    }

    // 辅助模拟方法
    private String mockQueryDatabase(String id) {
        if (id.contains("not_exist")) {
            return null;
        }
        return "DbData-" + id;
    }

    private void mockUpdateDatabaseOrder(String orderId, String status) {
        // 模拟数据库更新成功
    }

    private String mockReadOrder(String orderId, String cacheKey) {
        String val = redisTemplate.opsForValue().get(cacheKey);
        if (val == null) {
            // 查 DB 得到最新状态并写入缓存
            val = "PAID";
            redisTemplate.opsForValue().set(cacheKey, val, Duration.ofMinutes(5));
        }
        return val;
    }
}
