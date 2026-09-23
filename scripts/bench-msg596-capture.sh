#!/usr/bin/env bash
# MSG-596 · Grafana 대시보드(도감 요약 부하)를 회차 시간 창으로 PNG 캡처한다. renderer 컨테이너(monitoring compose) 필요.
#   scripts/bench-msg596-capture.sh <startISO(UTC, 예 2026-09-16T10:51:40Z)> <label>   → blog-images/msg596/<label>.png
#   창은 시작 30초 전 ~ 시작 + 5분 30초. 시작 시각은 bench-msg596-stages.py 에 넣는 값과 같게.
set -euo pipefail
S=$(python3 -c "from datetime import datetime;print(int(datetime.fromisoformat('$1'.replace('Z','+00:00')).timestamp()))")
curl -sf -u admin:admin -o "blog-images/msg596/$2.png" \
	"localhost:3000/render/d/fillmap-collection-summary/x?from=$(( (S-30)*1000 ))&to=$(( (S+330)*1000 ))&width=1600&height=1950&kiosk&tz=Asia/Seoul"
echo "blog-images/msg596/$2.png"
