# MSG-608 dev 실측 증거 (2026-09-27)

배포 직후 JIT 워밍업 전후 비교. 해석과 표는 `docs/spec/MSG-608.md` 작업 로그 2026-09-27 항목이 정본이다.
이미지 `msg608-b15fd3a-wt1`(이 브랜치 빌드), dev t3.small, 부하 = k6 `cluster-benchmark.js` 10배(줌아웃 150/s) 60초 + 카나리 5/s, 재기동 healthy 직후 시작.

| 파일 | 내용 |
|---|---|
| `rounds-table.txt` | 14회차 요약표(`summarize-rounds.py`가 k6 요약에서 생성, r8·r9는 같은 스크립트로 뒤에 추가) |
| `r0-off-jitlog-*` | 워밍업 끔 + JIT/클래스 로딩 로그 플래그 켬(1단계 원인 굳히기용, 플래그 오버헤드로 수치는 참고만) |
| `r1-off-*`, `r2-off-*` | 워밍업 끔 = "전" 기준 2회 |
| `r3-on-*`, `r4-on-*`, `r5-on-*` | 워밍업 켬 N=1,000 3회 |
| `r6-on2000-*` | N=2,000 |
| `r7-on200-*` | N=200 (효과 없음 — 곡선 아래쪽 확인) |
| `r8-off-hikari30-*` | 워밍업 끔 + Hikari 풀 10→30 (카카오페이 글의 1단계 재현 — 효과 없음, pending 내내 0) |
| `r9-off-c1only-*` | 워밍업 끔 + `-XX:TieredStopAtLevel=1`(C2 끔) — 벌점 37→19초로 절반, 목표 미달 |
| `r10-on2000-jitlog-*` | 워밍업 켬 N=2,000 + JIT 로그 플래그 — 2단계 로그용(수치는 참고만) |
| `r11-on2000-*` · `r12-on2000-*` | 워밍업 켬 N=2,000 반복 2·3회차 — p95 491(:02초 멈춤이 7초에 걸림, 제외 시 298)·234 |
| `r13-off-minidle10-*` | 워밍업 끔 + Hikari minimumIdle 2→10 — 효과 없음(카카오페이 글의 옵션 셋 중 마지막) |
| `stage2-jit-on-analysis.txt` · `stage2-jit-on.log.gz` | 워밍업 켠 채 뜬 JIT 로그(r10)와 창별 분석 — 워밍업 창 13,090건(tier3 목록이 r0 부하 창과 거의 같다), 부하 창 2,372건 중 tier4 1,010 |
| `*-summary.json` | k6 요약(하네스 SUMMARY_JSON 형식, `cluster`·`k6` 두 키) |
| `*-actuator.txt` | ssh로 5초마다 읽은 actuator(Hikari·CPU·요청 수·서버 측 max) |
| `stage1-jit-analysis.txt` | `-Xlog:jit+compilation` 로그를 5초 구간·패키지별로 접은 결과(`scripts/analyze-jit-log.py`) |
| `stage1-jit.log.gz` | 그 원본(r0 회차, JVM 기동부터 약 5분) |
| `grafana-jvm-warmup-all-rounds.png` | 로컬 Grafana `fillmap-jvm-warmup`(URI 필터를 집계 2종으로 고친 뒤) |
| `grafana-cluster-r0-r4.png` | 로컬 Grafana `fillmap-cluster-zoomout` |
| `grafana-jvm-warmup-before-r1.png` · `after-r6-n2000.png` · `*-session-2045-2206.png` | 회차별·세션 전체 Grafana 캡처(로컬 Prometheus 6시간 보존이라 재캡처 불가) |
| `p95-timeline-before-after.png` | k6 원시 결과를 초별 p95로 접은 전후 선 차트 (`scripts/bench-jvm-warmup-p95-chart.py`) |
| `p95-timeline-symptom-fixes.png` | 같은 차트에 r8(풀 30)·r9(C1만)를 더한 증상 처치 비교 |
| `stage1-jit-excerpt.txt` | JIT 로그에서 골라 뽑은 30줄(프레임워크 첫 컴파일 · 우리 코드 · tier 4 주인) |

원시 k6 JSON(`--out json`)과 클래스 로딩 로그는 크기 때문에 넣지 않았다.
