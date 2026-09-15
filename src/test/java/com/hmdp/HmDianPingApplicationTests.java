package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;
    @Resource
    private RedisIdWorker redisIdWorker;

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
}