package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.annotation.PreventDuplicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段六第三课：Redisson 分布式锁与看门狗实战测试 (RedissonDistributedLockLiveTest)
 *
 * 核心考点：
 * 1. SETNX 原始漏洞分析与 Redisson 底层演进
 * 2. Redisson 看门狗 (Watchdog) 自动续期原理与验证
 * 3. 基于注解 @PreventDuplicate 与 AOP 的方法级防重复提交实操
 */
@SpringBootTest
@Import(RedissonDistributedLockLiveTest.TestOrderService.class)
public class RedissonDistributedLockLiveTest {

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
    private RedissonClient redissonClient;

    @Autowired
    private TestOrderService testOrderService;

    // =========================================================================
    // 1. Redisson 分布式锁核心 API：互斥性与可重入性
    // 面试要点：
    // - Redisson 锁是基于 Redis Hash 实现的可重入锁 (记录持有锁的客户端 UUID + 线程 ID 及重入计数)
    // - tryLock(waitTime, leaseTime, unit) 支持等待排队与自旋
    // =========================================================================
    @Test
    @DisplayName("1. Redisson 锁互斥性：同一把锁同一时刻仅能被 1 个线程持有")
    void testLockMutualExclusion() throws Exception {
        String lockKey = "lock:test:order:1001";
        RLock lock = redissonClient.getLock(lockKey);

        // 主线程成功获取锁 (非阻塞，立即返回)
        boolean acquired = lock.tryLock(0, 5, TimeUnit.SECONDS);
        assertTrue(acquired, "首次获取锁必须成功");

        // 启动异步线程，尝试获取同一把锁
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = executor.submit(() -> {
            RLock otherThreadLock = redissonClient.getLock(lockKey);
            // 尝试非阻塞获取，预期获取失败 (互斥)
            return otherThreadLock.tryLock(0, 5, TimeUnit.SECONDS);
        });

        Boolean otherAcquired = future.get(3, TimeUnit.SECONDS);
        assertFalse(otherAcquired, "主线程持有锁期间，其他线程绝不能获取到该锁");

        // 主线程释放锁
        lock.unlock();

        // 再次由异步线程尝试获取，此时锁已释放，预期成功
        Future<Boolean> futureAfterRelease = executor.submit(() -> {
            RLock otherThreadLock = redissonClient.getLock(lockKey);
            boolean ok = otherThreadLock.tryLock(0, 5, TimeUnit.SECONDS);
            if (ok) {
                otherThreadLock.unlock();
            }
            return ok;
        });

        assertTrue(futureAfterRelease.get(3, TimeUnit.SECONDS), "主线程释放锁后，其他线程应能成功获取");
        executor.shutdown();
    }

