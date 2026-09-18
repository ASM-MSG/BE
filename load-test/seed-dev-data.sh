#!/usr/bin/env bash
# dev 밀도 테스트 데이터 시드 (Postgres grids/videos/user_grids + Redis hotzone).
# 사용: ./seed-dev-data.sh [n_general] [n_hot] [n_users]
set -euo pipefail
PG="${PG_CONTAINER:-fillmap-postgres}"
RD="${REDIS_CONTAINER:-fillmap-local-redis}"
N_GENERAL="${1:-2000000}"
N_HOT="${2:-400000}"
N_USERS="${3:-3000}"
SQL_DIR="$(cd "$(dirname "$0")" && pwd)"

echo ">> Postgres 시드: general=$N_GENERAL hot=$N_HOT users=$N_USERS"
time docker exec -i "$PG" psql -U user -d fillmap -q \
	-v n_users="$N_USERS" -v n_general="$N_GENERAL" -v n_hot="$N_HOT" \
	< "$SQL_DIR/seed-dev-data.sql"

echo ">> Redis 핫구역 시드 (Postgres 최근48h 신호 → UTC 6h 버킷)"
docker exec "$RD" sh -c 'redis-cli --scan --pattern "hotzone:*" | xargs -r redis-cli DEL' >/dev/null
docker exec "$PG" psql -U user -d fillmap -tA -F' ' -c "
SELECT floor(extract(epoch from created_at) / 21600)::bigint AS bucket, grid_id, count(*)
FROM videos
WHERE created_at >= (now() AT TIME ZONE 'UTC') - interval '48 hours'
GROUP BY 1, 2;" \
	| awk '{ printf "ZADD hotzone:%s %s %s\r\n", $1, $3, $2 }' \
	| docker exec -i "$RD" redis-cli --pipe
for b in $(docker exec "$RD" redis-cli --scan --pattern "hotzone:*"); do
	docker exec "$RD" redis-cli EXPIRE "$b" 194400 >/dev/null
done
echo ">> hotzone 버킷 $(docker exec "$RD" redis-cli --scan --pattern 'hotzone:*' | wc -l | tr -d ' ')개"
