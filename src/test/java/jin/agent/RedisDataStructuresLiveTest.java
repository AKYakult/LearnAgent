package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段六第一课：Redis 5 大核心数据结构与面试实战 (RedisDataStructuresLiveTest)
 *
 * 覆盖考点：
 * 1. String：基本读写、原子自增计数器、SETNX 分布式锁雏形、SDS 底层原理
 * 2. Hash：对象属性存储、局部修改、双哈希表渐进式 rehash 原理
 * 3. List：双端队列与栈（FIFO / LIFO）、Quicklist 底层结构
 * 4. Set：去重集合、交集与差集计算（共同好友、标签）
 * 5. ZSet：有序集合（Score 分值）、排行榜、跳表（SkipList）原理
 */
@SpringBootTest
public class RedisDataStructuresLiveTest {

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

    // =========================================================================
    // 1. String (字符串)
    // 底层原理：SDS (Simple Dynamic String 简单动态字符串)
    // 面试要点：
    // - O(1) 获取长度 (内部维护 len 字段，避免 C 语言 O(N) 遍历)
    // - 二进制安全 (buf[] 可保存任意二进制数据，不以 '\0' 作为结束符)
    // - 空间预分配与惰性释放 (有效防止缓冲区溢出 Buffer Overflow)
    // =========================================================================
    @Test
    @DisplayName("1. String 数据结构：读写、原子自增与 SETNX 锁")
    void testStringDataStructure() {
        String key = "test:string:user:1001";
        
        // 1.1 基本存取与过期时间 (SETEX)
        redisTemplate.opsForValue().set(key, "Alice", Duration.ofSeconds(60));
        assertEquals("Alice", redisTemplate.opsForValue().get(key));

        // 1.2 原子计数器 (INCR) - 用于点赞量、浏览量、接口访问频次计数
        String counterKey = "test:string:counter:api_call";
        redisTemplate.delete(counterKey);
        Long count1 = redisTemplate.opsForValue().increment(counterKey);
        Long count2 = redisTemplate.opsForValue().increment(counterKey, 5);
        assertEquals(1L, count1);
        assertEquals(6L, count2);

        // 1.3 SETNX (SET if Not eXists) - 分布式锁雏形
        String lockKey = "test:string:lock:order:999";
        redisTemplate.delete(lockKey);
        Boolean acquired1 = redisTemplate.opsForValue().setIfAbsent(lockKey, "uuid-worker-1", Duration.ofSeconds(10));
        Boolean acquired2 = redisTemplate.opsForValue().setIfAbsent(lockKey, "uuid-worker-2", Duration.ofSeconds(10));
        
        assertTrue(Boolean.TRUE.equals(acquired1), "第一次获取锁必须成功");
        assertFalse(Boolean.TRUE.equals(acquired2), "重复获取同一把未释放的锁必须失败 (互斥)");

        // 清理
        redisTemplate.delete(List.of(key, counterKey, lockKey));
    }

    // =========================================================================
    // 2. Hash (哈希字典)
    // 底层原理：Dict (字典) + 渐进式 rehash / Listpack
    // 面试要点：
    // - 为什么用 Hash 存对象属性：只更新某个字段时无需全量序列化与反序列化，极大节省带宽与 CPU
    // - 渐进式 rehash：内部维护 ht[0] 和 ht[1] 两张哈希表，每次增删改查时顺带迁移一个槽位，
    //   平摊了扩容开销，彻底避免了单次扩容导致的服务器秒级卡顿
    // =========================================================================
    @Test
    @DisplayName("2. Hash 数据结构：用户信息局部修改与渐进式哈希")
    void testHashDataStructure() {
        String userKey = "test:hash:user:1002";
        redisTemplate.delete(userKey);

        // 2.1 存储对象各字段
        redisTemplate.opsForHash().put(userKey, "name", "Bob");
        redisTemplate.opsForHash().put(userKey, "role", "admin");
        redisTemplate.opsForHash().put(userKey, "login_count", "0");

        // 2.2 字段单独读取与局部递增
        assertEquals("Bob", redisTemplate.opsForHash().get(userKey, "name"));
        Long newCount = redisTemplate.opsForHash().increment(userKey, "login_count", 1);
        assertEquals(1L, newCount);

        // 2.3 获取整个 Hash
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(userKey);
        assertEquals(3, userMap.size());
        assertEquals("admin", userMap.get("role"));

        redisTemplate.delete(userKey);
    }

    // =========================================================================
    // 3. List (双向列表)
    // 底层原理：Quicklist (双向链表包裹内存紧凑的 listpack)
    // 面试要点：
    // - LPUSH + RPOP = 经典先进先出 (FIFO) 消息队列
    // - LPUSH + LPOP = 栈 (LIFO)
    // - 常用场景：用户时间线、最新评论流、分页获取最新 N 条记录
    // =========================================================================
    @Test
    @DisplayName("3. List 数据结构：双端队列操作与范围切片")
    void testListDataStructure() {
        String queueKey = "test:list:queue:tasks";
        redisTemplate.delete(queueKey);

        // 3.1 左侧推入数据 (Task-A, Task-B, Task-C)
        redisTemplate.opsForList().leftPush(queueKey, "Task-A");
        redisTemplate.opsForList().leftPush(queueKey, "Task-B");
        redisTemplate.opsForList().leftPush(queueKey, "Task-C");

        // 列表内当前顺序为: [Task-C, Task-B, Task-A]
        Long size = redisTemplate.opsForList().size(queueKey);
        assertEquals(3L, size);

        // 3.2 范围获取 (LRANGE 0 1)
        List<String> top2 = redisTemplate.opsForList().range(queueKey, 0, 1);
        assertNotNull(top2);
        assertEquals(2, top2.size());
        assertEquals("Task-C", top2.get(0));

        // 3.3 右侧弹出 (RPOP) 实现 FIFO 队列
        String firstIn = redisTemplate.opsForList().rightPop(queueKey);
        assertEquals("Task-A", firstIn, "FIFO: 最先放入的 Task-A 应该最先出队");

        redisTemplate.delete(queueKey);
    }

