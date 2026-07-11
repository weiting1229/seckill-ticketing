-- 一次性搶購 token 的原子校驗 + 刪除(設計文件第 6、10 節)。
-- GET 比對後 DEL 必須原子執行,避免兩個併發請求用同一 token 都通過。
-- KEYS[1] = seckill:token:{userId}:{ticketTypeId}
-- ARGV[1] = 前端提交的 token
-- 回傳:1 校驗通過並已消耗(刪除);0 不符或不存在(過期 / 已用 / 偽造)
local v = redis.call('GET', KEYS[1])
if v and v == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
return 0
