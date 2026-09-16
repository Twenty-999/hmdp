-- 三个 Redis Key，由 Java 按顺序传入
local stockKey = KEYS[1]
local buyersKey = KEYS[2]
local streamKey = KEYS[3]

-- 普通参数，由 Java 按顺序传入
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

-- 1. 检查库存数据是否已准备
local stockText = redis.call('GET', stockKey)
if not stockText then
    return 3
end

local stock = tonumber(stockText)
if not stock or stock < 0 or stock ~= math.floor(stock) then
    return redis.error_reply('invalid stock value')
end

-- 2. 检查用户是否已经获得资格
if redis.call('SISMEMBER', buyersKey, userId) == 1 then
    return 2
end

-- 3. 检查库存是否充足
if stock < 1 then
    return 1
end

-- 提前检查任务 Key 类型，避免写入阶段才发现类型冲突
local streamType = redis.call('TYPE', streamKey).ok
if streamType ~= 'none' and streamType ~= 'stream' then
    return redis.error_reply('invalid stream key type')
end

-- 4. 预扣库存
redis.call('DECR', stockKey)

-- 5. 记录已获得资格的用户
redis.call('SADD', buyersKey, userId)

-- 6. 写入待处理订单任务
redis.call('XADD', streamKey, '*',
    'orderId', orderId,
    'userId', userId,
    'voucherId', voucherId)

return 0