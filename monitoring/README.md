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

## 4. 부하 실행 (k6 · 시나리오 선택)

k6는 벤치 유저 **JWT**가 필요하다(개인 도감 조회라 인증). §3에서 만든 bench 유저로 로그인해 확보:
```bash
APP=http://localhost:8080   # EC2면 앱 사설IP:8080
TOKEN=$(curl -s -X POST $APP/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"bench@fillmap.kr","password":"Bench1234"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['body']['accessToken'])")
```
> bench 유저가 없으면 먼저 `POST /auth/signup` `{email,password:"Bench1234",nickname:"bench"}`.

시나리오(각 시나리오는 strategy **A → B 순차**로 돌려 비교):

| `SCENARIO` | 시나리오 | executor | 파라미터 |
|---|---|---|---|
| `s0` | 스모크 | per-vu-iterations | 1 VU × 5 |
| `s1` | **동시 사용자 ("100명 3번씩")** | per-vu-iterations | `VUS`명 × 3 (기본 100) |
| `s2` | 지속 부하(증가) | ramping-vus | 0→50→100→150 VU |
| `s3` | 목표 RPS 고정(개방형) | constant-arrival-rate | `RATE` req/s, 2분 (기본 300) |
| `s4` | 스트레스 | ramping-arrival-rate | 100→1000 req/s |

```bash
# k6 설치된 경우
TOKEN=$TOKEN k6 run -e SCENARIO=s1 -e BASE_URL=$APP \
  --out experimental-prometheus-rw load-test/k6/viewport-ab-benchmark.js

# k6 미설치 → grafana/k6 도커 (SCENARIO·TOKEN·BASE_URL은 k6 -e 플래그로)
docker run --rm --add-host host.docker.internal:host-gateway \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write \
  -v "$PWD/load-test/k6:/scripts" \
  grafana/k6 run \
    -e SCENARIO=s1 -e TOKEN="$TOKEN" -e BASE_URL=http://host.docker.internal:8080 \
    --out experimental-prometheus-rw /scripts/viewport-ab-benchmark.js
```
⚠️ **t3.small(2 vCPU)**: `s3`/`s4`는 부하를 낮춰 시작해 포화점을 찾는다 — 예 `-e SCENARIO=s3 -e RATE=150`.
> 스크립트는 A→B를 순차로 돌리고 strategy 태그로 지연/처리량을 분리 수집하며, 요약에 A/B 비교표(avg·p95·p99·max)를 출력한다.

## 5. 판정

Grafana에서 strategy A/B의 **p95/p99 · RPS · 에러율**(k6) + **DB 쿼리시간·인덱스 스캔**(postgres_exporter) + **앱 지연·Hikari 포화**(actuator) 비교.
→ 낮은 지연·높은 처리량 쪽을 기본 경로로 채택하고 `?strategy` 고정/제거.
→ 결과·그래프를 채택 근거로 `docs/MSG-73` 작업 로그에 남긴다.

## 정리
```bash
docker compose -f monitoring/docker-compose.yml down    # 스택 내림
# (임시 EC2면 인스턴스도 종료)
```