    // =========================================================================
    // 2. Redisson 看门狗 (Watchdog) 自动续期原理
    // 面试要点：
    // - 痛点：业务执行较长，固定 TTL 锁提前失效被其他线程抢占引发并发问题。
    // - 原理：不指定 leaseTime（传入 -1）时，Redisson 启动后台定时任务 (Netty 时间轮 HashedWheelTimer)，
    //   每隔 lockWatchdogTimeout / 3 (即 10 秒) 自动将锁超时刷新重置回 30 秒！
    // =========================================================================
    @Test
    @DisplayName("2. Redisson 看门狗自动续期：长耗时任务下锁永不提前过期")
    void testWatchdogRenewal() throws Exception {
        String lockKey = "lock:test:watchdog:demo";
        RLock lock = redissonClient.getLock(lockKey);

        // 不指定 leaseTime，触发看门狗机制
        lock.lock();
        try {
            long initialTtl = lock.remainTimeToLive();
            assertTrue(initialTtl > 0 && initialTtl <= 30000, "初始 TTL 应该在 30 秒以内");

            // 休眠 11 秒（跨越了看门狗第 10 秒的续期阈值）
            Thread.sleep(11000);

            // 如果没有看门狗，经过 11 秒后 TTL 应该只剩 19 秒以下；
            // 但因为看门狗在第 10 秒触发了自动续期，此时 TTL 被重置回 20 秒以上！
            long renewedTtl = lock.remainTimeToLive();
            assertTrue(renewedTtl > 20000, "看门狗应自动刷新 TTL 回到 20 秒以上，实际为: " + renewedTtl);

        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // 3. 基于注解 @PreventDuplicate 的防重复提交实战
    // 场景：前端用户疯狂连击“提交订单”按钮，或弱网下产生重复 HTTP 请求
    // 预期：同一个业务 Key (如同一个订单号) 的并发请求，仅 1 个能进入业务逻辑，其余直接被 AOP 拦截抛出异常
    // =========================================================================
    @Test
    @DisplayName("3. @PreventDuplicate 注解防重复提交：同一 Key 并发阻断，不同 Key 并行通过")
    void testPreventDuplicateAnnotation() throws Exception {
        String orderA = "ORDER-2026-A";
        String orderB = "ORDER-2026-B";

        // 清理潜在残留 key
        redissonClient.getKeys().delete("lock:prevent_dup:" + orderA, "lock:prevent_dup:" + orderB);

        CountDownLatch thread1EnteredLatch = new CountDownLatch(1);
        CountDownLatch thread1HoldLatch = new CountDownLatch(1);

        AtomicReference<String> resultA1 = new AtomicReference<>();
        AtomicReference<Throwable> errorA2 = new AtomicReference<>();
        AtomicReference<String> resultB = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(3);

        // 线程 1：提交订单 A，并在持有锁期间通过 CountDownLatch 保持挂起
        pool.submit(() -> {
            try {
                resultA1.set(testOrderService.submitOrder(orderA, thread1EnteredLatch, thread1HoldLatch));
            } catch (Throwable t) {
                // 不应抛出
            }
        });

        // 主线程等待：严格确保线程 1 已经进入方法并拿到了分布式锁！
        boolean thread1Ready = thread1EnteredLatch.await(5, TimeUnit.SECONDS);
        assertTrue(thread1Ready, "线程 1 必须成功加锁并进入方法");

        // 线程 2：在线程 1 仍然持有锁期间，强行提交相同的订单 A -> 100% 触发防重复拦截！
        pool.submit(() -> {
            try {
                testOrderService.submitOrder(orderA, null, null);
            } catch (Throwable t) {
                errorA2.set(t);
            }
        });

        // 线程 3：并发提交订单 B（不同的 Key，预期互不干扰，直接成功通过）
        pool.submit(() -> {
            try {
                resultB.set(testOrderService.submitOrder(orderB, null, null));
            } catch (Throwable t) {
                // 不应抛出
            }
        });

        // 稍等线程 2 和线程 3 执行完毕
        Thread.sleep(200);

        // 放行线程 1，让其完成业务并正常释放分布式锁
        thread1HoldLatch.countDown();

        pool.shutdown();
        boolean finished = pool.awaitTermination(3, TimeUnit.SECONDS);
        assertTrue(finished);

        // 验证结果：
        assertEquals("SUCCESS:ORDER-2026-A", resultA1.get(), "线程 1 应该正常执行完成");
        assertEquals("SUCCESS:ORDER-2026-B", resultB.get(), "不同业务 Key 的订单 B 应该并行成功");

        assertNotNull(errorA2.get(), "重复提交订单 A 的线程 2 必须被拦截并抛出异常");
        assertInstanceOf(IllegalStateException.class, errorA2.get());
        assertEquals("订单处理中，请勿重复提交！", errorA2.get().getMessage());

        // 当线程 1 彻底完成后，再次提交订单 A，应该恢复正常
        String retryResult = testOrderService.submitOrder(orderA, null, null);
        assertEquals("SUCCESS:ORDER-2026-A", retryResult, "前序任务执行完毕释放锁后，新请求可以正常执行");
    }

    /**
     * 模拟被防重复提交切面保护的业务服务类
     */
    @Service
    public static class TestOrderService {

        @PreventDuplicate(key = "#orderId", message = "订单处理中，请勿重复提交！")
        public String submitOrder(String orderId, CountDownLatch enterLatch, CountDownLatch holdLatch) {
            if (enterLatch != null) {
                enterLatch.countDown(); // 告知测试：已成功通过 AOP 加锁并进入业务方法
            }
            if (holdLatch != null) {
                try {
                    holdLatch.await(5, TimeUnit.SECONDS); // 模拟长耗时业务执行过程
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "SUCCESS:" + orderId;
        }
    }
}
