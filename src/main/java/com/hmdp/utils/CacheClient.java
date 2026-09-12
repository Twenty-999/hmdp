package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存工具，封装对象序列化及过期时间设置。
 */
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
}