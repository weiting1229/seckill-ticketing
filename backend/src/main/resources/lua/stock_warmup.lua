-- 庫存預熱(冪等,不覆蓋已扣減庫存)。
-- KEYS[1] = seckill:stock:{ticketTypeId}
-- ARGV[1] = 初始庫存(來自 DB stock_remaining)
-- ARGV[2] = TTL 秒數(活動結束 + 1 天)
-- 回傳:預熱後 Redis 現值(整數)。
--   已存在(重複預熱):原子地回傳現值,絕不覆蓋——保住熱路徑已扣減的庫存。
--   不存在(首次預熱):SET 值與 TTL 後回傳該值。
local cur = redis.call('GET', KEYS[1])
if cur then
    return tonumber(cur)
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return tonumber(ARGV[1])
