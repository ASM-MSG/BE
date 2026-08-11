# 운영 관측 runbook (MSG-344)

prod와 dev 앱을 대상 서버 밖에서 상시 관측하는 스택의 운영 절차. 스택 파일 정본은 레포의
`monitoring/prod/` 이고, 실제 가동 위치는 fillmap-ai EC2(52.78.158.240, t3.small)다.
부하테스트용 임시 스택(`monitoring/`)과는 별개이며 서로 건드리지 않는다.

## 구성 요약

| 컨테이너 | 포트 | 역할 |
|---|---|---|
| Prometheus v2.54.1 | 9090 | prod 앱 관리 포트(10.0.1.24:8081)와 dev 앱 포트(10.0.1.24:8080, MSG-377)의 `/actuator/prometheus`를 15초 간격 scrape, 알림 규칙 평가. 둘 다 fillmap-dev EC2의 사설 IP다 |
| Alertmanager v0.27.0 | 9093 | 규칙 위반을 Slack incoming webhook으로 발송 |
| Grafana 11.3.0 | 3000 | 상시 대시보드 1장(fillmap-prod-overview), 익명 접근 차단 |

필요 환경변수 2개는 fillmap-ai 서버의 `monitoring/prod/.env`에만 둔다(레포에 값을 넣지 않는다,
`.env`는 gitignore). 최초 세팅은 예시 파일을 소유자 전용 권한으로 복사한 뒤 채운다. 웹훅
URL과 admin 비밀번호가 든 파일이라 처음부터 600으로 만들어 다른 계정이 읽을 틈을 없앤다:
`install -m 600 monitoring/prod/.env.example monitoring/prod/.env`

- `SLACK_WEBHOOK_URL`: Slack incoming webhook 주소. 성민이 발급, 채널은 #dev-monitoring 예정
- `GRAFANA_ADMIN_PASSWORD`: Grafana admin 비밀번호

둘 다 필수 보간(`:?required`)이라 비어 있으면 `up` 자체가 실패한다. 값 없이 조용히 뜨는 것을
막기 위한 장치다.

## 보관 기간: 30일

`--storage.tsdb.retention.time=30d`. 근거: 현재 시계열 규모(HTTP 지연 버킷 18개 x uri 조합에
JVM, Hikari를 더해 수천 개 수준)에 15초 간격이면 30일에 대략 1~2GB로, t3.small 기본 디스크로
충분하다. 한 달이면 배포 전후 비교와 주간 추세 확인에 모자라지 않고, 그 이상은 디스크만 먹는다.
데이터는 도커 볼륨(`prometheus-data`)에 있어 컨테이너 재기동에도 유지된다.

## 대시보드 접근법

웹 UI 3종(Grafana 3000, Prometheus 9090, Alertmanager 9093)은 전부 루프백에만 바인딩돼 있어
SSH 터널이 유일한 접근 경로다. Grafana와 Prometheus가 평문 HTTP라 외부 노출 자체를 막았다.
보안그룹으로 팀 IP를 열어 주는 방식은 적용 불가다(루프백 바인딩이라 포트를 열어도 안 닿는다).

1. 터널 연결: `ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -L 9093:localhost:9093 <user>@52.78.158.240`
2. Grafana: 브라우저에서 `http://localhost:3000`. 로그인 admin / `GRAFANA_ADMIN_PASSWORD` 값.
   익명 접근은 차단돼 있다
3. 대시보드 "FillMap 상시 관측 (MSG-344/377)" 1장: 상단 "대상" 변수로 fillmap-prod와
   fillmap-dev를 오간다. 패널은 기동 상태(up), 5xx 비율, p95와 p99, Hikari(active, pending,
   max), JVM heap, 프로세스 CPU
4. Prometheus 원본 조회는 `http://localhost:9090` (targets 상태는 `/targets`, 규칙은 `/rules`),
   Alertmanager는 `http://localhost:9093`

## 알림: 기준, 의미, 대응 (prod 8종 + dev 2종)

