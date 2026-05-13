
local available = redis.call('GET', KEYS[1]) or 0
local locked = redis.call('GET', KEYS[2]) or 0
available = tonumber(available)
locked = tonumber(locked)
quantity = tonumber(ARGV[1])

if locked < quantity then
    return 0
end

redis.call('DECRBY', KEYS[2], quantity)
redis.call('INCRBY', KEYS[1], quantity)

return 1