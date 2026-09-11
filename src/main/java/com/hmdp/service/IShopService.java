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
}