임계값 근거는 둘로 갈린다. 지연(p95 < 300ms, p99 < 800ms)은 MSG-134 ADR이 지도 뷰포트
조회에 대해 정한 SLO를 그대로 옮긴 것이라, p95와 p99 규칙은 `uri="/api/grids"` 정확 일치로
한정한다(접두 매칭이면 `/api/grids/{gridId}` 등 다른 라우트가 섞여 판정이 오염되고, 전 URI
합산이면 무관 엔드포인트가 오알림을 내거나 희석시킨다). 5xx 1%는 SLO 인용이 아니라 서비스
전반 오류율의 운영 기본 임계다(티켓 완료 조건의 5xx 비율 감시. 뷰포트로 좁히면 인증과 업로드
5xx를 놓친다). 값을 바꾸려면 각각 그 근거의 논의가 먼저다.

| 알림 | 기준 | 지속 | 심각도 | 의미와 대응 |
|---|---|---|---|---|
| AppDown | `up{job="fillmap-prod"} == 0` | 1분 | critical | 앱 중단 또는 수집 실패. 아래 "up==0 구분 절차"를 먼저 밟는다 |
| High5xxRate | 서비스 전반 5xx 요청 비율 > 1% | 5분 | critical | 서버 오류율이 운영 임계를 넘었다. Grafana에서 어느 시점부터인지 보고, 앱 로그(`journalctl -u fillmap-prod`)에서 스택트레이스를 찾는다. 직전 배포가 있었으면 롤백을 우선 검토 |
| ViewportLatencyP95High | `uri="/api/grids"` p95 > 0.3s | 10분 | warning | 뷰포트 조회 대부분이 느려졌다. Hikari 패널과 CPU 패널을 같이 본다. DB 병목이면 HikariPending이 함께 떴을 가능성이 크다 |
| ViewportLatencyP99High | `uri="/api/grids"` p99 > 0.8s | 10분 | warning | 뷰포트 조회 꼬리 지연 악화. 다른 uri도 같이 느린지 Prometheus에서 uri 라벨로 나눠 본다 |
| HikariPending | `hikaricp_connections_pending > 0` | 2분 | critical | 커넥션 풀 고갈로 요청이 대기 중. DB 병목의 가장 이른 신호다. 느린 쿼리(pg_stat_activity)와 풀 크기(max 20)를 확인한다 |
| HikariSaturation | active / max > 0.9 | 5분 | warning | 풀이 거의 찼다. pending이 뜨기 전의 전조. 트래픽 증가인지 커넥션 누수인지 가른다 |
| JvmHeapHigh | heap used / max > 0.9 | 10분 | warning | GC 압박, OOM 전조. heap 패널에서 톱니(정상 GC)인지 우상향(누수 의심)인지 본다 |
| CpuHigh | `process_cpu_usage > 0.9` | 10분 | warning | 앱 프로세스가 CPU를 다 쓴다. 트래픽 급증인지 특정 요청의 폭주인지 5xx, 지연 패널과 같이 본다 |

dev 알림은 최소 구성 2종이다(MSG-377). 지연 SLO와 5xx 비율을 dev에 안 다는 이유: 트래픽이
팀원뿐이라 분모가 작아 요청 몇 건으로도 비율이 널뛰어 소음이 된다.

| 알림 | 기준 | 지속 | 심각도 | 의미와 대응 |
|---|---|---|---|---|
| DevAppDown | `up{job="fillmap-dev"} == 0` | 3분 | critical | develop 푸시마다 일어나는 배포 재시작보다 긴 다운이라 크래시 루프를 의심한다(과거 32시간 크래시 루프에도 CD는 초록불이었다). 아래 "up==0 구분 절차"를 dev 값으로 바꿔 밟는다 |
| DevJvmHeapHigh | heap used / max > 0.9 | 10분 | warning | dev 박스(t3.small)는 앱과 DB, Redis, Kafka 컨테이너가 동거해 메모리가 빠듯하다. heap 압박은 OOM 크래시 루프의 전조 |

해소되면 같은 채널로 resolved 알림이 온다(`send_resolved: true`).

## up==0 구분 절차 (AppDown, DevAppDown 수신 시)

DevAppDown이면 아래 절차에서 포트 8081을 8080으로, fillmap-prod라는 이름(systemd 서비스명,
Prometheus 타깃명)을 fillmap-dev로 바꿔 같은 순서로 밟는다. 단 2번의 구성요소별 본문은 dev에
없다. `show-details: always`가 prod 프로파일 전용이라 dev의 health 응답은 상태 한 줄뿐이다.
dev에서 503이 나오면 본문 대신 앱 로그(`journalctl -u fillmap-dev`)로 어느 구성요소가
병들었는지 확인한다.

