package com.hmdp.init;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * 按商户类型将坐标加载到 Redis GEO。
 * 仅在显式开启初始化配置时执行。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "hmdp.geo.init-enabled",
        havingValue = "true"
)
public class ShopGeoInitializer implements ApplicationRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 应用启动时加载商户坐标。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        // 1. 只查询构建地理位置索引需要的字段
        List<Shop> shops = shopService.query()
                .select("id", "type_id", "x", "y")
                .list();

        int loaded = 0;
        int skipped = 0;

        for (Shop shop : shops) {
            Double longitude = shop.getX();
            Double latitude = shop.getY();

            // 2. 检查分类和坐标是否有效
            if (shop.getTypeId() == null
                    || longitude == null
                    || latitude == null
                    || !Double.isFinite(longitude)
                    || !Double.isFinite(latitude)
                    || longitude < -180 || longitude > 180
                    || latitude < -85.05112878
                    || latitude > 85.05112878) {

                log.warn("跳过分类或坐标无效的商户，ID：{}", shop.getId());
                skipped++;
                continue;
            }

            // 3. 不同类型的商户放入不同 GEO 集合
            String key = SHOP_GEO_KEY + shop.getTypeId();

            // 4. 保存经纬度，成员为商户 ID
            stringRedisTemplate.opsForGeo().add(
                    key,
                    new Point(longitude, latitude),
                    shop.getId().toString()
            );

            loaded++;
        }

        log.info("商户 GEO 加载完成，已处理：{}，已跳过：{}",
                loaded, skipped);
    }
}