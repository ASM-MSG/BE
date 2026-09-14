#!/usr/bin/env bash
# 핫구역 부하테스트용 Redis 시드 (MSG-183/184).
#
# hotzone:{bucketId} Sorted Set 8개(조회가 룩백하는 창 그대로)에 격자를 흩뿌린다.
# bucketId = epochSeconds / 21600 — HotZoneServiceImpl 과 같은 UTC 6h 고정 버킷.
#
# 사용: ./load-test/seed-hotzone.sh [좌표 표본 수]     (기본 1000)
#       GRIDS=100000 ./load-test/seed-hotzone.sh
#       FORCE=1 ./load-test/seed-hotzone.sh 1000      (기존 키 확인 없이 삭제)
#
# 인자는 "격자 수"가 아니라 뽑을 좌표 표본 수다. 난수 좌표를 복원추출하므로 서로 다른
# 격자 수는 그보다 적다(같은 셀이 여러 번 뽑힌다). 실제 고유 격자 수는 아래 출력의
# "합산 고유 격자"를 볼 것 — 문서·표에는 그 값을 써야 한다.
set -euo pipefail

REDIS_CONTAINER="${REDIS_CONTAINER:-fillmap-local-redis}"
SAMPLES="${1:-${GRIDS:-1000}}"
BUCKET_SECONDS=21600
LOOKBACK=8

# 서울 도심 일대에 뿌린다 — k6 뷰포트와 같은 범위여야 필터를 통과한다.
# 규모 감도 스윕(hotzone-scale-sweep.sh)은 AREA=wide 로 전국 사각형에 뿌려 고유 격자 수를
# 서울 상한(약 5.8만 칸) 너머로 키운다. 그때도 핫구역 후보(점수 3 이상)는 서울 상자 안에서만
# 만든다 — 전국 상위 50 이 뷰포트 밖에 몰리면 응답이 전부 비어 지연 수치가 무의미해진다.
if [ "${AREA:-seoul}" = "wide" ]; then
	LAT_MIN=34.5 LAT_MAX=38.3
	LNG_MIN=126.0 LNG_MAX=129.5
else
	LAT_MIN=37.45 LAT_MAX=37.65
	LNG_MIN=126.85 LNG_MAX=127.15
fi
HOT_LAT_MIN=37.45 HOT_LAT_MAX=37.65
HOT_LNG_MIN=126.85 HOT_LNG_MAX=127.15
# wide 모드에서는 표본과 별개로 서울 상자에 HOT_EXTRA 개를 원래 분포(5% 핫)로 더 뿌린다.
# 규모(표본 수)가 바뀌어도 상위 50 핫구역 집합은 같게 유지된다 — 규모만 변수로 남긴다.
HOT_EXTRA=$([ "${AREA:-seoul}" = "wide" ] && echo "${HOT_EXTRA:-2000}" || echo 0)
now=$(date +%s)
current_bucket=$((now / BUCKET_SECONDS))
first_bucket=$((current_bucket - LOOKBACK + 1))

# 기존 핫구역 키 제거 — 이전 회차의 격자가 남으면 규모 감도 측정이 오염된다.
# 다만 REDIS_CONTAINER 를 잘못 주면 실제 핫스코어를 날린다. 이미 쌓인 게 많으면 멈춘다.
existing=$(docker exec "$REDIS_CONTAINER" sh -c \
	'redis-cli --scan --pattern "hotzone:*" | wc -l' | tr -d ' ')
if [ "$existing" -gt 100 ] && [ "${FORCE:-0}" != "1" ]; then
	echo "중단: '$REDIS_CONTAINER' 에 hotzone 키가 ${existing}개 있습니다." >&2
	echo "      테스트용 Redis 가 맞는지 확인하고, 맞으면 FORCE=1 로 다시 실행하세요." >&2
	exit 1
fi
docker exec "$REDIS_CONTAINER" sh -c \
	'redis-cli --scan --pattern "hotzone:*" | xargs -r redis-cli DEL' >/dev/null

