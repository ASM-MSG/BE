#!/usr/bin/env bash
# 핫구역 조회 규모 감도 스윕 — 격자 수만 바꿔 가며 같은 부하를 반복한다.
#
# MSG-321 은 "격자가 만 단위로 늘면 재계산이 수십 ms 가 되고 그때는 다시 봐야 한다"로 끝났다.
# 이 스크립트는 그 "그때"가 어디인지 잰다. 규모마다 세 가지를 남긴다.
#   (1) 재계산 1회의 순수 Redis 비용 (measure-zunionstore.sh · 앱을 거치지 않음)
#   (2) 고정 RPS 에서의 지연 — 전체 p50/p99 와, TTL 주기로 접었을 때 만료 위상의 p99 (analyze-expiry.py)
#   (3) 재계산이 실제로 몇 번 돌았는지 (INFO commandstats 의 zunionstore 증분 — 만료 횟수와 같아야 정상)
#
# 사용:  앱(local 프로파일)과 Redis 가 떠 있는 상태에서
#   TOKEN=<jwt> ./load-test/hotzone-scale-sweep.sh
#   SIZES="10000 100000" RATE=200 DURATION=2m OUT=/tmp/sweep ./load-test/hotzone-scale-sweep.sh
# 결과: $OUT/<size>/{zunion.txt,k6.txt,k6.json,expiry.txt,cmdstat.txt} 와 $OUT/summary.tsv
set -euo pipefail

cd "$(dirname "$0")/.."
: "${TOKEN:?TOKEN(jwt) 필요 — POST /api/auth/dev/social-login}"
SIZES="${SIZES:-1000 10000 100000 1000000}"
RATE="${RATE:-200}"
DURATION="${DURATION:-2m}"
OUT="${OUT:-load-test/results/hotzone-scale-$(date +%Y%m%d-%H%M)}"
REDIS_CONTAINER="${REDIS_CONTAINER:-fillmap-local-redis}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
export AREA=wide REDIS_CONTAINER

mkdir -p "$OUT"
echo -e "samples\tunique_grids\tzunion_ms\tensure_ms\tmed_ms\tp99_ms\tmax_ms\tworst_phase_p99_ms\tphase_p99_median_ms\tratio\treproduce\tzunionstore_calls\treqs\tfailed_pct\tempty_pct" > "$OUT/summary.tsv"

zunion_calls() {
	docker exec "$REDIS_CONTAINER" redis-cli INFO commandstats | tr -d '\r' \
		| awk -F'[=,]' '/cmdstat_zunionstore/ {print $2} END {if (NR==0) print 0}' | tail -1
}

for size in $SIZES; do
	dir="$OUT/$size"; mkdir -p "$dir"
	echo "=== 표본 $size ==="
	# (1) 시드 + 재계산 비용. measure-zunionstore.sh 가 같은 srand(42) 로 시드하므로 아래 k6 도 같은 데이터를 본다.
	SIZES="$size" ./load-test/measure-zunionstore.sh | tee "$dir/zunion.txt"
	# 캐시를 비워 첫 만료 위상을 맞춘다. 앱 EVALSHA 캐시는 그대로다.
	docker exec "$REDIS_CONTAINER" redis-cli DEL hotzone:top >/dev/null
	before=$(zunion_calls)
	# (2) 고정 RPS 부하
	k6 run --quiet -e SCENARIO=expiry -e RATE="$RATE" -e DURATION="$DURATION" -e BASE_URL="$BASE_URL" \
		-e TOKEN="$TOKEN" -e LABEL="samples=$size" --out "json=$dir/k6.json" \
		load-test/k6/hotzone-benchmark.js 2>&1 | tee "$dir/k6.txt" || true
	after=$(zunion_calls)
	echo "zunionstore_calls_delta=$((after - before))" | tee "$dir/cmdstat.txt"
	# (3) TTL 주기로 접기
	./load-test/analyze-expiry.py "$dir/k6.json" | tee "$dir/expiry.txt"

	# 요약 한 줄
	python3 - "$size" "$dir" "$((after - before))" >> "$OUT/summary.tsv" <<'PY'
import re, sys
size, d, calls = sys.argv[1], sys.argv[2], sys.argv[3]
z = open(f"{d}/zunion.txt").read()
row = [l for l in z.splitlines() if re.match(r"^\s*[\d,]+\s+[\d,]+\s+[\d.]+\s+[\d.]+ ms", l)][-1].split()
unique, zun, ens = row[1].replace(',', ''), row[2], row[3]
e = open(f"{d}/expiry.txt").read()
g = lambda pat: re.search(pat, e).group(1)
med, p99, mx = g(r"중앙값 ([\d.]+)ms"), g(r"p99 ([\d.]+)ms · max"), g(r"max ([\d.]+)ms")
worst, pmed, ratio = g(r"최악 위상 \d+초: p99 ([\d.]+)ms"), g(r"위상 p99 중앙값 ([\d.]+)ms"), g(r"배율 ([\d.]+)")
rep = g(r"재현율: (\d+/\d+)")
k = open(f"{d}/k6.txt").read()
reqs = re.search(r"요청 (\d+)건", k).group(1); failed = re.search(r"실패율 ([\d.]+)%", k).group(1)
empty = re.search(r"빈 응답 ([\d.]+)%", k).group(1)
print("\t".join([size, unique, zun, ens, med, p99, mx, worst, pmed, ratio, rep, calls, reqs, failed, empty]))
PY
done

echo
echo "=== 요약 ($OUT/summary.tsv) ==="
column -t -s $'\t' "$OUT/summary.tsv"
