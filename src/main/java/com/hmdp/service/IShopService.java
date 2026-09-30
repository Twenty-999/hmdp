package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据商户 ID 查询详情，优先读取 Redis 缓存。
     *
     * @param id 商户 ID
     * @return 商户详情；商户不存在时返回失败结果
     */
    Result queryById(Long id);

    /**
     * 更新商户信息，并删除对应的详情缓存。
     *
     * @param shop 待更新的商户数据，必须包含 ID
     * @return 更新成功或失败的结果
     */
    Result updateShop(Shop shop);

    /**
     * 查询商户并写入带逻辑过期时间的缓存。
     *
     * @param id 商户 ID
     * @param expireSeconds 数据保持新鲜的时长，单位：秒，必须大于零
     */
    void saveShopWithLogicalExpire(Long id, long expireSeconds);

    /**
     * 查询逻辑过期缓存，过期时触发后台重建并返回旧数据。
     *
     * @param id 商户 ID
     * @return 商户详情或查询失败结果
     */
    Result queryWithLogicalExpire(Long id);

    /**
     * 按类型分页查询商户，有坐标时按距离排序。
     *
     * @param typeId 商户类型 ID
     * @param current 页码，从 1 开始
     * @param x 用户经度，可为空
     * @param y 用户纬度，可为空
     * @return 商户列表，附近查询时包含距离，单位为米
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
