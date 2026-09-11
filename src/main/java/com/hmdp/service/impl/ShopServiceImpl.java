package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 商户业务实现，提供商户详情的缓存查询。
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

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
     * 尝试获取指定商户的缓存重建锁。
     *
     * @param lockKey 锁的 Redis Key
     * @param owner 本次获取锁使用的唯一标识
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
     * 释放属于当前持有者的锁。
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
     * 优先查询缓存，未命中时使用互斥锁控制缓存重建。
     * <p>
     * 未获取锁的请求短暂等待后重试，重试次数耗尽则返回繁忙提示。
     *
     * @param id 商户 ID
     * @return 商户详情、商户不存在或系统繁忙的结果
     */
    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;

        // 最多尝试 20 轮，避免请求无限等待
        for (int attempt = 0; attempt < 20; attempt++) {
            String shopJson = stringRedisTemplate.opsForValue().get(key);

            if (StrUtil.isNotBlank(shopJson)) {
                return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
            }

            if (shopJson != null) {
                return Result.fail("商户不存在！");
            }

            // 每次获取锁使用独立标识，供释放锁时核对
            String owner = UUID.randomUUID().toString();

            if (!tryLock(lockKey, owner)) {
                if (attempt == 19) {
                    break;
                }

                // 没拿到锁，等待其他请求重建，然后重新查缓存
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.fail("请求已中断，请重试！");
                }

                continue;
            }

            try {
                // 拿锁后再次检查，其他请求可能已经完成缓存重建
                shopJson = stringRedisTemplate.opsForValue().get(key);

                if (StrUtil.isNotBlank(shopJson)) {
                    return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
                }

                if (shopJson != null) {
                    return Result.fail("商户不存在！");
                }

                Shop shop = getById(id);

                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(
                            key,
                            "",
                            CACHE_NULL_TTL,
                            TimeUnit.MINUTES
                    );
                    return Result.fail("商户不存在！");
                }

                long ttl = CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5);
                stringRedisTemplate.opsForValue().set(
                        key,
                        JSONUtil.toJsonStr(shop),
                        ttl,
                        TimeUnit.MINUTES
                );

                return Result.ok(shop);
            } finally {
                // 正常返回或发生异常，都尝试释放自己的锁
                unlock(lockKey, owner);
            }
        }

        return Result.fail("系统繁忙，请稍后重试！");
    }

    /**
     * 更新数据库中的商户信息，成功后删除旧缓存。
     *
     * @param shop 待更新的商户数据，必须包含 ID
     * @return 更新成功或失败的结果
     */
    @Override
    public Result updateShop(Shop shop) {
        // 1. 必须指定要修改的商户
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商户 ID 不能为空！");
        }

        // 2. 更新数据库
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("商户更新失败！");
        }

        // 3. 删除旧缓存，让下一次查询从数据库加载最新数据
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}