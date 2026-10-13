package jin.agent.annotation;

import java.lang.annotation.*;

/**
 * 接口防重复提交与幂等控制注解
 *
 * 核心设计：
 * 基于 Redisson 分布式锁，在方法执行前尝试获取以业务 Key 命名的锁。
 * 若已有相同 Key 的请求在执行中，则直接拦截并阻断，杜绝前端双击或网络重试引发的重复执行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PreventDuplicate {

    /**
     * 锁的唯一业务 Key 前缀
     */
    String prefix() default "lock:prevent_dup:";

    /**
     * 动态业务 Key 的 SpEL 表达式（例如: "#conversationId" 或 "#userId"）
     * 为空时默认以 "方法全限定名" 作为静态锁 Key
     */
    String key() default "";

    /**
     * 获取锁的最大等待时间（秒）。
     * 默认 0 秒：非阻塞模式，抢不到锁立即报错，典型用于前端防重复点击。
     */
    long waitTime() default 0;

    /**
     * 锁的最长租期（秒）。
     * 默认为 -1：启用 Redisson 看门狗 (Watchdog) 自动续期机制，直到业务执行完毕显式释放；
     * 若大于 0：则锁在固定时间后强制自动释放（关闭看门狗）。
     */
    long leaseTime() default -1;

    /**
     * 获取锁失败时的提示信息
     */
    String message() default "当前请求正在处理中，请勿重复提交！";
}
