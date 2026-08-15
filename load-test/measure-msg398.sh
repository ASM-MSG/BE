#!/usr/bin/env bash
# MSG-398 미션 목록·진행도 응답 지연 실측 (스펙 "성능 측정" 절 2~4번).
#
# 무엇을 왜 재나:
#   cold-popup  — 캐시가 빈 상태(기동 직후)의 첫 요청. 그 요청 하나가 전국 스냅숏 재계산
#                 (코스 148개 경로 점 환산 포함)을 통째로 뒤집어쓴다. D1 판정선(300ms)의 대상.
#   cold-empty  — 같은 콜드지만 빈 결과 뷰포트(서해 원해)로 재서 직렬화·전송분을 뺀
#                 "재계산 단독"에 가까운 값을 얻는다. 앱을 재기동한 뒤 돌려야 콜드다.
#   warm        — 스냅숏이 살아 있는 상태에서 type=POPUP 강남역 0.5도(실측 최대 169건)를
#                 워밍업 10회 후 100회 재서 p50/p95/max. 판정선 p95 300ms.
#   progress    — warm 뷰포트의 팝업 id 전부를 /progress 한 번에 넘겨 재고(워밍업 10회 +
#                 30회), 같은 쿼리를 EXPLAIN (ANALYZE, BUFFERS)로 떠서 실행 계획을 남긴다.
#
# 사용:  앱을 local 프로파일로 기동한 직후 →  ./load-test/measure-msg398.sh cold-popup
#        앱 재기동 후                      →  ./load-test/measure-msg398.sh cold-empty
#        (콜드 측정이 끝난 뒤 아무 때나)    →  ./load-test/measure-msg398.sh warm
#                                            ./load-test/measure-msg398.sh progress
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PG_CONTAINER="${PG_CONTAINER:-fillmap-postgres}"
OID="msg398-bench"

# 강남역(37.4979, 127.0276) 중심 0.5도 — 스펙 성능 측정 1번의 최대 payload 지점.
POPUP_QS="type=POPUP&swLat=37.2479&swLng=126.7776&neLat=37.7479&neLng=127.2776"
# 서해 원해 — 미션 0건이라 응답 본문이 사실상 빈 배열. 재계산 비용만 남는다.
EMPTY_QS="type=EVENT&swLat=33.01&swLng=124.01&neLat=33.05&neLng=124.05"

token() {
	curl -s -X POST "$BASE_URL/api/auth/dev/social-login" \
		-H 'Content-Type: application/json' \
		-d "{\"provider\":\"KAKAO\",\"oid\":\"$OID\"}" \
		| python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])'
}

# 요청 1회의 서버 응답 시간(ms, curl time_total 기준).
time_ms() { # $1=url $2=token
	curl -s -o /dev/null -w '%{time_total}' -H "Authorization: Bearer $2" "$1" \
		| python3 -c 'import sys; print(f"{float(sys.stdin.read())*1000:.1f}")'
}

percentiles() { # stdin: ms 목록 → p50/p95/max
	sort -n | awk '{a[NR]=$1} END {
		p50=a[int(NR*0.50+0.5)]; p95=a[int(NR*0.95+0.5)];
		printf "n=%d  p50=%.1fms  p95=%.1fms  max=%.1fms\n", NR, p50, p95, a[NR] }'
}

MODE="${1:?사용법: measure-msg398.sh cold-popup|cold-empty|warm|progress}"
TOKEN=$(token)

case "$MODE" in
cold-popup)
	echo "[cold-popup] 기동 직후 첫 요청 — type=POPUP 강남역 0.5도 (재계산 + 169건 직렬화)"
	echo "  1회: $(time_ms "$BASE_URL/api/missions/active?$POPUP_QS" "$TOKEN") ms"
	echo "  (직후 웜 1회 대조): $(time_ms "$BASE_URL/api/missions/active?$POPUP_QS" "$TOKEN") ms"
	;;
cold-empty)
	echo "[cold-empty] 기동 직후 첫 요청 — 빈 결과 뷰포트 (≈ 재계산 단독)"
	echo "  1회: $(time_ms "$BASE_URL/api/missions/active?$EMPTY_QS" "$TOKEN") ms"
	echo "  (직후 웜 1회 대조): $(time_ms "$BASE_URL/api/missions/active?$EMPTY_QS" "$TOKEN") ms"
	;;
warm)
	echo "[warm] type=POPUP 강남역 0.5도 — 워밍업 10회 후 100회"
	for _ in $(seq 10); do time_ms "$BASE_URL/api/missions/active?$POPUP_QS" "$TOKEN" >/dev/null; done
	for _ in $(seq 100); do time_ms "$BASE_URL/api/missions/active?$POPUP_QS" "$TOKEN"; done | percentiles
	curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/missions/active?$POPUP_QS" | python3 -c "
import sys, json
raw = sys.stdin.buffer.read()
items = json.loads(raw)['data']
print(f'  건수/본문: {len(items)}건 {len(raw):,}B')"
	;;
progress)
	ids=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/missions/active?$POPUP_QS" \
		| python3 -c 'import sys,json; print(",".join(str(m["missionId"]) for m in json.load(sys.stdin)["data"]))')
	count=$(echo "$ids" | tr ',' '\n' | wc -l | tr -d ' ')
	echo "[progress] 강남역 팝업 ${count}건 id 를 한 번에 — 워밍업 10회 후 30회"
	for _ in $(seq 10); do time_ms "$BASE_URL/api/missions/progress?missionIds=$ids" "$TOKEN" >/dev/null; done
	for _ in $(seq 30); do time_ms "$BASE_URL/api/missions/progress?missionIds=$ids" "$TOKEN"; done | percentiles

	user_id=$(docker exec "$PG_CONTAINER" psql -U user -d fillmap -Atc \
		"SELECT id FROM users WHERE email = '$OID@dev.local'")
	echo ""
	echo "[EXPLAIN (ANALYZE, BUFFERS)] userId=$user_id, missionIds=${count}건"
	docker exec "$PG_CONTAINER" psql -U user -d fillmap -c "
		EXPLAIN (ANALYZE, BUFFERS)
		SELECT m.id, m.target_count,
			LEAST(COUNT(DISTINCT v.grid_id), m.target_count),
			BOOL_OR(um.user_id IS NOT NULL)
		FROM missions m
		LEFT JOIN mission_grids mg ON mg.mission_id = m.id
		LEFT JOIN videos v ON v.grid_id = mg.grid_id
			AND v.user_id = $user_id
			AND v.status <> 'DELETED'
			AND (m.start_at IS NULL OR v.recorded_at >= m.start_at)
			AND (m.end_at IS NULL OR v.recorded_at <= m.end_at)
		LEFT JOIN user_missions um ON um.user_id = $user_id AND um.mission_id = m.id
		WHERE m.id IN ($ids)
		GROUP BY m.id, m.target_count
		ORDER BY m.id"
	;;
*)
	echo "알 수 없는 모드: $MODE" >&2; exit 1 ;;
esac
