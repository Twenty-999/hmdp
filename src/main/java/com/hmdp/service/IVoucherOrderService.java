package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 为当前登录用户创建秒杀订单。
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 下单成功时返回订单 ID，否则返回失败原因
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 根据消息创建订单。
     *
     * @param orderId 订单 ID
     * @param userId 用户 ID
     * @param voucherId 优惠券 ID
     */
    void createOrderFromMessage(Long orderId, Long userId, Long voucherId);
}
