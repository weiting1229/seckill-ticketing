# ratelimit-bench — 單一 Redis 節點的限流 CAS 上限

階段 3 的量測工具（計畫 [`docs/plans/2026-09-15-oci-load-test-stages.md`](../../docs/plans/2026-09-15-oci-load-test-stages.md) §4）。
回答一個問題：**一個 Redis 節點面對瞬間爆量的 Bucket4j CAS 檢查，能撐多少併發而不讓排隊延遲失控。**

## 為什麼是這支程式，而不是繼續用 k6

輪 1-2（2000 VU 打正式站）證明：情境 A 每個 VU 只搶一次就結束，2000 個 VU 在 30 秒 ramp 內
對 `seckill:rl:global` 只產生約 **65 次/秒**，是要驗證的門檻（`global-capacity=3000`/s）的 **2%**。
而且經由 HTTP 打進去，量到的會混進 TLS、Caddy、Tomcat、BCrypt 的影響，分不出延遲來自哪裡。

這支程式直接在主機的 Docker 網路內跑，只做一件事：用**與正式程式碼完全相同**的
Bucket4j + Lettuce CAS 路徑打同一把 key。

## 與正式程式碼的對應

偏離任何一項，量到的就不是正式站在跑的東西。對照來源是
[`RateLimitConfig.java`](../../backend/src/main/java/com/seckill/config/RateLimitConfig.java) 與
[`RateLimiterService.java`](../../backend/src/main/java/com/seckill/seckill/ratelimit/RateLimiterService.java)：

| 項目 | 設定 |
|---|---|
| ProxyManager | `Bucket4jLettuce.casBasedBuilder` + `basedOnTimeForRefillingBucketUpToMax(10s)` |
| codec | `RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)` |
| 連線 | 單一共享 `StatefulRedisConnection`（連線本身的多工也是被測對象的一部分） |
| 每次呼叫 | 重新 `getProxy(key, cfg)` 再 `tryConsume(1)`，不快取 proxy |
| 桶設定 | `capacity(N).refillGreedy(N, 1s)` |
| 版本 | bucket4j 8.19.0、lettuce 6.6.0.RELEASE、netty 4.1.137.Final（對齊 backend 實際解析到的版本） |

⚠️ **netty 必須整組同版本**：只釘其中幾個子模組會在執行期炸
`NoSuchMethodError: PlatformDependent.isExplicitNoPreferDirect`（實測過）。pom 用 `netty-bom` 一次對齊。

## 量法

閉環（closed loop）：每個 worker 拿到回應就立刻送下一個，所以「併發數」是**在途請求數**，
吞吐是量出來的結果而不是設定值。逐階加大併發找延遲拐點。

計時涵蓋從呼叫到拿到回應的 wall-clock，與 `seckill.ratelimit.check.duration` 同定義，
天然含 Redis 單執行緒的佇列等待。延遲用每個 worker 私有的固定解析度直方圖收集
（0–10ms 每格 10µs、10ms–1s 每格 1ms），不共享不加鎖，避免量測本身造成競爭。

## 建置

```bash
cd load-test/ratelimit-bench
JAVA_HOME="C:\\Users\\USER\\.jdks\\temurin-25\\jdk-25.0.3+9" ../../backend/mvnw -B clean package
```

一定要帶 `clean`：`target/` 留著上次的 fat jar 時，shade 會把它當成輸入再包一層（警告
`ratelimit-bench.jar define 263 overlapping classes`）。

產出 `target/ratelimit-bench.jar`（fat jar，約 7 MB）。jar 不進版控（`.gitignore` 的 `*.jar`）。

## 在 OCI 上跑

**打的是另起的拋棄式 Redis，不是正式 Redis**（2026-09-20 使用者決定）。

1. 傳 jar 上去：
   ```bash
   scp load-test/ratelimit-bench/target/ratelimit-bench.jar oci-seckill:/tmp/
   scp infra/docker-compose.loadtest-redis.yml oci-seckill:/opt/seckill/
   ```
2. 起拋棄式 Redis（密碼當場給，不寫進檔案）：
   ```bash
   ssh oci-seckill 'cd /opt/seckill && BENCH_REDIS_PASSWORD=<當場想一個> docker compose -f docker-compose.loadtest-redis.yml up -d'
   ```
3. 跑 bench（在同一個 docker 網路內，用容器名連）：
   ```bash
   ssh oci-seckill 'docker run --rm --network seckill-prod_default -v /tmp/ratelimit-bench.jar:/bench.jar:ro -e REDIS_URI="redis://:<同一個密碼>@seckill-redis-bench:6379/0" -e BENCH_STEPS=50,100,200,400,800,1600 eclipse-temurin:25-jre java -jar /bench.jar'
   ```
4. 收工：
   ```bash
   ssh oci-seckill 'cd /opt/seckill && BENCH_REDIS_PASSWORD=x docker compose -f docker-compose.loadtest-redis.yml down -v'
   ```

## 環境變數

| 變數 | 預設 | 說明 |
|---|---|---|
| `REDIS_URI` | 必填 | 例如 `redis://:password@seckill-redis-bench:6379/0`。程式不會把它印出來（含密碼） |
| `BENCH_KEY` | `seckill:rl:global` | 要打的 key |
| `BENCH_CAPACITY` | 3000 | 桶容量，對齊正式站 `global-capacity` |
| `BENCH_STEPS` | `50,100,200,400,800,1600` | 逗號分隔的併發數，逐階跑 |
| `BENCH_STEP_SECONDS` | 30 | 每階計時秒數 |
| `BENCH_WARMUP_SECONDS` | 5 | 每階正式計時前的暖身 |
| `BENCH_GAP_SECONDS` | 10 | 階與階之間的靜置 |

## 判讀

輸出是一張表，每階一列：`ops`、`ops/s`、`allowed`、`throttled`、`errors`、p50/p90/p99/p99.9/max（微秒）。

要找的是**拐點**：併發再往上加，`ops/s` 不再上升（甚至下降）而 p99 開始陡升的那一階。
那一階的 `ops/s` 就是階段 4 要用的「單節點安全上限」。

⚠️ `ops/s` **下降**而不是持平，是 CAS 重試風暴的典型形狀（競爭者越多、重試越多、有效工作越少），
不是量測出錯。本機 smoke test（Windows + Docker Desktop，數字不可外推）已經看到這個形狀：
併發 20/50/100 → 2801/2454/1753 ops/s，p99 44ms/118ms/325ms。
