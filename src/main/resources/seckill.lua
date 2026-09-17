-- Redis 键，由后端 Java 传入
local stockKey = KEYS[1]
local buyersKey = KEYS[2]
local streamKey = KEYS[3]

-- 业务参数，由后端 Java 传入
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

-- 活动开始、结束时间，单位为秒
local beginTime = tonumber(ARGV[4])
local endTime = tonumber(ARGV[5])

-- 1. 校验活动时间参数
if not beginTime or not endTime or beginTime >= endTime then
    return redis.error_reply('invalid activity time')
end

-- 使用 Redis 服务器的当前时间
local serverTime = redis.call('TIME')
local now = tonumber(serverTime[1])

if now < beginTime then
    return 4
end

if now >= endTime then
    return 5
end

-- 2. 检查库存是否已经初始化
local stockText = redis.call('GET', stockKey)

if not stockText then
    return 3
end

local stock = tonumber(stockText)

if not stock or stock < 0 or stock ~= math.floor(stock) then
    return redis.error_reply('invalid stock value')
end

-- 3. 检查当前用户是否已抢购
if redis.call('SISMEMBER', buyersKey, userId) == 1 then
    return 2
end

-- 4. 检查库存
if stock < 1 then
    return 1
end

-- 5. 提前检查消息队列的类型
local streamType = redis.call('TYPE', streamKey).ok

if streamType ~= 'none' and streamType ~= 'stream' then
    return redis.error_reply('invalid stream key type')
end

-- 6. 预扣 Redis 库存
redis.call('DECR', stockKey)

-- 7. 记录获得购买资格的用户
redis.call('SADD', buyersKey, userId)

-- 8. 将订单任务写入 Stream
redis.call('XADD', streamKey, '*',
    'orderId', orderId,
    'userId', userId,
    'voucherId', voucherId)

return 0