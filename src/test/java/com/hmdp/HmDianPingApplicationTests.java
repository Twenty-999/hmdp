package com.hmdp;

import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IVoucherService voucherService;

    /**
     * 手动预热商户 1，设置 20 秒的逻辑有效期。
     */
    @Test
    @Disabled("仅在手动预热时临时移除此注解")
    void preheatShop() {
        shopService.saveShopWithLogicalExpire(1L, 20L);
    }

    /**
     * 验证同一业务连续生成的 ID 为正数且不重复。
     */
    @Test
    void testRedisIdWorker() {
        long first = redisIdWorker.nextId("learning-order");
        long second = redisIdWorker.nextId("learning-order");

        System.out.println("第一个 ID：" + first);
        System.out.println("第二个 ID：" + second);

        assertTrue(first > 0);
        assertTrue(second > 0);
        assertNotEquals(first, second);
    }

    /**
     * 验证当前线程可以获取并释放 Redisson 锁。
     */
    @Test
    void testRedissonLock() {
        RLock lock = redissonClient.getLock("learning:redisson:lock");

        boolean acquired = lock.tryLock();
        assertTrue(acquired, "未获取到测试锁，请检查是否有其他测试占用");

        try {
            assertTrue(lock.isHeldByCurrentThread());
            System.out.println("拿到锁，开始执行业务");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 手动观察看门狗续期：持锁超过默认的 30 秒。
     *
     * @throws InterruptedException 模拟业务等待时被中断
     */
    @Test
    @Disabled("手动观察续期时临时移除此注解")
    void testLockWatchdog() throws InterruptedException {
        RLock lock = redissonClient.getLock("learning:watchdog:lock");

        boolean acquired = lock.tryLock();
        assertTrue(acquired, "未获取到测试锁");

        try {
            for (int i = 1; i <= 8; i++) {
                Thread.sleep(5000);

                System.out.println(
                        "已等待 " + i * 5 + " 秒，锁剩余有效期："
                                + lock.remainTimeToLive() + " 毫秒"
                );
            }

            assertTrue(
                    lock.isHeldByCurrentThread(),
                    "等待 40 秒后，当前线程应仍然持有锁"
            );
        } finally {
            lock.unlock();
        }
    }

    /**
     * 验证同一线程可以重复获取同一把锁，并逐次释放。
     */
    @Test
    void testReentrantLock() {
        RLock lock = redissonClient.getLock("learning:reentrant:lock");

        boolean first = lock.tryLock();
        assertTrue(first, "第一次获取锁失败");

        try {
            assertEquals(1, lock.getHoldCount());

            // 当前测试线程再次获取同一把锁
            boolean second = lock.tryLock();
            assertTrue(second, "同一线程应当可以重入");

            try {
                assertEquals(2, lock.getHoldCount());
                System.out.println("获取两次后，持有次数：" + lock.getHoldCount());
            } finally {
                // 释放内层这一次获取
                lock.unlock();
            }

            assertEquals(1, lock.getHoldCount());
            assertTrue(lock.isHeldByCurrentThread());

            System.out.println("释放一次后，持有次数：" + lock.getHoldCount());
        } finally {
            // 释放外层这一次获取
            lock.unlock();
        }

        assertFalse(lock.isHeldByCurrentThread());
    }

    /**
     * 手动验证 Lua 预扣库存、限制重复购买并写入订单任务。
     */
    @Test
    @Disabled("操作练习数据，手动运行时临时移除此注解")
    void testLearningSeckill() {
        // 加载 resources 下的 Lua 文件
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("learning-seckill.lua"));
        script.setResultType(Long.class);

        List<String> keys = Arrays.asList(
                "learning:seckill:stock:demo1",
                "learning:seckill:buyers:demo1",
                "learning:seckill:orders:demo1"
        );

        String orderId = String.valueOf(
                redisIdWorker.nextId("learning-order")
        );

        Long result = stringRedisTemplate.execute(
                script,
                keys,
                "1010",
                "demo1",
                orderId
        );

        System.out.println("脚本返回码：" + result);
        System.out.println("本次尝试使用的订单 ID：" + orderId);
    }

    /**
     * 读取一条练习任务，打印内容后确认。
     * 优先处理 c1 自己的待确认任务，再领取新任务。
     */
    @Test
    @Disabled("手动练习消息消费时临时移除此注解")
    void testConsumeLearningOrder() {
        String streamKey = "learning:seckill:orders:demo1";
        String group = "learning-order-group";
        String consumerName = "c1";

        // 1. 优先读取当前消费者已领取但尚未确认的消息
        List<MapRecord<String, Object, Object>> records =
                stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(
                                streamKey,
                                ReadOffset.from("0")
                        )
                );

        // 2. 没有待确认消息时，再领取新消息，最多阻塞等待 2 秒
        if (records == null || records.isEmpty()) {
            records = stringRedisTemplate.opsForStream().read(
                    Consumer.from(group, consumerName),
                    StreamReadOptions.empty()
                            .count(1)
                            .block(Duration.ofSeconds(2)),
                    StreamOffset.create(
                            streamKey,
                            ReadOffset.lastConsumed()
                    )
            );
        }

        if (records == null || records.isEmpty()) {
            System.out.println("当前没有可处理的练习任务");
            return;
        }

        // 3. 读取消息 ID 和消息内容
        MapRecord<String, Object, Object> record = records.get(0);
        Map<Object, Object> values = record.getValue();

        // 4. 模拟业务处理：这里只打印，不创建真实订单
        System.out.println("Stream 消息 ID：" + record.getId());
        System.out.println("订单 ID：" + values.get("orderId"));
        System.out.println("用户 ID：" + values.get("userId"));
        System.out.println("练习活动：" + values.get("voucherId"));

        // 5. 模拟处理完成后，确认这条消息
        Long acknowledged = stringRedisTemplate.opsForStream()
                .acknowledge(streamKey, group, record.getId());

        assertEquals(Long.valueOf(1L), acknowledged);
    }

    /**
     * 创建一张用于异步下单练习的秒杀券。
     * 每次执行都会新增数据，仅需手动执行一次。
     */
    @Test
    @Disabled("会新增真实数据库记录，手动创建时临时移除此注解")
    void createAsyncLearningVoucher() {
        LocalDateTime now = LocalDateTime.now();

        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("异步下单练习券");
        voucher.setSubTitle("库存 2 张，每人限购一张");
        voucher.setRules("仅用于本地学习");
        voucher.setPayValue(8000L);
        voucher.setActualValue(10000L);
        voucher.setType(1);
        voucher.setStatus(1);

        voucher.setStock(2);

        // 先安排在一小时后开始，给后续接线和库存初始化留出时间
        voucher.setBeginTime(now.plusHours(1));
        voucher.setEndTime(now.plusDays(1));

        voucherService.addSeckillVoucher(voucher);

        assertNotNull(voucher.getId());
        System.out.println("新秒杀券 ID：" + voucher.getId());
    }
}