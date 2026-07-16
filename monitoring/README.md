# 관측 스택 (MSG-128) — viewport A/B 부하테스트

MSG-73 viewport 조회 전략 **A(정수 범위 스캔) vs B(GIST 공간쿼리)** 를 실측 부하로 판정하기 위한
임시 관측 스택. **대상(앱·DB) 밖**에서 돌린다 — 같은 박스에 올리면 관측자효과로 측정이 왜곡된다(MSG-128 D2).

```
[대상]  Spring app :8080/actuator/prometheus     PostgreSQL :5432
             ▲                                        ▲
             │ scrape                                 │ (postgres_exporter)
[관측/부하]  Prometheus ◀─ k6(remote-write)          Grafana ◀─ Prometheus
```

## 구성
- `docker-compose.yml` — Prometheus(9090) · Grafana(3000) · postgres_exporter(9187)
- `prometheus/prometheus.yml` — 앱·exporter scrape (5s), k6 remote-write 수신
- `grafana/provisioning/` — Prometheus 데이터소스 + 대시보드 자동 로드
- `grafana/dashboards/fillmap-viewport-ab.json` — 앱 p95·RPS·Hikari (k6·PG 패널은 첫 실행 후 추가)

---

## 1. 로컬 실행

```bash
# (1) DB(fillmap-postgres 5432) 가동 + 앱 기동
./gradlew bootRun            # 호스트 8080

# (2) 관측 스택
docker compose -f monitoring/docker-compose.yml up -d
#   Prometheus http://localhost:9090/targets  (fillmap-app·postgres UP 확인)
#   Grafana    http://localhost:3000          (익명 Viewer, admin/admin)
```

> ⚠️ 로컬은 k6→앱이 호스트 내부라 깨끗하지만, **원격 서버를 로컬에서 때리면 인터넷 RTT가 섞여 측정 오염**. 깨끗한 수치는 아래 임시 EC2에서.

## 2. 같은 리전 임시 EC2 (깨끗한 측정)

앱/DB와 **같은 AWS 리전**의 별도 인스턴스에서 이 스택 + k6를 돌린다.
- `prometheus/prometheus.yml`의 `fillmap-app` target을 **앱 사설 IP:8080**으로 교체.
- DB 사설 IP를 `POSTGRES_HOST`로 주입:
  ```bash
  POSTGRES_HOST=<db-사설IP> docker compose -f monitoring/docker-compose.yml up -d
  ```
- 앱 보안그룹에서 이 인스턴스 → 8080(actuator), DB → 5432 인바운드 허용.
- 부하 끝나면 `docker compose down` + 인스턴스 종료(임시).

---

## 3. 데이터 시드 (커밋 + VACUUM ANALYZE)

EXPLAIN 때의 "트랜잭션 내 시드" 한계를 극복한다 — **커밋된 데이터 + VACUUM**로 실제 상태 재현.
`psql`로 대상 DB에 실행(서울 격자 ~11.4만, 벤치 유저가 ~1/3 점령):

```sql
-- 격자 블록 (GridConstants: LAT_STEP=0.0009, LNG_STEP=0.00115)
INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
SELECT y || '_' || x, y, x,
  ST_SetSRID(ST_MakePoint((x+0.5)*0.00115, (y+0.5)*0.0009), 4326)::geography,
  ST_SetSRID(ST_MakeEnvelope(x*0.00115, y*0.0009, (x+1)*0.00115, (y+1)*0.0009, 4326), 4326)::geography
FROM generate_series(41577, 41888) y,      -- 서울 위도 (37.42~37.70)/0.0009
     generate_series(110226, 110591) x     -- 서울 경도 (126.76~127.18)/0.00115
ON CONFLICT (grid_id) DO NOTHING;

-- 벤치 유저
INSERT INTO users (provider, oid, email, nickname)
VALUES ('KAKAO', 'bench-oid', 'bench@fillmap.kr', 'bench')
ON CONFLICT (email) DO NOTHING;

-- 그 유저가 약 1/3 점령
INSERT INTO user_grids (user_id, grid_id, video_count)
SELECT (SELECT id FROM users WHERE email='bench@fillmap.kr'), g.grid_id, 1
FROM grids g
WHERE (g.grid_y + g.grid_x) % 3 = 0
ON CONFLICT (user_id, grid_id) DO NOTHING;

VACUUM ANALYZE grids;
VACUUM ANALYZE user_grids;
```

## 4. 부하 실행 (k6)

k6는 벤치 유저의 **JWT access token**이 필요하다(개인 도감 조회라 인증 필수).
- 토큰 확보: 앱의 `TokenProvider`로 bench 유저 토큰 발급(로컬 로그인/유틸) → `TOKEN` 주입.

```bash
# strategy A
TOKEN="<bench-jwt>" k6 run --out experimental-prometheus-rw \
  -e STRATEGY_DEFAULT=A load-test/k6/viewport-ab-benchmark.js

# strategy B (스크립트가 A·B 순차 시나리오로 둘 다 돌림 — 기본)
TOKEN="<bench-jwt>" K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
  k6 run --out experimental-prometheus-rw load-test/k6/viewport-ab-benchmark.js
```

> k6 스크립트(`load-test/k6/viewport-ab-benchmark.js`)는 A→B를 순차로 돌리며 전략별 지연/처리량을 분리 수집한다.

## 5. 판정

Grafana에서 strategy A/B의 **p95/p99 · RPS · 에러율**(k6) + **DB 쿼리시간·인덱스 스캔**(postgres_exporter) + **앱 지연·Hikari 포화**(actuator) 비교.
→ 낮은 지연·높은 처리량 쪽을 기본 경로로 채택하고 `?strategy` 고정/제거.
→ 결과·그래프를 `docs/MSG-73` 작업 로그 및 블로그 "본선" 섹션에 기록.

## 정리
```bash
docker compose -f monitoring/docker-compose.yml down    # 스택 내림
# (임시 EC2면 인스턴스도 종료)
```
