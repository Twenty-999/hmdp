package com.hmdp;

import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    /**
     * 手动预热商户 1，设置 20 秒的逻辑有效期。
     */
    @Test
    @Disabled("仅在手动预热时临时移除此注解")
    void preheatShop() {
        shopService.saveShopWithLogicalExpire(1L, 20L);
    }
}