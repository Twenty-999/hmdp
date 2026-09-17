package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    /**
     * 新增秒杀优惠券，并在数据库事务提交后初始化 Redis 库存。
     *
     * @param voucher 优惠券信息，包含库存和活动时间
     */
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 1. 校验秒杀参数
        if (voucher.getStock() == null || voucher.getStock() <= 0) {
            throw new IllegalArgumentException("库存必须大于 0");
        }

        if (voucher.getBeginTime() == null
                || voucher.getEndTime() == null
                || !voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            throw new IllegalArgumentException("活动开始时间必须早于结束时间");
        }

        // 2. 保存优惠券基本信息
        if (!save(voucher)) {
            throw new IllegalStateException("优惠券保存失败");
        }

        // 3. 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());

        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀信息保存失败");
        }

        // 4. 提前准备 Redis 库存的键和值
        Long voucherId = voucher.getId();
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String stockValue = String.valueOf(voucher.getStock());

        // 5. 注册回调，数据库事务提交成功后再执行
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Boolean initialized = stringRedisTemplate
                                    .opsForValue()
                                    .setIfAbsent(stockKey, stockValue);

                            if (!Boolean.TRUE.equals(initialized)) {
                                throw new IllegalStateException("库存键已存在");
                            }
                        } catch (RuntimeException e) {
                            throw new IllegalStateException(
                                    "优惠券已保存，但 Redis 库存初始化失败，券 ID："
                                            + voucherId,
                                    e
                            );
                        }
                    }
                }
        );
    }
}
