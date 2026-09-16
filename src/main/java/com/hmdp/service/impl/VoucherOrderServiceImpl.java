package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 秒杀订单业务，负责活动校验、库存扣减和订单保存。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private SimpleRedisLock simpleRedisLock;

    /**
     * 执行秒杀下单，由调用方提供用户锁和数据库事务。
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 下单结果
     */
    private Result createVoucherOrder(Long voucherId) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        // 2. 查询秒杀券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        if (voucher == null) {
            return Result.fail("秒杀优惠券不存在！");
        }

        // 3. 校验活动时间
        LocalDateTime now = LocalDateTime.now();

        if (now.isBefore(voucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始！");
        }

        if (!now.isBefore(voucher.getEndTime())) {
            return Result.fail("秒杀已经结束！");
        }

        // 4. 初步检查库存
        if (voucher.getStock() <= 0) {
            return Result.fail("库存不足！");
        }

        // 检查当前用户是否已购买本张优惠券
        int count = query()
                .eq("user_id", user.getId())
                .eq("voucher_id", voucherId)
                .count();

        if (count > 0) {
            return Result.fail("不能重复购买！");
        }

        // 5. 生成订单 ID
        long orderId = redisIdWorker.nextId("order");

        // 6. 只有数据库当前库存大于零时，才允许扣减
        boolean deducted = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!deducted) {
            return Result.fail("库存不足！");
        }

        // 7. 创建订单
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(user.getId());
        order.setVoucherId(voucherId);
        order.setStatus(1);

        if (!save(order)) {
            // 此时已经扣减库存，抛出异常让数据库事务回滚
            throw new IllegalStateException("订单保存失败");
        }

        return Result.ok(orderId);
    }

    /**
     * 使用 Redis 用户锁协调秒杀下单，在事务结束后释放锁。
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 下单结果或请求处理中提示
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        String lockKey = "lock:order:user:" + user.getId();
        String owner = UUID.randomUUID().toString();

        // 获取失败直接返回，不进入数据库事务
        boolean locked = simpleRedisLock.tryLock(lockKey, owner, 10);
        if (!locked) {
            return Result.fail("请求正在处理中，请稍后重试！");
        }

        try {
            TransactionTemplate transactionTemplate =
                    new TransactionTemplate(transactionManager);

            return transactionTemplate.execute(
                    status -> createVoucherOrder(voucherId)
            );
        } catch (DuplicateKeyException e) {
            // 数据库事务已回滚，再确认是否是重复购买
            int count = query()
                    .eq("user_id", user.getId())
                    .eq("voucher_id", voucherId)
                    .count();

            if (count > 0) {
                return Result.fail("不能重复购买！");
            }

            throw e;
        } finally {
            try {
                simpleRedisLock.unlock(lockKey, owner);
            } catch (Exception e) {
                // 解锁异常不能覆盖已经完成的下单结果，锁还有过期时间
                log.error("释放订单锁失败，key：" + lockKey, e);
            }
        }
    }
}