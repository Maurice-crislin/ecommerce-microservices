
local available = redis.call('GET', KEYS[1]) or 0
local locked = redis.call('GET', KEYS[2]) or 0
available = tonumber(available)
locked = tonumber(locked)
local quantity = tonumber(ARGV[1])

if available < quantity then
    return 0
end

redis.call('DECRBY', KEYS[1], quantity)
redis.call('INCRBY', KEYS[2], quantity)

-- 如果订单系统崩溃/网络中断，UNLOCK 永远不会被调用，locked 会被永远"冻结"。这些库存既不能卖也不能释放。
-- 所以设置 这个 key 会在 1800 秒（30分钟）后自动删除,对应value也完全消失
redis.call('EXPIRE', KEYS[2], 1800)

return 1