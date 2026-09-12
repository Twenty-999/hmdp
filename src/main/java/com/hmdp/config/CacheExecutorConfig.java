package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 配置缓存重建使用的后台线程池。
 */
@Configuration
public class CacheExecutorConfig {

    /**
     * 创建缓存重建线程池。
     *
     * @return 由 Spring 管理生命周期的线程池
     */
    @Bean
    public ThreadPoolTaskExecutor cacheRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("cache-rebuild-");
        return executor;
    }
}