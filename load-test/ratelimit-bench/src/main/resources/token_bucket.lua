-- 單一原子操作的 token bucket(Lua 評估,計畫 §6)。
-- 與 Bucket4j 樂觀 CAS 的差別:讀、算、寫在 Redis 內一次完成,中間不會被其他請求插隊,
-- 所以沒有「寫回時發現版本變了 → 重試」這件事。每次檢查固定一次往返。
--
-- 語意對齊 RateLimiterService.perSecond():capacity 個 token、每秒平滑(greedy)補滿、初始為滿。
-- 時間取 Redis TIME,不用 client 時鐘:多個 backend 實例共用同一把 key 時不受各自時鐘漂移影響。
-- (Redis 7 的腳本一律以效果複寫,腳本內呼叫 TIME 不影響複寫一致性)
--
-- 狀態以「微 token」(token × 1e6)存成整數,避免浮點累積誤差:
--   補充量 = 經過微秒 × 每秒補充數,剛好是整數。
--
-- KEYS[1] = 桶的 key(hash:t = 剩餘微 token、ts = 上次更新的 Redis 時間,微秒)
-- ARGV[1] = capacity(token)
-- ARGV[2] = 每秒補充的 token 數
-- 回傳:1 放行(已扣 1 個 token);0 已達上限
local capacity = tonumber(ARGV[1]) * 1000000
local rate = tonumber(ARGV[2])

local now = redis.call('TIME')
local nowMicros = tonumber(now[1]) * 1000000 + tonumber(now[2])

local state = redis.call('HMGET', KEYS[1], 't', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
    -- key 不存在(或過期)= 桶已補滿
    tokens = capacity
    ts = nowMicros
end

local elapsed = nowMicros - ts
if elapsed > 0 then
    tokens = math.min(capacity, tokens + elapsed * rate)
end

local allowed = 0
if tokens >= 1000000 then
    tokens = tokens - 1000000
    allowed = 1
end

redis.call('HSET', KEYS[1], 't', tokens, 'ts', nowMicros)
-- 空桶補滿所需時間 + 1 秒緩衝;過期後重建成滿桶,與沒過期時的計算結果相同
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate / 1000) + 1000)
return allowed
