-- 搶購原子扣減(設計文件第 6 節)。杜絕重複購買與超賣的競態:整段在 Redis 單執行緒原子執行。
-- KEYS[1] = seckill:stock:{ticketTypeId}     預扣庫存(String int)
-- KEYS[2] = seckill:bought:{ticketTypeId}    已購用戶集合(Set of userId)
-- ARGV[1] = userId
-- 回傳:
--    1  成功(庫存 -1、已購集合 +userId)
--   -1  重複購買(已在已購集合)
--   -2  售罄(庫存 <= 0)
--   -3  活動未預熱 / 庫存 key 不存在
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '-999')
if stock == -999 then
    return -3
end
if stock <= 0 then
    return -2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
-- 已購集合首次建立時,對齊 stock key 的剩餘 TTL(設計文件第 6 節:bought 集合 TTL 同 stock =「活動結束+1天」)。
-- 只在尚無 TTL 時設(TTL < 0),確保設一次、不隨每次購買滑動;熱路徑不需讀 DB 算 event_time。
if redis.call('TTL', KEYS[2]) < 0 then
    local stockTtl = redis.call('TTL', KEYS[1])
    if stockTtl > 0 then
        redis.call('EXPIRE', KEYS[2], stockTtl)
    end
end
return 1