    // =========================================================================
    // 4. Set (无序去重集合)
    // 底层原理：Intset (整数集合) 或 HashTable
    // 面试要点：
    // - 元素天然去重，O(1) 判断某个元素是否存在 (SISMEMBER)
    // - 集合运算：交集 (SINTER)、并集 (SUNION)、差集 (SDIFF)
    // - 典型场景：共同好友、抽奖去重、用户标签匹配
    // =========================================================================
    @Test
    @DisplayName("4. Set 数据结构：去重与集合交集运算 (共同兴趣/好友)")
    void testSetDataStructure() {
        String userAInterests = "test:set:user:A:tags";
        String userBInterests = "test:set:user:B:tags";
        redisTemplate.delete(List.of(userAInterests, userBInterests));

        // 用户 A 的兴趣标签
        redisTemplate.opsForSet().add(userAInterests, "Java", "Spring", "AI", "Docker");
        // 用户 B 的兴趣标签
        redisTemplate.opsForSet().add(userBInterests, "Python", "AI", "Docker", "PyTorch");

        // 4.1 去重验证 (重复添加同一标签不会增加元素个数)
        redisTemplate.opsForSet().add(userAInterests, "Java");
        assertEquals(4L, redisTemplate.opsForSet().size(userAInterests));

        // 4.2 成员判断
        assertTrue(Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(userAInterests, "Java")));
        assertFalse(Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(userAInterests, "Python")));

        // 4.3 计算两者共同兴趣 (交集 SINTER: ["AI", "Docker"])
        Set<String> commonTags = redisTemplate.opsForSet().intersect(userAInterests, userBInterests);
        assertNotNull(commonTags);
        assertEquals(2, commonTags.size());
        assertTrue(commonTags.contains("AI"));
        assertTrue(commonTags.contains("Docker"));

        redisTemplate.delete(List.of(userAInterests, userBInterests));
    }

    // =========================================================================
    // 5. ZSet (有序集合 Sorted Set)
    // 底层原理：跳表 (SkipList) + Listpack
    // 面试高频必考题：
    // 问：为什么 ZSet 底层用跳表而不用红黑树？
    // 答：
    // 1. 范围查询效率更高：跳表在通过多层索引定位到范围下界后，直接在最底层的有序双向链表
    //    顺序往后扫描即可 (ZRANGEBYSCORE)；红黑树需要进行复杂的中序遍历。
    // 2. 实现更简洁、无锁并发友好：红黑树在插入与平衡时需要复杂的旋转 (Rotate) 操作；
    //    跳表只需通过随机层高和局部指针修改，维护成本显著低于平衡树。
    // 常用场景：积分排行榜、热搜榜、延时任务 (将时间戳作为 Score)、滑动窗口接口限流
    // =========================================================================
    @Test
    @DisplayName("5. ZSet 数据结构：排行榜打分、反向切片与跳表机制")
    void testZSetDataStructure() {
        String leaderBoardKey = "test:zset:leaderboard:game";
        redisTemplate.delete(leaderBoardKey);

        // 5.1 录入玩家与初始分值 (Score)
        redisTemplate.opsForZSet().add(leaderBoardKey, "Player-Alice", 100.0);
        redisTemplate.opsForZSet().add(leaderBoardKey, "Player-Bob", 85.0);
        redisTemplate.opsForZSet().add(leaderBoardKey, "Player-Charlie", 95.0);

        // 5.2 动态更新玩家分值 (ZINCRBY)
        Double newScore = redisTemplate.opsForZSet().incrementScore(leaderBoardKey, "Player-Bob", 20.0); // 85 + 20 = 105
        assertEquals(105.0, newScore);

        // 5.3 获取前 3 名玩家排行榜 (按分值降序 ZREVRANGE 0 2)
        Set<ZSetOperations.TypedTuple<String>> top3 = 
                redisTemplate.opsForZSet().reverseRangeWithScores(leaderBoardKey, 0, 2);

        assertNotNull(top3);
        List<ZSetOperations.TypedTuple<String>> list = top3.stream().toList();
        assertEquals(3, list.size());

        // 冠军应该是 105 分的 Bob
        assertEquals("Player-Bob", list.get(0).getValue());
        assertEquals(105.0, list.get(0).getScore());

        // 亚军应该是 100 分的 Alice
        assertEquals("Player-Alice", list.get(1).getValue());
        assertEquals(100.0, list.get(1).getScore());

        // 季军应该是 95 分的 Charlie
        assertEquals("Player-Charlie", list.get(2).getValue());
        assertEquals(95.0, list.get(2).getScore());

        redisTemplate.delete(leaderBoardKey);
    }
}