`up == 0`은 앱 중단과 수집 실패(네트워크, 보안그룹)를 구분하지 못한다. 알림 규칙이 아니라
이 절차로 가른다. 핵심: 수집 서버에서 앱으로 가는 curl은 Prometheus의 scrape와 같은 경로라,
경로나 보안그룹 장애일 때도 똑같이 실패한다. 그 실패만 보고 앱이 죽었다고 단정해 재시작하면
살아 있는 프로세스를 오판으로 죽이는 셈이다. 프로세스 생사 판정은 반드시 앱 호스트 로컬에서
한다.

1. 수집 서버(fillmap-ai)에 SSH 접속해 수집 경로의 상태를 본다:
   `curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://10.0.1.24:8081/actuator/health`
2. 503이면 앱은 떠 있으나 구성요소(DB 또는 Redis)가 병들었다.
   `curl http://10.0.1.24:8081/actuator/health`의 본문에서 어느 컴포넌트가 DOWN인지 본다
   (`show-details: always`라 본문에 구성요소별 상태가 실린다)
3. 200이면 앱도 경로도 정상이고 수집 스택 쪽 문제다:
   - Prometheus 컨테이너 상태 확인: `docker compose -f monitoring/prod/docker-compose.yml ps`
   - `http://localhost:9090/targets`에서 fillmap-prod 타깃의 에러 메시지 확인
4. 그 외(연결 불가 000 포함)면 아직 앱 중단으로 단정하지 않는다. 000은 죽음이 아니다:
   경로나 보안그룹 장애일 수도 있고, DB 장애로 health 응답이 Hikari 커넥션 대기(최대
   30초)에 걸리면 살아 있는 JVM도 3초 타임아웃에 000을 준다. 앱 호스트(10.0.1.24)에 SSH
   접속해 로컬로 확인한다:
   - 프로세스 상태부터 본다: `systemctl is-active fillmap-prod`
   - health를 긴 타임아웃으로 본다(35초는 Hikari connection-timeout 30초를 덮는 값이다):
     `curl -s -o /dev/null -w '%{http_code}' --max-time 35 http://localhost:8081/actuator/health`
   - is-active가 active이고 curl에서 200이나 503이 나오면 프로세스는 살아 있다.
     재시작하지 않는다. 문제는 수집 서버와 앱 사이 경로다: 타깃이 사설 IP 10.0.1.24인지
     (`monitoring/prod/prometheus/prometheus.yml`), 보안그룹 8081 인바운드 소스가
     fillmap-ai-sg(sg-05e4d5c7ddfa6c438)인지 확인한다. 소스 그룹 매칭은 같은 VPC 사설
     경로에서만 성립한다(공인 IP 타깃이면 8081 인바운드에 안 잡혀 scrape가 막힌다).
     인스턴스 교체로 사설 IP가 바뀌면 이 파일과 prometheus.yml을 같이 갱신한다
   - 재시작 처방은 is-active가 active가 아니거나(inactive, failed) 35초 curl도 실패할
     때만 쓴다: `journalctl -u fillmap-prod -n 100 --no-pager`로 원인 확인 후
     `sudo systemctl restart fillmap-prod`, health 200 재확인

## 스택 재기동법

fillmap-ai 서버의 레포 체크아웃 위치에서(시크릿은 위 "구성 요약"대로 `monitoring/prod/.env`에
채워져 있어야 한다):

```bash
docker compose -f monitoring/prod/docker-compose.yml up -d
```

설정 변경 반영은 바뀐 파일의 종류에 따라 다르다. 설정 파일들은 bind mount라 파일만 바뀌면
`up -d`가 프로세스를 건드리지 않는다는 점에 주의:

- `docker-compose.yml` 자체가 바뀐 경우: 레포에서 pull 후 `up -d` (바뀐 컨테이너만 재생성된다)
- `prometheus.yml`이나 `alert-rules.yml`이 바뀐 경우: pull 후
  `docker compose -f monitoring/prod/docker-compose.yml exec prometheus kill -HUP 1`
  (설정 리로드) 또는 `restart prometheus`
