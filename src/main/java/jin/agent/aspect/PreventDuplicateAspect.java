package jin.agent.aspect;

import jin.agent.annotation.PreventDuplicate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 防重复提交切面 (PreventDuplicateAspect)
 *
 * 核心机制（面试加分项）：
 * 1. 利用 Spring SpEL 动态解析方法入参生成业务唯一 LockKey（例如根据 conversationId 隔离）
 * 2. 通过 RedissonClient.getLock(lockKey) 获取分布式可重入锁 RLock
 * 3. 区分是否开启看门狗：
 *    - 当 leaseTime == -1 时，使用 lock.tryLock(waitTime, TimeUnit.SECONDS)，Redisson
 *      自动启动后台 Watchdog 线程，每隔 lockWatchdogTimeout/3 时间进行一次续期，永不提前失效
 *    - 当加锁失败（高并发重复请求）时，直接抛出异常拦截阻断
 * 4. 在 finally 块中执行 lock.isHeldByCurrentThread() 校验，确保只释放自己持有的锁，
 *    彻底规避“业务超时后误删他人所持有的锁”的经典并发安全隐患！
 */
@Aspect
@Component
public class PreventDuplicateAspect {

    private static final Logger log = LoggerFactory.getLogger(PreventDuplicateAspect.class);

    private final RedissonClient redissonClient;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    public PreventDuplicateAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Around("@annotation(preventDuplicate)")
    public Object around(ProceedingJoinPoint joinPoint, PreventDuplicate preventDuplicate) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 1. 动态解析业务 Key
        String dynamicKey = parseSpelKey(preventDuplicate.key(), method, joinPoint.getArgs());
        String lockKey = preventDuplicate.prefix() + (dynamicKey.isBlank() ? method.getName() : dynamicKey);

        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            long waitTime = preventDuplicate.waitTime();
            long leaseTime = preventDuplicate.leaseTime();

            // 2. 尝试获取分布式锁
            if (leaseTime > 0) {
                // 固定持有时间，明确指定租期（不走看门狗）
                locked = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            } else {
                // 默认走 Redisson 看门狗 (Watchdog) 自动续期
                locked = lock.tryLock(waitTime, TimeUnit.SECONDS);
            }

            if (!locked) {
                log.warn("防重复提交拦截触发: LockKey={}", lockKey);
                throw new IllegalStateException(preventDuplicate.message());
            }

            log.debug("成功获取分布式锁: LockKey={}", lockKey);
            // 3. 执行目标业务逻辑
            return joinPoint.proceed();

        } finally {
            // 4. 安全释放锁：必须先验证当前锁是否仍由本线程持有
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("成功释放分布式锁: LockKey={}", lockKey);
            }
        }
    }

    private String parseSpelKey(String spelExpression, Method method, Object[] args) {
        if (spelExpression == null || spelExpression.isBlank()) {
            return "";
        }
        String[] paramNames = discoverer.getParameterNames(method);
        if (paramNames == null || paramNames.length == 0) {
            return spelExpression;
        }

        EvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }

        try {
            Expression expression = parser.parseExpression(spelExpression);
            Object value = expression.getValue(context);
            return value != null ? value.toString() : "";
        } catch (Exception e) {
            log.warn("SpEL 表达式解析失败 [{}], 回退为原字符", spelExpression, e);
            return spelExpression;
        }
    }
}
