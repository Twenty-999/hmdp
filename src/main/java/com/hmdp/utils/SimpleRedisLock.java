package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 简单 Redis 锁，支持带过期时间的获取和持有者校验解锁。
 */
@Component
public class SimpleRedisLock {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 将持有者校验和删除放在同一次脚本执行中。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                            "return redis.call('del', KEYS[1]) " +
                            "else return 0 end",
                    Long.class
            );

    /**
     * 尝试获取锁，不等待、不重试。
     *
     * @param key 完整的锁 Key
     * @param owner 本次获取锁使用的唯一标识
     * @param timeoutSeconds 锁的有效时长，单位：秒，必须大于零
     * @return 获取成功返回 true，否则返回 false
     */
    public boolean tryLock(String key, String owner, long timeoutSeconds) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(
                        key,
                        owner,
                        timeoutSeconds,
                        TimeUnit.SECONDS
                );

        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 只删除仍然属于当前持有者的锁。
     *
     * @param key 完整的锁 Key
     * @param owner 获取锁时使用的唯一标识
     */
    public void unlock(String key, String owner) {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                owner
        );
    }
}