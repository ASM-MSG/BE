#!/usr/bin/env bash
# 핫구역 부하테스트용 Redis 시드 (MSG-183/184).
#
# hotzone:{bucketId} Sorted Set 8개(조회가 룩백하는 창 그대로)에 격자를 흩뿌린다.
# bucketId = epochSeconds / 21600 — HotZoneServiceImpl 과 같은 UTC 6h 고정 버킷.
#
# 사용: ./load-test/seed-hotzone.sh [격자수]        (기본 1000)
#       GRIDS=100000 ./load-test/seed-hotzone.sh
#
# 격자 수를 바꿔가며 재실행하면 ZUNIONSTORE 비용의 규모 감도를 볼 수 있다.
# 상위 K(50) 캡 때문에 응답 크기는 규모와 무관하게 최대 50개다 — 커지는 건 합산 비용뿐이다.
set -euo pipefail

REDIS_CONTAINER="${REDIS_CONTAINER:-fillmap-local-redis}"
GRIDS="${1:-${GRIDS:-1000}}"
BUCKET_SECONDS=21600
LOOKBACK=8

# 서울 도심 일대에 뿌린다 — k6 뷰포트와 같은 범위여야 필터를 통과한다.
LAT_MIN=37.45 LAT_MAX=37.65
LNG_MIN=126.85 LNG_MAX=127.15
# GridEncoder 와 같은 상수 (glossary "격자 계산 규칙")
LAT_STEP=0.0009
LNG_STEP=0.00115

now=$(date +%s)
current_bucket=$((now / BUCKET_SECONDS))
first_bucket=$((current_bucket - LOOKBACK + 1))

echo "격자 ${GRIDS}개를 버킷 ${first_bucket}~${current_bucket} (8개)에 시드합니다..."

# 기존 핫구역 키 제거 — 이전 회차의 격자가 남으면 규모 감도 측정이 오염된다.
docker exec "$REDIS_CONTAINER" sh -c \
	'redis-cli --scan --pattern "hotzone:*" | xargs -r redis-cli DEL' >/dev/null

# ZADD 명령을 만들어 redis-cli --pipe 로 한 번에 밀어넣는다 (건당 왕복이면 10만 건에 수 분).
awk -v grids="$GRIDS" -v first="$first_bucket" -v lookback="$LOOKBACK" \
	-v latmin="$LAT_MIN" -v latmax="$LAT_MAX" -v lngmin="$LNG_MIN" -v lngmax="$LNG_MAX" \
	-v latstep="$LAT_STEP" -v lngstep="$LNG_STEP" '
BEGIN {
	srand(42)   # 회차 간 같은 격자 집합을 쓰도록 고정 시드 — 규모만 변수로 남긴다
	for (i = 0; i < grids; i++) {
		lat = latmin + rand() * (latmax - latmin)
		lng = lngmin + rand() * (lngmax - lngmin)
		gridY = int(lat / latstep)
		gridX = int(lng / lngstep)
		gridId = gridY "_" gridX

		# 상위 K(50) 판정이 실제로 갈리도록 스코어를 치우치게 준다:
		# 대부분은 1~2(임계 3 미만이라 탈락), 5%만 5~30(핫구역 후보).
		hot = (rand() < 0.05)
		for (b = 0; b < lookback; b++) {
			score = hot ? int(rand() * 4) + 1 : (rand() < 0.3 ? 1 : 0)
			if (score > 0) {
				printf "ZADD hotzone:%d %d %s\r\n", first + b, score, gridId
			}
		}
	}
}' | docker exec -i "$REDIS_CONTAINER" redis-cli --pipe

# 버킷 TTL 은 집계(MSG-183)가 붙이는 54h 를 흉내 낸다 — 없으면 키가 영구 잔존한다.
for ((b = first_bucket; b <= current_bucket; b++)); do
	docker exec "$REDIS_CONTAINER" redis-cli EXPIRE "hotzone:$b" 194400 >/dev/null
done

echo
echo "=== 시드 결과 ==="
for ((b = first_bucket; b <= current_bucket; b++)); do
	count=$(docker exec "$REDIS_CONTAINER" redis-cli ZCARD "hotzone:$b")
	printf "  hotzone:%-12s %s개\n" "$b" "$count"
done

# 합산 후 상위 K 안에 임계(3) 이상이 몇 개인지 — 응답에 실제로 담길 개수의 상한이다.
docker exec "$REDIS_CONTAINER" redis-cli DEL hotzone:top >/dev/null
keys=""
for ((b = first_bucket; b <= current_bucket; b++)); do keys="$keys hotzone:$b"; done
# shellcheck disable=SC2086
docker exec "$REDIS_CONTAINER" redis-cli ZUNIONSTORE hotzone:preview "$LOOKBACK" $keys >/dev/null
echo
echo "  합산 격자 $(docker exec "$REDIS_CONTAINER" redis-cli ZCARD hotzone:preview)개"
echo "  상위 50 중 임계(3) 이상: $(docker exec "$REDIS_CONTAINER" redis-cli ZREVRANGEBYSCORE hotzone:preview +inf 3 LIMIT 0 50 | wc -l | tr -d ' ')개"
docker exec "$REDIS_CONTAINER" redis-cli DEL hotzone:preview >/dev/null
