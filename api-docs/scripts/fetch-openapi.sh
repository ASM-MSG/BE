#!/usr/bin/env bash
# dev 서버의 OpenAPI 스펙을 사이트 스냅샷(docs/openapi.json)으로 받는다 (MSG-568).
#
#   DOCS_BASIC_AUTH=user:pass ./scripts/fetch-openapi.sh [URL]
#
# dev nginx 가 /v3/api-docs 에 basic auth 를 걸어 두므로 DOCS_BASIC_AUTH 가 필요하다.
# 배포 직후 앱이 뜨는 동안 5xx/연결 거부가 나므로 최대 2분 재시도한다. 경로 수가 너무 적으면
# 잘린 응답으로 보고 실패시킨다 — 빈 레퍼런스가 조용히 배포되지 않게.
set -euo pipefail
cd "$(dirname "$0")/.."
URL="${1:-https://api.fillmap.kr/v3/api-docs}"
OUT=docs/openapi.json
auth=()
[ -n "${DOCS_BASIC_AUTH:-}" ] && auth=(-u "$DOCS_BASIC_AUTH")

curl -fsS "${auth[@]}" --retry 20 --retry-delay 6 --retry-all-errors --max-time 30 "$URL" -o "$OUT.tmp"
paths=$(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))['paths']))" "$OUT.tmp")
if [ "$paths" -lt 50 ]; then
	echo "OpenAPI 경로가 ${paths}개뿐이라 잘린 응답으로 본다. 스냅샷을 갱신하지 않는다" >&2
	rm -f "$OUT.tmp"
	exit 1
fi
mv "$OUT.tmp" "$OUT"
echo "openapi.json 갱신: 경로 ${paths}개"
