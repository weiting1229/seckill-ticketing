-- 多層令牌桶的原子檢查(ADR 0010,設計文件第 10 節)。
-- 讀、算、寫在 Redis 內一次完成,沒有 Bucket4j 樂觀 CAS 的「版本變了 → 重試」。
--
-- 全有或全無(ADR 0010 §3):先算出每一層補充後的 token 數,所有層都至少有 1 個 token 才同時各扣 1;
-- 任何一層不足就一個都不扣、也不寫回,被擋下的請求不消耗其他層的額度。
-- 不寫回不影響正確性:狀態是「上次更新時的 token 數 + 時間」,下次讀取時照樣從舊時間補充到現在。
--
-- 語意對齊原 Bucket4j 設定 capacity(N).refillGreedy(N, 1s):容量 N、每秒平滑補滿 N、初始為滿。
-- 時間取 Redis TIME,不收 client 時間:多個 backend 共用全域 key 時不受各機時鐘漂移影響(ADR 0010 §4)。
-- 狀態以整數「微 token」(token × 1e6)存,補充量 = 經過微秒 × 每秒容量,恰為整數,不累積浮點誤差。
--
-- KEYS[i] = 第 i 層的桶(hash:t = 剩餘微 token、ts = 上次更新的 Redis 時間,微秒)
-- ARGV[i] = 第 i 層的每秒容量
-- 回傳:0 放行(每層已各扣 1);i > 0 表示第 i 層不足而被擋下(按 KEYS 順序取第一個不足的)
local now = redis.call('TIME')
local nowMicros = tonumber(now[1]) * 1000000 + tonumber(now[2])

local tokens = {}
for i = 1, #KEYS do
    local rate = tonumber(ARGV[i])
    local capacity = rate * 1000000
    local state = redis.call('HMGET', KEYS[i], 't', 'ts')
    local t = tonumber(state[1])
    local ts = tonumber(state[2])
    if t == nil or ts == nil then
        -- key 不存在(或已過期)= 桶已補滿
        t = capacity
        ts = nowMicros
    end
    local elapsed = nowMicros - ts
    if elapsed > 0 then
        t = math.min(capacity, t + elapsed * rate)
    end
    if t < 1000000 then
        return i
    end
    tokens[i] = t
end

for i = 1, #KEYS do
    redis.call('HSET', KEYS[i], 't', tokens[i] - 1000000, 'ts', nowMicros)
    -- TTL = 空桶補滿所需時間 + 1 秒。容量即每秒速率,空桶補滿恆為 1 秒,故固定 2 秒。
    -- 過期後重建成滿桶,與沒過期時的計算結果相同,TTL 只影響記憶體、不影響語意。
    redis.call('PEXPIRE', KEYS[i], 2000)
end
return 0
