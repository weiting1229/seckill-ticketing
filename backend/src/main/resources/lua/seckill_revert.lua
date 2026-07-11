-- 搶購庫存回補(設計文件第 6 節):DB 落庫失敗、訂單超時取消兩個場景使用。
-- 與 seckill_deduct.lua 對稱,原子回補一張庫存並移除已購標記。
-- KEYS[1] = seckill:stock:{ticketTypeId}     預扣庫存(String int)
-- KEYS[2] = seckill:bought:{ticketTypeId}    已購用戶集合(Set of userId)
-- ARGV[1] = userId
-- 回傳:1(恆成功)
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return 1
