-- KEYS[1]：原订单队列
-- KEYS[2]：异常订单队列
-- KEYS[3]：重试次数 Hash
local sourceKey = KEYS[1]
local deadKey = KEYS[2]
local retryKey = KEYS[3]

-- ARGV[1]：消费者组
-- ARGV[2]：原消息 ID
-- ARGV[3]：失败原因
local group = ARGV[1]
local messageId = ARGV[2]
local reason = ARGV[3]

-- 1. 检查原消息是否仍在待确认列表
local pending = redis.call(
    'XPENDING', sourceKey, group,
    messageId, messageId, 1
)

if #pending == 0 then
    return 0
end

-- 2. 读取原始消息，保留完整内容
local entries = redis.call(
    'XRANGE', sourceKey, messageId, messageId
)

if #entries == 0 then
    return redis.error_reply('original message missing')
end

-- 3. 写入前检查异常队列类型
local deadType = redis.call('TYPE', deadKey).ok

if deadType ~= 'none' and deadType ~= 'stream' then
    return redis.error_reply('invalid dead stream type')
end

-- HGET 也会检查重试键是否为 Hash
local retries = redis.call('HGET', retryKey, messageId) or '0'

-- 将原始字段列表编码为 JSON，连格式错误的消息也完整保存
local payload = cjson.encode(entries[1][2])

-- 4. 保存异常消息及排查信息
redis.call('XADD', deadKey, '*',
    'sourceStream', sourceKey,
    'sourceGroup', group,
    'sourceMessageId', messageId,
    'payload', payload,
    'reason', reason,
    'retries', retries)

-- 5. 异常消息保存后，确认原消息
redis.call('XACK', sourceKey, group, messageId)

-- 6. 清理原消息的失败次数
redis.call('HDEL', retryKey, messageId)

return 1