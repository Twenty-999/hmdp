package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 使用时间信息和 Redis 自增序号生成业务 ID。
 */
@Component
public class RedisIdWorker {

    /** 起始时间：2022-01-01 00:00:00 UTC，单位：秒。 */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /** ID 低位中为自增序号预留的位数。 */
    private static final int COUNT_BITS = 32;

    /** 按 UTC 日期划分计数器。 */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy:MM:dd")
                    .withZone(ZoneOffset.UTC);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成指定业务下的 ID。
     *
     * @param keyPrefix 业务标识，例如 order；同类业务必须使用相同标识
     * @return 由相对时间和自增序号组合而成的正数 ID
     * @throws IllegalArgumentException 业务标识为空时抛出
     * @throws IllegalStateException 时间或序号超出可用范围时抛出
     */
    public long nextId(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("业务标识不能为空");
        }

        // 1. 使用同一个时刻计算时间信息和计数器日期
        Instant now = Instant.now();
        long timestamp = now.getEpochSecond() - BEGIN_TIMESTAMP;

        if (timestamp < 0 || timestamp > Integer.MAX_VALUE) {
            throw new IllegalStateException("时间超出 ID 生成范围");
        }

        // 2. 按业务和 UTC 日期获取自增序号
        String date = DATE_FORMATTER.format(now);
        String key = "icr:" + keyPrefix + ":" + date;
        Long count = stringRedisTemplate.opsForValue().increment(key);

        if (count == null || count <= 0 || count > 0xFFFFFFFFL) {
            throw new IllegalStateException("自增序号超出 ID 生成范围");
        }

        // 3. 高位保存时间信息，低 32 位保存序号
        return (timestamp << COUNT_BITS) | count;
    }
}