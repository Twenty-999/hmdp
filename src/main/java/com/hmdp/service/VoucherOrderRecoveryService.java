package com.hmdp.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工恢复异常订单。
 * 当前版本用于本地单实例，且订单尚未执行库存补偿的场景。
 */
@Slf4j
@Service
public class VoucherOrderRecoveryService {

    private static final String STREAM_KEY = "stream.orders.dead";
    private static final String GROUP = "recovery-group";
    private static final String CONSUMER = "recovery-c1";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 恢复一条异常订单，数据库处理成功后确认异常消息。
     *
     * @return 本次恢复结果
     */
    public synchronized Result recoverOne() {
        // 1. 优先读取当前恢复消费者尚未确认的消息
        List<MapRecord<String, Object, Object>> records =
                readOne(ReadOffset.from("0"));

        if (records == null || records.isEmpty()) {
            records = readOne(ReadOffset.lastConsumed());
        }

        if (records == null || records.isEmpty()) {
            return Result.ok("没有待恢复的订单");
        }

        MapRecord<String, Object, Object> record = records.get(0);
        String messageId = record.getId().getValue();

        // 2. 解析原始订单，并恢复数据库处理
        try {
            Object payload = record.getValue().get("payload");

            if (payload == null) {
                throw new IllegalArgumentException("异常消息缺少 payload");
            }

            Map<String, String> fields = parsePayload(payload.toString());

            Long orderId = Long.valueOf(fields.get("orderId"));
            Long userId = Long.valueOf(fields.get("userId"));
            Long voucherId = Long.valueOf(fields.get("voucherId"));

            // 沿用原订单 ID，不再次预扣 Redis 库存
            voucherOrderService.createOrderFromMessage(
                    orderId, userId, voucherId
            );
        } catch (Exception e) {
            log.error("异常订单恢复未确认成功，消息 ID：" + messageId, e);
            return Result.fail("恢复未确认成功，请查看日志，消息已保留");
        }

        // 3. 数据库事务成功返回后，确认异常队列中的消息
        try {
            Long acknowledged = stringRedisTemplate.opsForStream()
                    .acknowledge(STREAM_KEY, GROUP, record.getId());

            if (!Long.valueOf(1L).equals(acknowledged)) {
                return Result.fail("数据库处理已完成，但本次 ACK 未确认移除消息");
            }
        } catch (Exception e) {
            log.error("数据库处理已完成，但异常消息 ACK 失败，消息 ID："
                    + messageId, e);
            return Result.fail("数据库处理已完成，但 ACK 结果未确认");
        }

        return Result.ok("异常订单已恢复");
    }

    /**
     * 读取一条异常消息。
     *
     * @param offset 消息读取位置
     * @return 消息列表
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
     * 将交替存放字段名与字段值的 JSON 数组转换为 Map。
     *
     * @param payload 原始消息字段数组
     * @return 字段名与字段值的映射
     */
    private Map<String, String> parsePayload(String payload) {
        JSONArray array = JSONUtil.parseArray(payload);

        if (array.size() % 2 != 0) {
            throw new IllegalArgumentException("原始消息字段不成对");
        }

        Map<String, String> fields = new HashMap<>();

        for (int i = 0; i < array.size(); i += 2) {
            String key = array.getStr(i);
            String value = array.getStr(i + 1);

            if (key == null || value == null || fields.containsKey(key)) {
                throw new IllegalArgumentException("原始消息字段无效或重复");
            }

            fields.put(key, value);
        }

        return fields;
    }
}