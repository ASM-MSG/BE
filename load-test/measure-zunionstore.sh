#!/usr/bin/env bash
# hotzone:top 재계산(8버킷 ZUNIONSTORE) 의 순수 Redis 비용을 격자 규모별로 잰다.
#
# 이 비용이 중요한 이유: Redis 는 명령을 한 번에 하나씩 처리한다. 재계산이 N ms 걸리면
# 그동안 다른 요청은 큐에서 기다린다. 캐시가 만료되는 순간 이 대기가 몰려 응답 시간의
# 꼬리가 튄다(head-of-line 지연). 재계산 자체는 한 번만 일어난다 — 운영 스크립트가
# EXISTS+ZUNIONSTORE+EXPIRE 를 한 덩어리 Lua 로 묶어 두어 Redis 가 원자적으로 처리하고,
# 뒤이은 요청은 EXISTS=1 을 보고 건너뛰기 때문이다.
#
# 두 가지를 잰다:
#   (a) ZUNIONSTORE 단독 — 결과 키가 이미 있는 상태에서 덮어쓰는 반복
#   (b) 운영 스크립트 전체 — 매 회 결과 키를 지워 "만료 직후"를 재현 (DEL+EXISTS+ZUNIONSTORE+EXPIRE)
# 둘 다 빈 EVAL 기준선을 빼서 docker exec·CLI 고정비를 제거한다.
#
# 사용: ./load-test/measure-zunionstore.sh          (1000·10000·100000 표본 순회)
#       SIZES="1000 5000" ./load-test/measure-zunionstore.sh
set -euo pipefail

REDIS_CONTAINER="${REDIS_CONTAINER:-fillmap-local-redis}"
SIZES="${SIZES:-1000 10000 100000}"
REPEAT=100
ROUNDS=3          # 회차 간 편차를 보려고 여러 번 재고 중앙값을 쓴다
BUCKET_SECONDS=21600
LOOKBACK=8

now=$(date +%s)
current_bucket=$((now / BUCKET_SECONDS))
first_bucket=$((current_bucket - LOOKBACK + 1))
keys=""
for ((b = first_bucket; b <= current_bucket; b++)); do keys="$keys hotzone:$b"; done

# Lua 를 REPEAT 회 돌린 총 시간을 잰다. 고정비(docker exec·CLI 왕복)는 REPEAT 로 나뉘지만
# 0 은 아니므로, 같은 형태의 빈 루프를 재서 빼 준다.
time_eval() {
	local script="$1"; shift
	local start end
	start=$(python3 -c 'import time; print(time.time_ns())')
	# shellcheck disable=SC2086
	docker exec "$REDIS_CONTAINER" redis-cli EVAL "$script" $((LOOKBACK + 1)) hotzone:bench $keys >/dev/null
	end=$(python3 -c 'import time; print(time.time_ns())')
	echo "$(( (end - start) / 1000 ))"   # 마이크로초
}

median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}'; }

BASELINE_LUA="for i=1,$REPEAT do end return 1"
ZUNION_LUA="for i=1,$REPEAT do redis.call('ZUNIONSTORE', KEYS[1], $LOOKBACK, unpack(KEYS,2)) end return 1"
# 운영 ENSURE_TOP_SCRIPT 와 같은 순서. 매 회 DEL 로 "캐시가 막 만료된 상태"를 만든다.
ENSURE_LUA="for i=1,$REPEAT do \
redis.call('DEL', KEYS[1]) \
if redis.call('EXISTS', KEYS[1]) == 0 then \
redis.call('ZUNIONSTORE', KEYS[1], $LOOKBACK, unpack(KEYS,2)) \
redis.call('EXPIRE', KEYS[1], 30) \
end end return 1"

printf "\n%-12s %-14s %-16s %-18s\n" "좌표 표본" "고유 격자" "ZUNIONSTORE" "운영 스크립트 전체"
printf "%s\n" "──────────────────────────────────────────────────────────────────"

for size in $SIZES; do
	FORCE=1 ./load-test/seed-hotzone.sh "$size" >/dev/null

	baselines=(); zunions=(); ensures=()
	for ((r = 0; r < ROUNDS; r++)); do
		baselines+=("$(time_eval "$BASELINE_LUA")")
		zunions+=("$(time_eval "$ZUNION_LUA")")
		ensures+=("$(time_eval "$ENSURE_LUA")")
	done

	base=$(median "${baselines[@]}")
	zun=$(median "${zunions[@]}")
	ens=$(median "${ensures[@]}")
	unique=$(docker exec "$REDIS_CONTAINER" redis-cli ZCARD hotzone:bench)
	docker exec "$REDIS_CONTAINER" redis-cli DEL hotzone:bench >/dev/null

	python3 -c "
zun_ms = max(0.0, ($zun - $base) / 1000 / $REPEAT)
ens_ms = max(0.0, ($ens - $base) / 1000 / $REPEAT)
print(f'{$size:<12,} {$unique:<14,} {zun_ms:<16.3f} {ens_ms:.3f} ms')
"
done

echo
echo "  기준선(빈 EVAL ${REPEAT}회)을 빼고 ${REPEAT}회로 나눈 1회 비용, ${ROUNDS}회 측정 중앙값."
echo "  '좌표 표본'은 뽑은 난수 좌표 수이고 실제 격자는 '고유 격자' 열이다 (복원추출이라 더 적다)."
echo "  ※ 재계산이 도는 동안 Redis 는 다른 명령을 처리하지 못한다. 이 값이 곧 만료 시점의 대기 폭이다."
