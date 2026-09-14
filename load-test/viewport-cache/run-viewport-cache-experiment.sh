#!/usr/bin/env bash
# 뷰포트 조회 캐시 전략 비교 실험 — 전략(none·local·redis·two-level) × 인스턴스 수(1·4) × 반복.
#
# 한 run 은 이렇다: API 컨테이너를 그 전략으로 새로 띄우고(= 콜드 스타트), 1초마다 네 인스턴스의
# /actuator/prometheus 를 합산해 DB 조회·캐시 적중을 기록하면서 k6 로 고정 RPS 를 DURATION 동안 건다.
# 남는 것: k6 요약(지연·실패율), DB 조회 횟수(전체·첫 30초), 캐시 적중률, pg_stat 교차 확인.
#
# 사용 (DB·Redis 컨테이너가 떠 있는 상태에서, 저장소 루트에서):
#   ./load-test/viewport-cache/run-viewport-cache-experiment.sh
#   MODES="none local" INSTANCES=1 REPS=1 RATE=300 DURATION=1m SKIP_BUILD=1 ./load-test/viewport-cache/run-viewport-cache-experiment.sh
# 결과: $OUT/runs/<mode>-x<n>-r<k>/{k6.json,k6.txt,metrics.csv,pg.txt} 와 $OUT/summary.tsv
set -euo pipefail
cd "$(dirname "$0")/../.."

MODES="${MODES:-none local redis two-level}"
INSTANCES="${INSTANCES:-1 4}"
REPS="${REPS:-3}"
RATE="${RATE:-300}"
DURATION="${DURATION:-3m}"
USERS="${USERS:-200}"
OCCUPY="${OCCUPY:-0.03}"
OUT="${OUT:-load-test/results/viewport-cache-$(date +%Y%m%d-%H%M)}"
COMPOSE=(docker compose -p fillmap-bench -f load-test/viewport-cache/docker-compose.bench.yml)
BASE_URL="http://localhost:8090"
PG_CONTAINER="${PG_CONTAINER:-fillmap-postgres}"

mkdir -p "$OUT/runs"
OUT="$(cd "$OUT" && pwd)"   # k6 파일 경로는 절대 경로로 넘긴다
echo -e "mode\tinstances\trep\trate\treqs\tachieved_rps\tfailed_pct\tmed_ms\tp95_ms\tp99_ms\tmax_ms\tdb_loads_measure\tdb_loads_warmup\tl1_hit\tl2_hit\tmiss\thit_pct\tpg_user_grids_scans" > "$OUT/summary.tsv"

# ── 0. 이미지 ──────────────────────────────────────────────────────────────
if [ "${SKIP_BUILD:-0}" != "1" ]; then
	./gradlew bootJar -x test -q
	docker build -q -t fillmap-bench . >/dev/null
fi

api_containers() { "${COMPOSE[@]}" ps -q api; }

wait_healthy() {
	local want="$1" deadline=$((SECONDS + 180))
	while :; do
		local ok=0
		for c in $(api_containers); do
			[ "$(docker inspect -f '{{.State.Health.Status}}' "$c")" = "healthy" ] && ok=$((ok + 1))
		done
		[ "$ok" -ge "$want" ] && return 0
		[ "$SECONDS" -gt "$deadline" ] && { echo "API 기동 실패 ($ok/$want healthy)" >&2; return 1; }
		sleep 2
	done
}

up_stack() {
	local mode="$1" n="$2"
	VIEWPORT_CACHE_MODE="$mode" "${COMPOSE[@]}" up -d --scale api="$n" --force-recreate --no-deps api >/dev/null 2>&1
	wait_healthy "$n"
	# nginx 는 기동 시 api 호스트를 한 번 풀므로 복제본이 바뀌면 다시 만든다.
	"${COMPOSE[@]}" up -d --force-recreate --no-deps nginx >/dev/null 2>&1
	sleep 2
	curl -fs "$BASE_URL/actuator/health" >/dev/null
}

# 네 인스턴스의 카운터 합 (db_loads, l1_hit, l2_hit, miss)
scrape() {
	local db=0 l1=0 l2=0 miss=0 body
	for c in $(api_containers); do
		body=$(docker exec "$c" curl -s localhost:8080/actuator/prometheus 2>/dev/null || true)
		db=$((db + $(echo "$body" | awk '/^viewport_db_loads_total/ {s+=$2} END {printf "%d", s+0}')))
		l1=$((l1 + $(echo "$body" | awk '/^viewport_cache_lookups_total\{.*l1_hit/ {s+=$2} END {printf "%d", s+0}')))
		l2=$((l2 + $(echo "$body" | awk '/^viewport_cache_lookups_total\{.*l2_hit/ {s+=$2} END {printf "%d", s+0}')))
		miss=$((miss + $(echo "$body" | awk '/^viewport_cache_lookups_total\{.*miss/ {s+=$2} END {printf "%d", s+0}')))
	done
	echo "$(date +%s),$db,$l1,$l2,$miss"
}

