package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 商户业务实现，提供商户详情的缓存查询。
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ThreadPoolTaskExecutor cacheRebuildExecutor;
    @Resource
    private CacheClient cacheClient;

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
     * 查询商户详情，通过缓存工具控制缓存重建。
     *
     * @param id 商户 ID
     * @return 商户详情、商户不存在或请求失败的结果
     */
    @Override
    public Result queryById(Long id) {
        try {
            Shop shop = cacheClient.queryWithMutex(
                    CACHE_SHOP_KEY,
                    LOCK_SHOP_KEY,
                    id,
                    Shop.class,
                    this::getById,
                    CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5),
                    TimeUnit.MINUTES
            );

            if (shop == null) {
                return Result.fail("商户不存在！");
            }

            return Result.ok(shop);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("请求已中断，请重试！");
        } catch (TimeoutException e) {
            return Result.fail("系统繁忙，请稍后重试！");
        }
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
        // 删除两种格式的缓存，让后续查询重新加载数据
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        stringRedisTemplate.delete(CACHE_SHOP_LOGICAL_KEY + id);

        return Result.ok();
    }

    /**
     * 将商户数据与逻辑过期时间一起写入 Redis。
     *
     * @param id 商户 ID
     * @param expireSeconds 数据保持新鲜的时长，单位：秒
     * @throws IllegalArgumentException 参数不合法或商户不存在时抛出
     */
    @Override
    public void saveShopWithLogicalExpire(Long id, long expireSeconds) {
        if (id == null || expireSeconds <= 0) {
            throw new IllegalArgumentException("商户 ID 不能为空，有效时长必须大于零");
        }

        // 1. 从数据库加载完整商户信息
        Shop shop = getById(id);
        if (shop == null) {
            throw new IllegalArgumentException("商户不存在，无法预热");
        }

        // 将商户信息和逻辑过期时间一起写入缓存
        cacheClient.setWithLogicalExpire(
                CACHE_SHOP_LOGICAL_KEY + id,
                shop,
                expireSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * 优先读取逻辑过期缓存，过期时尝试提交后台刷新任务。
     *
     * @param id 商户 ID
     * @return 商户详情；没有逻辑缓存时使用原有查询流程
     */
    @Override
    public Result queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_LOGICAL_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 没有预热数据时，使用已有的互斥锁查询，不能直接判断商户不存在
        if (StrUtil.isBlank(json)) {
            return queryById(id);
        }

        // 取出包装对象及其中的商户数据
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean(
                JSONUtil.toJsonStr(redisData.getData()),
                Shop.class
        );

        // 逻辑过期时间还在未来，说明数据仍然有效
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return Result.ok(shop);
        }

        // 提交后台任务，当前请求不等待数据库查询完成
        try {
            cacheRebuildExecutor.execute(() -> rebuildLogicalCache(id));
        } catch (TaskRejectedException e) {
            log.debug("缓存重建线程繁忙，本次返回旧数据，商户 ID：{}", id);
        }

        return Result.ok(shop);
    }

    /**
     * 在后台获取重建锁，并刷新指定商户的逻辑缓存。
     *
     * @param id 商户 ID
     */
    private void rebuildLogicalCache(Long id) {
        String key = CACHE_SHOP_LOGICAL_KEY + id;
        String lockKey = LOCK_SHOP_KEY + "logical:" + id;
        String owner = UUID.randomUUID().toString();
        boolean locked = false;

        try {
            // 同一商户只允许锁持有者执行重建
            locked = tryLock(lockKey, owner);
            if (!locked) {
                return;
            }

            // 拿锁后重新检查，其他任务可能已经完成刷新
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return;
            }

            RedisData current = JSONUtil.toBean(json, RedisData.class);
            if (current.getExpireTime().isAfter(LocalDateTime.now())) {
                return;
            }

            Shop shop = getById(id);

            // 商户已被删除，清除缓存，让后续请求走普通查询
            if (shop == null) {
                stringRedisTemplate.delete(key);
                stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
                return;
            }

            // 刷新商户缓存，并重新设置 30 秒的逻辑有效期
            cacheClient.setWithLogicalExpire(
                    key,
                    shop,
                    30,
                    TimeUnit.SECONDS
            );

            log.debug("商户逻辑缓存重建完成，ID：{}", id);
        } catch (Exception e) {
            // 保留原缓存，后续访问还可以再次尝试重建
            log.error("商户逻辑缓存重建失败，ID：" + id, e);
        } finally {
            if (locked) {
                try {
                    unlock(lockKey, owner);
                } catch (Exception e) {
                    log.error("释放商户缓存锁失败，ID：" + id, e);
                }
            }
        }
    }

    /**
     * 按商户类型分页查询，有坐标时查询附近 5 公里内的商户。
     *
     * @param typeId 商户类型 ID
     * @param current 页码
     * @param x 用户经度
     * @param y 用户纬度
     * @return 商户列表，距离单位为米
     */
    @Override
    public Result queryShopByType(
            Integer typeId, Integer current, Double x, Double y) {

        if (typeId == null || typeId <= 0
                || current == null || current < 1) {
            return Result.fail("商户类型或页码不合法！");
        }

        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;

        // 1. 没有坐标，使用普通数据库分页
        if (x == null && y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .orderByAsc("id")
                    .page(new Page<>(current, pageSize));

            return Result.ok(page.getRecords());
        }

        // 2. 坐标必须成对提供，并处于合法范围
        if (x == null || y == null
                || !Double.isFinite(x) || !Double.isFinite(y)
                || x < -180 || x > 180
                || y < -85.05112878 || y > 85.05112878) {
            return Result.fail("请提供有效的经度和纬度！");
        }

        long from = (long) (current - 1) * pageSize;
        long end = (long) current * pageSize;

        // 3. 查询最近的前 end 个商户，并返回距离
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().radius(
                        SHOP_GEO_KEY + typeId,
                        new Circle(
                                new Point(x, y),
                                new Distance(5, Metrics.KILOMETERS)
                        ),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );

        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> locations =
                results.getContent();

        if (locations.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        // 4. 截取本页的商户 ID，并记录距离
        List<Long> ids = new ArrayList<>();
        Map<Long, Double> distanceMap = new HashMap<>();

        for (int i = (int) from; i < locations.size(); i++) {
            GeoResult<RedisGeoCommands.GeoLocation<String>> location =
                    locations.get(i);

            Long shopId = Long.valueOf(location.getContent().getName());
            ids.add(shopId);

            // 本次查询使用公里，返回给前端时转换为米
            distanceMap.put(shopId, location.getDistance().getValue() * 1000);
        }

        // 5. 批量查询商户详情
        List<Shop> shops = listByIds(ids);
        Map<Long, Shop> shopMap = new HashMap<>();

        for (Shop shop : shops) {
            shopMap.put(shop.getId(), shop);
        }

        // 6. 保留 Redis 返回的距离顺序
        List<Shop> orderedShops = new ArrayList<>();

        for (Long id : ids) {
            Shop shop = shopMap.get(id);

            // 跳过已删除或已经变更类型的商户
            if (shop == null
                    || !Long.valueOf(typeId.longValue()).equals(shop.getTypeId())) {
                continue;
            }

            shop.setDistance(distanceMap.get(id));
            orderedShops.add(shop);
        }

        return Result.ok(orderedShops);
    }
}