- `alertmanager.yml`이 바뀐 경우: pull 후
  `docker compose -f monitoring/prod/docker-compose.yml restart alertmanager`
  (기동 시 sed 치환을 다시 거쳐야 해서 리로드 신호로는 안 되고 재시작이 필요하다)
- `.env` 값이 바뀐 경우: `up -d` (compose가 환경 변화를 감지해 해당 컨테이너를 재생성한다)

반영 확인:

- Prometheus `/targets`에서 fillmap-dev 타깃 UP(fillmap-prod는 앱 가동 전까지 DOWN이 정상),
  `/rules`에서 규칙 10종(prod 8종 + dev 2종) 로드 (규칙은 Prometheus가
  로드하고 평가한다. Alertmanager가 아니다)
- Alertmanager 라우팅은 별도로 확인한다: UI(`http://localhost:9093/#/status`)의 config에
  slack receiver가 보이는지, 또는
  `docker compose -f monitoring/prod/docker-compose.yml exec alertmanager amtool config show`

재시작 정책이 unless-stopped라 서버 재부팅 시 자동으로 되살아난다(수동으로 stop한 경우 제외).
Alertmanager의 silence와 알림 상태는 `alertmanager-data` 볼륨에 있어 컨테이너 재생성에도
보존된다(아래 "prod 미가동 기간" silence가 재생성마다 사라지지 않게 하기 위한 장치).

### Grafana 비밀번호 회전

`GF_SECURITY_ADMIN_PASSWORD`는 첫 기동 때 admin 계정을 만드는 시드값일 뿐이다. 계정이 이미
`grafana-data` 볼륨에 있으면 이후 `.env`만 바꾸고 `up -d` 해도 옛 비밀번호가 그대로 남는다.
env 변경만으로는 회전이 안 된다. 회전은 컨테이너 안에서 직접 한다:

```bash
docker compose -f monitoring/prod/docker-compose.yml exec grafana grafana-cli admin reset-admin-password '새비밀번호'
```

실행 후 `.env`의 `GRAFANA_ADMIN_PASSWORD`도 같은 값으로 맞춰 둔다. 이후 볼륨을 지우고 새로
시드되는 경우가 아니면 env가 적용되는 일은 없지만, 값이 어긋나 있으면 다음 사람이 env 값으로
로그인을 시도하다 헛돈다.

## prod 미가동 기간의 타깃 DOWN 조치

2026-08-11 실측 기준 prod 앱은 아직 어디에도 상시 구동돼 있지 않다(api.fillmap.kr 트래픽은
dev 앱이 받는다). 따라서 스택 가동 직후 fillmap-prod 타깃이 DOWN인 것이 정상이고, 방치하면
AppDown 알림이 계속 울린다. 조치는 silence다:

1. Alertmanager UI(SSH 터널 후 `http://localhost:9093`, 위 "대시보드 접근법")에서 New Silence 생성
2. Matcher: `alertname="AppDown"`, `job="fillmap-prod"`
3. 만료(Ends at)는 prod 가동 예정일로 잡는다. 예정일이 없으면 2주 단위로 걸고 갱신한다.
   무기한 silence는 만들지 않는다. prod가 떴는데 silence가 남아 있으면 실제 중단을 놓친다
4. prod 앱이 뜨면 silence를 만료(Expire)시키고, `sudo systemctl stop fillmap-prod`로
   실중단을 재현해 5분 안에 Slack 알림이 오는지 검증한다(스펙 성공 기준 3). 확인 후
   `sudo systemctl start fillmap-prod`로 재기동

타깃을 prometheus.yml에서 주석 처리하는 방법도 있으나 쓰지 않는다. 서버의 파일이 레포와
어긋나고, 되살리는 것을 잊으면 관측 자체가 빠진 채 굴러가기 때문이다. silence는 만료가 있어
잊어도 되살아난다.

## fillmap-ai 메모리 여유 실측 기록

스택 배치 직후 `free -m` 결과를 여기에 남긴다. 배치 전 실측은 가용 1070MiB였고, 컨테이너
메모리 캡 합계는 896MiB(Prometheus 512 + Grafana 256 + Alertmanager 128)다.

| 날짜 | 시점 | available (MiB) | 기록 |
|---|---|---|---|
| 2026-08-11 | 배치 전 | 1070 | 스펙 실측값 |
| (기록 예정) | 배치 직후 | | `free -m` 출력 붙여넣기 |
