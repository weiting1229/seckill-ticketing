#!/usr/bin/env bash
# 逐階單獨跑 ratelimit-bench,每階前後抓 Redis 指令計數與 CPU,用來分辨瓶頸在哪一端。
#
# 為什麼要有這支:第一輪(50–1600 併發)的 ops/s × 併發數 幾乎是常數(約 85k–92k),
# 看起來像「每成功一次要付出約 N 次 CAS 嘗試」的重試風暴,但光看 bench 的輸出分不出
# 瓶頸是 Redis 單執行緒還是 client 端單一連線的 netty event loop。
# 每階單獨跑才能用 CONFIG RESETSTAT 拿到該階自己的指令計數。
#
# 用法(在 OCI 主機上,拋棄式 Redis 已 up):
#   BENCH_REDIS_PASSWORD=<同 compose 的密碼> bash /tmp/sweep.sh 1 2 5 10 20 30 50
#
# 注意:commandstats 含每階 BENCH_WARMUP_SECONDS 的暖身流量,bench 的 ops 不含。
# 算「每次成功的指令數」時分母要用 ops/s × (暖身 + 計時秒數)。
set -euo pipefail

: "${BENCH_REDIS_PASSWORD:?請設定 BENCH_REDIS_PASSWORD}"
[ "$#" -gt 0 ] || { echo "用法: $0 <併發數>..." >&2; exit 2; }

JAR=${BENCH_JAR:-/tmp/ratelimit-bench.jar}
NET=${BENCH_NETWORK:-seckill-prod_default}
STEP_SECONDS=${BENCH_STEP_SECONDS:-30}
WARMUP_SECONDS=${BENCH_WARMUP_SECONDS:-5}
REDIS_CONTAINER=seckill-redis-bench
BENCH_CONTAINER=seckill-ratelimit-bench

rcli() {
    docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$BENCH_REDIS_PASSWORD" "$@" | tr -d '\r'
}

# jar 不存在時 docker -v 會在主機上以 root 建一個同名空目錄,java 只會報
# "Invalid or corrupt jarfile",看不出真因(實測踩過)
if [ ! -f "$JAR" ]; then
    echo "$JAR 不是檔案。若它是 docker 誤建的空目錄,先 sudo rmdir 再重傳 jar。" >&2
    exit 1
fi

if [ "$(docker inspect -f '{{.State.Health.Status}}' "$REDIS_CONTAINER" 2>/dev/null)" != "healthy" ]; then
    echo "$REDIS_CONTAINER 不存在或尚未 healthy。先在 /opt/seckill 下起拋棄式 Redis:" >&2
    echo "  BENCH_REDIS_PASSWORD=... docker compose -f docker-compose.loadtest-redis.yml up -d --wait" >&2
    exit 1
fi

echo "stepSeconds=$STEP_SECONDS warmupSeconds=$WARMUP_SECONDS steps=$*"
for c in "$@"; do
    echo
    echo "=== concurrency $c ==="
    rcli CONFIG RESETSTAT >/dev/null

    # 計時窗中段取一次 CPU 快照(背景執行,bench 結束前一定會跑完)
    (
        sleep $((WARMUP_SECONDS + STEP_SECONDS / 2))
        docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}}' "$REDIS_CONTAINER" "$BENCH_CONTAINER"
    ) &

    docker run --rm --name "$BENCH_CONTAINER" --network "$NET" \
        -v "$JAR":/bench.jar:ro \
        -e REDIS_URI="redis://:${BENCH_REDIS_PASSWORD}@${REDIS_CONTAINER}:6379/0" \
        -e BENCH_STEPS="$c" \
        -e BENCH_STEP_SECONDS="$STEP_SECONDS" \
        -e BENCH_WARMUP_SECONDS="$WARMUP_SECONDS" \
        -e BENCH_GAP_SECONDS=0 \
        eclipse-temurin:25-jre java -jar /bench.jar \
        | grep -E '^concurrency|^ *[0-9]+ \|'
    wait

    rcli INFO commandstats | grep -E '^cmdstat_' || true
done
