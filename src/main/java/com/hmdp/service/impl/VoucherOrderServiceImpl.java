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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

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
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 保存脚本配置，供每次抢购请求复用
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

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
     * 申请秒杀资格，预扣 Redis 库存并写入订单消息。
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 受理成功时返回订单 ID，否则返回失败提示
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券 ID 不合法！");
        }

        // 2. 查询活动信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("秒杀优惠券不存在！");
        }

        if (voucher.getBeginTime() == null
                || voucher.getEndTime() == null
                || !voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            return Result.fail("活动时间配置有误！");
        }

        // 3. 将数据库中的北京时间转换为秒级时间戳
        ZoneId activityZone = ZoneId.of("Asia/Shanghai");

        long beginTime = voucher.getBeginTime()
                .atZone(activityZone)
                .toEpochSecond();

        long endTime = voucher.getEndTime()
                .atZone(activityZone)
                .toEpochSecond();

        // 4. 提前生成订单 ID，后续消息和数据库使用同一个 ID
        long orderId = redisIdWorker.nextId("order");

        // 5. 执行 Lua：校验资格、预扣库存、写入订单消息
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + voucherId,
                        "seckill:buyers:" + voucherId,
                        "stream.orders"
                ),
                user.getId().toString(),
                voucherId.toString(),
                String.valueOf(orderId),
                String.valueOf(beginTime),
                String.valueOf(endTime)
        );

        if (result == null) {
            throw new IllegalStateException("秒杀脚本未返回结果");
        }

        // 6. 根据脚本结果返回提示
        if (result == 0L) {
            // 仅表示已受理，数据库订单由消费者异步创建
            return Result.ok(orderId);
        }

        if (result == 1L) {
            return Result.fail("库存不足！");
        }
        if (result == 2L) {
            return Result.fail("不能重复购买！");
        }
        if (result == 3L) {
            return Result.fail("活动库存尚未准备好！");
        }
        if (result == 4L) {
            return Result.fail("秒杀尚未开始！");
        }
        if (result == 5L) {
            return Result.fail("秒杀已经结束！");
        }

        throw new IllegalStateException("未知秒杀结果码：" + result);
    }

    /**
     * 根据消息创建订单，确保同一订单重复处理时不会重复扣减库存。
     *
     * @param orderId 订单 ID
     * @param userId 用户 ID
     * @param voucherId 优惠券 ID
     */
    @Override
    @Transactional
    public void createOrderFromMessage(
            Long orderId, Long userId, Long voucherId) {

        // 1. 校验消息中的必要参数
        if (orderId == null || userId == null || voucherId == null
                || orderId <= 0 || userId <= 0 || voucherId <= 0) {
            throw new IllegalArgumentException("订单消息参数不合法");
        }

        // 2. 判断这条订单消息是否已经处理成功
        VoucherOrder existingOrder = getById(orderId);

        if (existingOrder != null) {
            // 同一个订单 ID 必须对应同一个用户和优惠券
            if (!userId.equals(existingOrder.getUserId())
                    || !voucherId.equals(existingOrder.getVoucherId())) {
                throw new IllegalStateException("订单 ID 对应的信息不一致");
            }

            // 订单已经存在，本次无需再次扣库存或保存订单
            return;
        }

        // 3. 检查是否已有同一用户购买同一张券的其他订单
        Integer count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();

        if (count > 0) {
            throw new IllegalStateException("该用户已有其他订单，需要核对");
        }

        // 4. 扣减数据库库存，库存大于 0 才允许扣减
        boolean deducted = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!deducted) {
            throw new IllegalStateException("数据库库存扣减失败");
        }

        // 5. 使用消息里的信息保存订单
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(1);

        if (!save(order)) {
            throw new IllegalStateException("订单保存失败");
        }
    }
}