echo "좌표 ${SAMPLES}개를 버킷 ${first_bucket}~${current_bucket} (8개)에 시드합니다..."

# ZADD 명령을 만들어 redis-cli --pipe 로 한 번에 밀어넣는다 (건당 왕복이면 10만 건에 수 분).
#
# gridId 는 PostGIS 로 만든다. 격자 인코딩은 MSG-347 이후 위경도 스텝이 아니라 EPSG:5179 미터 평면을
# 100m 로 나눈 floor 다 (GridEncoder). 예전 awk 시드(LAT_STEP·LNG_STEP)는 그 뒤로 앱 필터와 어긋나
# 응답이 전부 비었다 (2026-09-14 실측). spatial_ref_sys 의 5179 proj4 정의는 GridConstants 와 같다.
# setseed 로 회차 간 같은 격자 집합을 쓴다 — 규모만 변수로 남긴다.
PG_CONTAINER="${PG_CONTAINER:-fillmap-postgres}"
docker exec -i "$PG_CONTAINER" psql -U user -d fillmap -qtA -F' ' \
	-v samples="$SAMPLES" -v extra="$HOT_EXTRA" -v first="$first_bucket" -v lookback="$LOOKBACK" \
	-v latmin="$LAT_MIN" -v latmax="$LAT_MAX" -v lngmin="$LNG_MIN" -v lngmax="$LNG_MAX" \
	-v hlatmin="$HOT_LAT_MIN" -v hlatmax="$HOT_LAT_MAX" -v hlngmin="$HOT_LNG_MIN" -v hlngmax="$HOT_LNG_MAX" <<'SQL' \
	| docker exec -i "$REDIS_CONTAINER" redis-cli --pipe
SELECT setseed(0.42);
WITH pts AS (
	-- 전국(또는 서울) 표본. wide 모드에서는 핫이 되지 않는다.
	SELECT :latmin + random() * (:latmax - :latmin) AS lat,
	       :lngmin + random() * (:lngmax - :lngmin) AS lng,
	       (:extra = 0 AND random() < 0.05) AS hot
	FROM generate_series(1, :samples)
	UNION ALL
	-- 서울 핫 후보 (wide 모드에서만 extra > 0). 원래 분포 그대로 5% 핫.
	SELECT :hlatmin + random() * (:hlatmax - :hlatmin),
	       :hlngmin + random() * (:hlngmax - :hlngmin),
	       random() < 0.05
	FROM generate_series(1, :extra)
), cells AS (
	SELECT floor(ST_Y(m) / 100)::bigint || '_' || floor(ST_X(m) / 100)::bigint AS grid_id, hot
	FROM pts, LATERAL (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), 5179) AS m) t
), scored AS (
	-- 상위 K(50) 판정이 실제로 갈리도록 스코어를 치우치게 준다:
	-- 대부분은 1~2(임계 3 미만이라 탈락), 5%만 5~30(핫구역 후보).
	SELECT grid_id, :first + b AS bucket,
	       CASE WHEN hot THEN floor(random() * 4)::int + 1
	            WHEN random() < 0.3 THEN 1 ELSE 0 END AS score
	FROM cells, generate_series(0, :lookback - 1) b
)
SELECT 'ZADD hotzone:' || bucket || ' ' || score || ' ' || grid_id
FROM scored WHERE score > 0;
SQL

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
unique=$(docker exec "$REDIS_CONTAINER" redis-cli ZCARD hotzone:preview)
echo
echo "  좌표 표본 ${SAMPLES}개 → 합산 고유 격자 ${unique}개  (문서에는 이 값을 쓸 것)"
echo "  상위 50 중 임계(3) 이상: $(docker exec "$REDIS_CONTAINER" redis-cli ZREVRANGEBYSCORE hotzone:preview +inf 3 LIMIT 0 50 | wc -l | tr -d ' ')개"
docker exec "$REDIS_CONTAINER" redis-cli DEL hotzone:preview >/dev/null