sampler() { # 1초마다 scrape → csv
	local file="$1"
	echo "t,db_loads,l1_hit,l2_hit,miss" > "$file"
	while :; do scrape >> "$file"; sleep 1; done
}

pg_idx_scan() {
	docker exec "$PG_CONTAINER" psql -U user -d fillmap -tAc \
		"SELECT coalesce(idx_scan,0) + coalesce(seq_scan,0) FROM pg_stat_user_tables WHERE relname='user_grids'"
}

# 개발용 토큰은 1시간짜리다. 24회 행렬은 그보다 길어서 한 번 받은 토큰이 중간에 만료돼 실패율 100% 가
# 나왔다(2026-09-14 실측). run 마다 새로 받는다 — 200명 로그인은 2초다.
issue_tokens() {
python3 - "$USERS" "$BASE_URL" "$OUT/tokens.json" <<'PY'
import json, sys, urllib.request
n, base, out = int(sys.argv[1]), sys.argv[2], sys.argv[3]
tokens = []
for i in range(1, n + 1):
	req = urllib.request.Request(f"{base}/api/auth/dev/social-login", method="POST",
		data=json.dumps({"provider": "KAKAO", "oid": f"vbench-{i}"}).encode(),
		headers={"Content-Type": "application/json", "X-Client-Type": "app"})
	with urllib.request.urlopen(req) as r:
		tokens.append(json.load(r)["data"]["accessToken"])
json.dump(tokens, open(out, "w"))
print(f"tokens: {len(tokens)}")
PY
}

# ── 1. 벤치 사용자·토큰·데이터 (none × 1 로 한 번 띄워서) ─────────────────────
up_stack none 1
issue_tokens
docker exec -i "$PG_CONTAINER" psql -U user -d fillmap -q -v occupy="$OCCUPY" \
	< load-test/viewport-cache/seed-viewport-bench.sql | tail -3

# ── 2. 실험 행렬 ───────────────────────────────────────────────────────────
for n in $INSTANCES; do
	for mode in $MODES; do
		for rep in $(seq 1 "$REPS"); do
			dir="$OUT/runs/$mode-x$n-r$rep"; mkdir -p "$dir"
			echo "=== $mode × $n · rep $rep ==="
			up_stack "$mode" "$n"                      # 매 rep 새 프로세스 = 콜드 스타트 포함
			issue_tokens >/dev/null
			pg0=$(pg_idx_scan)
			sampler "$dir/metrics.csv" & sp=$!
			TOKENS_FILE="$OUT/tokens.json" OUT="$dir/k6.json" k6 run --quiet -e BASE_URL="$BASE_URL" \
				-e RATE="$RATE" -e DURATION="$DURATION" -e LABEL="$mode-x$n-r$rep" \
				load-test/k6/viewport-cache-benchmark.js 2>&1 | tee "$dir/k6.txt" || true
			kill "$sp" 2>/dev/null; wait "$sp" 2>/dev/null || true
			pg1=$(pg_idx_scan)
			echo "user_grids scans: $pg0 -> $pg1 (delta $((pg1 - pg0)))" | tee "$dir/pg.txt"
			python3 - "$mode" "$n" "$rep" "$dir" "$((pg1 - pg0))" >> "$OUT/summary.tsv" <<'PY'
import csv, json, sys
mode, n, rep, d, pg = sys.argv[1:6]
k = json.load(open(f"{d}/k6.json"))
rows = list(csv.DictReader(open(f"{d}/metrics.csv")))
first, last = rows[0], rows[-1]
delta = lambda c: int(last[c]) - int(first[c])
t0 = int(first["t"])
warm_s = int(str(k.get("warmup", "30s")).rstrip("s"))
first30 = next((r for r in rows if int(r["t"]) - t0 >= warm_s), last)   # 워밍업 끝 시점
db_warm = int(first30["db_loads"]) - int(first["db_loads"])
db_measure = int(last["db_loads"]) - int(first30["db_loads"])
l1, l2, miss = delta("l1_hit"), delta("l2_hit"), delta("miss")
lookups = l1 + l2 + miss
hit = (l1 + l2) / lookups * 100 if lookups else 0
f = lambda x: "" if x is None else f"{x:.2f}"
print("\t".join(map(str, [mode, n, rep, k["rate"], k["reqs"], f(k["achieved_rps"]), f(k["failed_pct"]),
	f(k["med_ms"]), f(k["p95_ms"]), f(k["p99_ms"]), f(k["max_ms"]), db_measure, db_warm, l1, l2, miss, f"{hit:.1f}", pg])))
PY
		done
	done
done

"${COMPOSE[@]}" down >/dev/null 2>&1 || true
echo; echo "=== 요약 ($OUT/summary.tsv) ==="; column -t -s $'\t' "$OUT/summary.tsv"
