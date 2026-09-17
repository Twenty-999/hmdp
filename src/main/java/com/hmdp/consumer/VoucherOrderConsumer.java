package com.hmdp.consumer;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 消费秒杀订单消息，在数据库事务提交后确认消息。
 * 当前版本用于本地单实例运行。
 */
@Slf4j
@Component
public class VoucherOrderConsumer {

    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP = "order-group";
    private static final String CONSUMER = "c1";
    private static final String DEAD_STREAM_KEY = "stream.orders.dead";
    private static final String RETRY_KEY = "stream.orders:retry:order-group";
    private static final long MAX_FAILURES = 3L;

    private static final DefaultRedisScript<Long> MOVE_TO_DEAD_SCRIPT;

    static {
        MOVE_TO_DEAD_SCRIPT = new DefaultRedisScript<>();
        MOVE_TO_DEAD_SCRIPT.setLocation(
                new ClassPathResource("move-order-to-dead.lua")
        );
        MOVE_TO_DEAD_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 每轮处理一条订单消息。
     * 下单失败时累计次数，成功提交后再确认消息。
     */
    @Scheduled(fixedDelay = 1000)
    public void consumeOrder() {
        MapRecord<String, Object, Object> record;

        // 第一阶段：读取消息
        try {
            List<MapRecord<String, Object, Object>> records =
                    readOne(ReadOffset.from("0"));

            if (records == null || records.isEmpty()) {
                records = readOne(ReadOffset.lastConsumed());
            }

            if (records == null || records.isEmpty()) {
                return;
            }

            record = records.get(0);
        } catch (Exception e) {
            // 尚未取得具体消息，不能给某条订单累计失败次数
            log.error("读取订单消息失败", e);
            return;
        }

        String messageId = record.getId().getValue();

        // 第二阶段：解析消息并创建数据库订单
        try {
            Map<Object, Object> values = record.getValue();

            Long orderId = readId(values, "orderId");
            Long userId = readId(values, "userId");
            Long voucherId = readId(values, "voucherId");

            voucherOrderService.createOrderFromMessage(
                    orderId, userId, voucherId
            );
        } catch (Exception e) {
            handleOrderFailure(messageId, e);
            return;
        }

        // 第三阶段：数据库事务已提交，确认消息
        try {
            Long acknowledged = stringRedisTemplate.opsForStream()
                    .acknowledge(STREAM_KEY, GROUP, record.getId());

            if (acknowledged == null) {
                throw new IllegalStateException("ACK 未返回结果");
            }

            // 返回 0 表示本次没有移除待确认记录，可能此前已确认
            if (acknowledged == 0L) {
                log.warn("消息已不在待确认列表，消息 ID：{}", messageId);
            }

            // 成功完成确认操作后，清理历史失败次数
            stringRedisTemplate.opsForHash().delete(
                    RETRY_KEY, messageId
            );
        } catch (Exception e) {
            // 订单已提交，不再累计下单失败次数
            log.error("订单已入库，但 ACK 或计数清理失败，消息 ID："
                    + messageId, e);
        }
    }

    /**
     * 按指定偏移量读取一条消息。
     *
     * @param offset 读取位置
     * @return 读取到的消息
     */
    private List<MapRecord<String, Object, Object>> readOne(
            ReadOffset offset) {

        return stringRedisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, offset)
        );
    }

    /**
     * 将消息字段转换为正整数 ID。
     *
     * @param values 消息内容
     * @param field 字段名称
     * @return 转换后的 ID
     */
    private Long readId(Map<Object, Object> values, String field) {
        Object value = values.get(field);

        if (value == null) {
            throw new IllegalArgumentException("消息缺少字段：" + field);
        }

        long id = Long.parseLong(value.toString());

        if (id <= 0) {
            throw new IllegalArgumentException("消息 ID 不合法：" + field);
        }

        return id;
    }

    /**
     * 累计订单处理失败次数，达到上限后转入异常队列。
     *
     * @param messageId 原消息 ID
     * @param cause 本次处理异常
     */
    private void handleOrderFailure(String messageId, Exception cause) {
        log.error("订单处理失败，消息 ID：" + messageId, cause);

        try {
            // 1. 为当前消息累计一次失败
            Long failures = stringRedisTemplate.opsForHash().increment(
                    RETRY_KEY, messageId, 1L
            );

            if (failures == null) {
                throw new IllegalStateException("失败计数未返回结果");
            }

            // 2. 尚未达到上限，不确认消息，留给下一轮重试
            if (failures < MAX_FAILURES) {
                log.warn("订单等待重试，消息 ID：{}，累计失败次数：{}",
                        messageId, failures);
                return;
            }

            // 完整异常保留在日志中，队列中记录异常类型
            String reason = cause.getClass().getSimpleName();

            // 3. 保存异常消息、确认原消息、清理计数
            Long moved = stringRedisTemplate.execute(
                    MOVE_TO_DEAD_SCRIPT,
                    Arrays.asList(
                            STREAM_KEY,
                            DEAD_STREAM_KEY,
                            RETRY_KEY
                    ),
                    GROUP,
                    messageId,
                    reason
            );

            if (Long.valueOf(1L).equals(moved)) {
                log.error("订单已转入异常队列，需要排查，消息 ID：{}",
                        messageId);
            } else if (Long.valueOf(0L).equals(moved)) {
                log.warn("原消息已不在待确认列表，无需转移，消息 ID：{}",
                        messageId);
            } else {
                throw new IllegalStateException("异常消息转移结果不合法");
            }
        } catch (Exception e) {
            // 转移未确认成功，不额外 ACK；下次仍可处理待确认消息
            log.error("失败计数或异常消息转移出错，消息 ID："
                    + messageId, e);
        }
    }
}