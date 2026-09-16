package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置连接单节点 Redis 的 Redisson 客户端。
 */
@Configuration
public class RedissonConfig {

    /**
     * 使用项目已有的 Redis 连接配置创建客户端。
     *
     * @param properties application.yaml 中的 Redis 配置
     * @return Redisson 客户端
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();

        SingleServerConfig server = config.useSingleServer()
                .setAddress(
                        "redis://" + properties.getHost()
                                + ":" + properties.getPort()
                )
                .setDatabase(properties.getDatabase());

        String password = properties.getPassword();
        if (password != null && !password.isEmpty()) {
            server.setPassword(password);
        }

        return Redisson.create(config);
    }
}