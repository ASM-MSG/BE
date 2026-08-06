#!/usr/bin/env bash
# hotzone:top 재계산(8버킷 ZUNIONSTORE) 의 순수 Redis 비용을 격자 규모별로 잰다.
#
# 이 비용이 중요한 이유: Redis 는 단일 스레드다. 재계산이 N ms 걸리면 그동안 Redis 는
# 다른 요청을 처리하지 못하고, 캐시 만료 직후 N ms 창 안에 도착한 요청은 (락이 없으므로)
# 각자 또 재계산을 시작한다. 창이 넓을수록 중복 실행이 늘고 서로를 막는다.
#
# 사용: ./load-test/measure-zunionstore.sh          (1000·10000·100000 순회)
#       SIZES="1000 5000" ./load-test/measure-zunionstore.sh
set -euo pipefail

REDIS_CONTAINER="${REDIS_CONTAINER:-fillmap-local-redis}"
SIZES="${SIZES:-1000 10000 100000}"
REPEAT=100
BUCKET_SECONDS=21600
LOOKBACK=8

now=$(date +%s)
current_bucket=$((now / BUCKET_SECONDS))
first_bucket=$((current_bucket - LOOKBACK + 1))
keys=""
for ((b = first_bucket; b <= current_bucket; b++)); do keys="$keys hotzone:$b"; done

printf "\n%-10s %-12s %-14s %-16s\n" "격자수" "버킷당" "합산결과" "ZUNIONSTORE"
printf "%s\n" "────────────────────────────────────────────────────────────"

for size in $SIZES; do
	./load-test/seed-hotzone.sh "$size" >/dev/null

	per_bucket=$(docker exec "$REDIS_CONTAINER" redis-cli ZCARD "hotzone:$current_bucket")

	# Lua 안에서 REPEAT 회 반복 — docker exec 왕복이 REPEAT 분의 1로 희석돼 서버 비용만 남는다.
	start=$(python3 -c 'import time; print(time.time_ns())')
	# shellcheck disable=SC2086
	docker exec "$REDIS_CONTAINER" redis-cli EVAL \
		"for i=1,$REPEAT do redis.call('ZUNIONSTORE', KEYS[1], $LOOKBACK, unpack(KEYS,2)) end return 1" \
		$((LOOKBACK + 1)) hotzone:bench $keys >/dev/null
	end=$(python3 -c 'import time; print(time.time_ns())')

	merged=$(docker exec "$REDIS_CONTAINER" redis-cli ZCARD hotzone:bench)
	docker exec "$REDIS_CONTAINER" redis-cli DEL hotzone:bench >/dev/null

	python3 -c "
elapsed_ms = ($end - $start) / 1e6
print(f'{$size:<10} {$per_bucket:<12} {$merged:<14} {elapsed_ms / $REPEAT:.3f} ms')
"
done

echo
echo "  ※ 재계산 창이 넓을수록 그 안에 도착한 요청이 전부 중복 재계산한다 (락 없음, MSG-233 D4)."
