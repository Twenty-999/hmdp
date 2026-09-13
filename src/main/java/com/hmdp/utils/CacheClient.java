package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * Redis 缓存工具，封装对象序列化及过期时间设置。
 */
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 原子检查锁的持有者，并删除属于自己的锁。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                            "return redis.call('del', KEYS[1]) " +
                            "else return 0 end",
                    Long.class
            );

    /**
     * 尝试获取缓存重建锁。
     *
     * @param lockKey 锁的 Redis Key
     * @param owner 本次获取锁的唯一标识
     * @return 获取成功返回 true，否则返回 false
     */
    private boolean tryLock(String lockKey, String owner) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(
                        lockKey,
                        owner,
                        LOCK_SHOP_TTL,
                        TimeUnit.SECONDS
                );

        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 仅释放属于当前持有者的锁。
     *
     * @param lockKey 锁的 Redis Key
     * @param owner 获取锁时使用的唯一标识
     */
    private void unlock(String lockKey, String owner) {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                owner
        );
    }

    /**
     * 将对象转换为 JSON，并设置 Redis 自动过期时间。
     *
     * @param key 缓存 Key
     * @param value 待缓存的数据对象
     * @param time 有效时长
     * @param unit 时间单位
     */
    public void set(String key, Object value,
                    long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(value),
                time,
                unit
        );
    }

    /**
     * 将对象和逻辑过期时间一起保存，不设置 Redis 自动过期时间。
     *
     * @param key 缓存 Key
     * @param value 待缓存的数据对象
     * @param time 数据保持新鲜的时长
     * @param unit 时间单位，本方法按秒计算
     * @throws IllegalArgumentException 有效时长不足一秒时抛出
     */
    public void setWithLogicalExpire(String key, Object value,
                                     long time, TimeUnit unit) {
        long seconds = unit.toSeconds(time);
        if (seconds <= 0) {
            throw new IllegalArgumentException("逻辑有效时长至少为一秒");
        }

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(
                LocalDateTime.now().plusSeconds(seconds)
        );

        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(redisData)
        );
    }

    /**
     * 优先查询缓存，未命中时调用数据库查询方法。
     * <p>
     * 数据不存在时缓存空字符串，减少对不存在数据的重复查询。
     *
     * @param keyPrefix 缓存 Key 前缀
     * @param id 数据 ID
     * @param type 缓存数据对应的对象类型
     * @param dbFallback 缓存未命中时执行的数据库查询操作
     * @param time 正常数据的缓存有效时长
     * @param unit 正常数据缓存的时间单位
     * @param <R> 查询结果的类型
     * @return 查询到的对象；数据不存在时返回 null
     */
    public <R> R queryWithPassThrough(
            String keyPrefix,
            Long id,
            Class<R> type,
            Function<Long, R> dbFallback,
            long time,
            TimeUnit unit) {

        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 命中正常数据，按调用方指定的类型转换
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 2. 命中空值标记，直接返回不存在
        if (json != null) {
            return null;
        }

        // 3. 缓存未命中，执行调用方提供的数据库查询
        R data = dbFallback.apply(id);

        if (data == null) {
            // 空值标记直接写入，不经过 JSON 序列化
            stringRedisTemplate.opsForValue().set(
                    key,
                    "",
                    CACHE_NULL_TTL,
                    TimeUnit.MINUTES
            );
            return null;
        }

        // 4. 复用已有写入方法，缓存查询结果
        this.set(key, data, time, unit);

        return data;
    }

    /**
     * 优先读取缓存，未命中时通过互斥锁控制缓存重建。
     *
     * @param keyPrefix 缓存 Key 前缀
     * @param lockPrefix 锁 Key 前缀
     * @param id 数据 ID
     * @param type 查询结果的类型
     * @param dbFallback 缓存未命中时执行的数据库查询操作
     * @param time 正常数据的缓存有效时长
     * @param unit 正常数据缓存的时间单位
     * @param <R> 返回对象的类型
     * @return 查询结果；数据不存在时返回 null
     * @throws InterruptedException 等待重试期间线程被中断时抛出
     * @throws TimeoutException 重试次数耗尽仍未获得结果时抛出
     */
    public <R> R queryWithMutex(
            String keyPrefix,
            String lockPrefix,
            Long id,
            Class<R> type,
            Function<Long, R> dbFallback,
            long time,
            TimeUnit unit)
            throws InterruptedException, TimeoutException {

        String key = keyPrefix + id;
        String lockKey = lockPrefix + id;

        for (int attempt = 0; attempt < 20; attempt++) {
            // 1. 缓存中已有结果时，无需获取锁
            String json = stringRedisTemplate.opsForValue().get(key);

            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }

            if (json != null) {
                return null;
            }

            // 2. 缓存未命中，尝试获取锁
            String owner = UUID.randomUUID().toString();

            if (!tryLock(lockKey, owner)) {
                if (attempt == 19) {
                    break;
                }

                Thread.sleep(50);
                continue;
            }

            try {
                // 3. 复用防穿透查询，它会再次检查缓存
                return queryWithPassThrough(
                        keyPrefix,
                        id,
                        type,
                        dbFallback,
                        time,
                        unit
                );
            } finally {
                // 4. 无论正常返回还是抛出异常，都尝试释放自己的锁
                unlock(lockKey, owner);
            }
        }

        throw new TimeoutException("缓存查询重试次数已耗尽");
    }
}