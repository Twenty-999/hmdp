-- KEYS[1]：关注集合
-- KEYS[2]：已加载标记
-- ARGV[1]：缓存有效期，单位为秒
-- ARGV[2] 开始：关注的用户 ID

local ttl = tonumber(ARGV[1])

if not ttl or ttl <= 0 or ttl ~= math.floor(ttl) then
    return redis.error_reply('invalid cache ttl')
end

-- 清理之前的标记和集合
redis.call('DEL', KEYS[2], KEYS[1])

-- 写入本次从数据库查到的关注对象
for i = 2, #ARGV do
    redis.call('SADD', KEYS[1], ARGV[i])
end

-- 非空集合设置有效期；空集合不存在，EXPIRE 返回 0
redis.call('EXPIRE', KEYS[1], ttl)

-- 即使没有关注任何人，也保存已加载标记
redis.call('SET', KEYS[2], '1', 'EX', ttl)

return 1