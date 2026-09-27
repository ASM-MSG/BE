# MSG-608 dev 실측 증거 (2026-09-27)

배포 직후 JIT 워밍업 전후 비교. 해석과 표는 `docs/spec/MSG-608.md` 작업 로그 2026-09-27 항목이 정본이다.
이미지 `msg608-b15fd3a-wt1`(이 브랜치 빌드), dev t3.small, 부하 = k6 `cluster-benchmark.js` 10배(줌아웃 150/s) 60초 + 카나리 5/s, 재기동 healthy 직후 시작.

| 파일 | 내용 |
|---|---|
| `rounds-table.txt` | 8회차 요약표(`summarize-rounds.py`가 k6 요약에서 생성) |
| `r0-off-jitlog-*` | 워밍업 끔 + JIT/클래스 로딩 로그 플래그 켬(1단계 원인 굳히기용, 플래그 오버헤드로 수치는 참고만) |
| `r1-off-*`, `r2-off-*` | 워밍업 끔 = "전" 기준 2회 |
| `r3-on-*`, `r4-on-*`, `r5-on-*` | 워밍업 켬 N=1,000 3회 |
| `r6-on2000-*` | N=2,000 |
| `r7-on200-*` | N=200 (효과 없음 — 곡선 아래쪽 확인) |
| `*-summary.json` | k6 요약(하네스 SUMMARY_JSON 형식, `cluster`·`k6` 두 키) |
| `*-actuator.txt` | ssh로 5초마다 읽은 actuator(Hikari·CPU·요청 수·서버 측 max) |
| `stage1-jit-analysis.txt` | `-Xlog:jit+compilation` 로그를 5초 구간·패키지별로 접은 결과(`scripts/analyze-jit-log.py`) |
| `stage1-jit.log.gz` | 그 원본(r0 회차, JVM 기동부터 약 5분) |
| `grafana-jvm-warmup-all-rounds.png` | 로컬 Grafana `fillmap-jvm-warmup`(URI 필터를 집계 2종으로 고친 뒤) |
| `grafana-cluster-r0-r4.png` | 로컬 Grafana `fillmap-cluster-zoomout` |

원시 k6 JSON(`--out json`)과 클래스 로딩 로그는 크기 때문에 넣지 않았다.
