package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    // 商户逻辑过期缓存的 Key 前缀。
    public static final String CACHE_SHOP_LOGICAL_KEY = "cache:shop:logical:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    // 按点赞时间排序的用户集合
    public static final String BLOG_LIKED_TIME_KEY = "blog:liked:time:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    // 用户关注的人，使用 Set 保存
    public static final String FOLLOW_SET_KEY = "follows:";
    // 关注集合已加载的标记
    public static final String FOLLOW_READY_KEY = "follows:ready:";
    // 关注缓存有效期，单位：秒
    public static final long FOLLOW_CACHE_TTL = 60L;
}
