
local locked = redis.call('GET', KEYS[1]) or 0
locked = tonumber(locked)
local quantity = tonumber(ARGV[1])

if locked < quantity then
    return 0
end

redis.call('DECRBY', KEYS[1], quantity)

return 1