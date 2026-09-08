# 트랜잭션 대량 데이터 실측 2026-09-08

- 기준 커밋: `3fde2f19`, 브랜치 `feature/MSG-583-backend-audit-skill`. 애플리케이션 코드 수정 없음.
- 범위: 이전 [tx 재감사](/Users/ssomae/seongmin/coding/FillMap/docs/audit/2026-09-08-backend-audit-tx.md)의 행사 알림·축제 시드·미션 캐시 조회를 실제 서비스 코드로 실행했다.
- 환경: 로컬 PostgreSQL 16.4/PostGIS, Apple M5·메모리 32GiB, JDK 21.0.2·테스트 힙 2GiB·Hikari 2개. DB shared_buffers 128MB. JaCoCo 포함.
- 데이터: 별도 DB `fillmap_tx_bench_20260908`. 최대 구독자 5만 명, 미션 3천 개·판정격자 24만 3천 개, 기존 로컬 행정동 3,558개 복사. 운영·dev 접속 없음.

## 1. 요약

**한 트랜잭션 안의 반복 작업으로 긴 연결 점유와 잠금 대기가 실제로 발생했다.** 우선 검토 대상은 행사 알림이다. 개선 코드를 적용한 전후 비교는 수행하지 않았다.

| 대상 | 큰 규모에서 확인한 값 | 개선 검토 지점 |
|---|---|---|
| 행사 시작 알림 5만 명 | 처리 **400.387초**, 경쟁 잠금 획득 **334.367초** | 구독자 전체 엔티티 로딩 뒤 개별 INSERT·flush 반복, 전역 잠금 유지 |
| 축제 최초 적재 3천 개 | **152.144초**, prepare **492,002회**, 연결 1개 계속 점유 | 격자별 존재 SELECT와 INSERT 반복, 전체 파일을 하나의 TX로 처리 |
| 미션 캐시 무효화 뒤 3천 개 조회 | 중앙값 **2.501초**, prepare **3,002회** | 전체 활성 데이터 재조립과 미션별 행정동 조회 |

우선순위: 행사 알림은 실제 장시간 경합을 재현해 P1 검토 대상으로 올린다. 시드·조회는 실측 비용을 확인한 P2다. 운영에서 같은 규모가 존재하거나 SLO를 위반했다는 의미는 아니다.

## 2. 측정 방법

- 자동 스케줄을 취소하고 한 번에 한 벤치만 실행했다. fixture 생성·삭제·ANALYZE·검증 조회는 시간·prepare·풀 점유 집계 밖이다.
- 미션은 실제 `FestivalMissionSeeder.run` 프록시를 호출했다. 신규 적재는 규모별 1회, 변경 없는 재적재와 조회는 각각 3회다. 결과 행 수가 N개·81N개인지 확인했다.
- 알림은 실제 repository·command service·transaction manager를 주입한 `EventNotificationScheduler.tick`을 호출했다. 발송과 종료 구독 정리는 각각 별도 TX다. 알림 N개·중복 없는 사용자 N명·구독 N명·두 TX 커밋을 검증했다.
- 1천·1만 명 알림은 준비 실행 1회를 제외하고 신규 생성 3회, 중복 재실행 1회다. 5만 명은 첫 실행만 완료·검증했다. 이후 반복을 의도적으로 중단했으므로 그 전체 Gradle 실행은 SIGTERM 143으로 실패 처리됐다. 완료된 샘플의 정합성 확인과 전체 테스트 성공을 구분한다.
- 최종 벤치는 5만 명을 단발 실행하도록 바꿨다. 변경된 벤치 파일은 미션 10개·구독자 1천 명으로 다시 실행해 **테스트 2건 통과**를 확인했다. 큰 규모 전부를 다시 실행한 것은 아니다.

## 3. 실측 결과

### 3.1 행사 시작 알림

| 구독자 | 신규 생성 시간 | 첫 발송 TX 시간 | prepare 횟수 | 비고 |
|---|---:|---:|---:|---|
| 1,000명 | 0.486초 | 0.483초 | 1005 | 3회 중앙값, 범위 0.385~0.503초 |
| 10,000명 | 17.262초 | 17.259초 | 10005 | 3회 중앙값, 범위 16.997~17.974초 |
| 50,000명 | 400.387초 | 400.379초 | 50005 | 단발 진단값, JFR·잠금 경쟁 포함 |

중복 실행도 비용이 거의 남았다. 1천 명은 0.590초, 1만 명은 16.086초였고 각각 1,005회·10,005회 prepare가 발생했다. 이미 기록된 알림을 INSERT 단계에서 충돌 처리하므로 반복 전체를 피하지 못한다.

5만 명 실행에서는 Hikari 연결 반환이 2회, 점유 합계가 400.376초였다. 대부분 첫 발송 TX이며 정리 TX는 약 8ms였다. 앱 TX 외곽 시간과 실제 연결 점유 값이 같은 방향을 보였다.

**경합 검증:** 별도 연결 PID `37455`에서 같은 `event_seed` 잠금을 요청했다. `pg_blocking_pids(37455)`가 발송 연결 `37215`를 반환했고, 잠금 SELECT가 334.367초 뒤 완료됐다. 발송 시작보다 늦게 요청했으므로 400초 전체를 기다린 것은 아니다.

**프로파일:** 30초 JFR에서 작업 스레드 실행 샘플 1,912개 중 1,465개(76.6%)에 flush 관련 프레임이 있었다. 별도 스레드 덤프에서도 `NativeQueryImpl.prepareForExecution → SessionImpl.flush → prepareEntityFlushes/cascadeOnFlush` 경로를 확인했다. 반복적인 변경 검사가 실제로 실행됨을 확인한 것이며, 76.6%를 전체 처리 시간의 비율로 해석하지 않는다.

### 3.2 축제 시드 적재

| 미션 / 판정격자 | 최초 적재 1회 | prepare | 연결 점유 / 반환 | 변경 없는 재적재 3회 중앙값 |
|---|---:|---:|---|---:|
| 100 / 8,100 | 7.557초 | 16,402 | 7.557초 / 1회 | 43.307ms |
| 1,000 / 81,000 | 54.328초 | 164,002 | 54.328초 / 1회 | 146.044ms |
| 3,000 / 243,000 | 152.144초 | 492,002 | 152.144초 / 1회 | 436.427ms |

재적재 prepare는 규모와 무관하게 3회였지만 결과 엔티티를 읽고 처리하는 시간은 증가했다. 최초 적재와 기존 데이터 갱신을 같은 비용으로 취급하면 안 된다.

**DB 로그 확인:** 미션 10개 소규모 재실행에서 한 커밋 구간의 실제 업무 execute는 1,642회였다. 격자 SELECT 810회 + 격자 INSERT 810회 + 미션 INSERT 10회 + 미션 UPDATE 10회 + 나머지 2회다. 드라이버 메타데이터와 BEGIN·COMMIT, parse·bind 중복을 제외했다. 큰 규모의 `164N+2` prepare와 같은 패턴이다.

[FestivalMissionSeeder.insertMission](/Users/ssomae/seongmin/coding/FillMap/src/main/java/com/msg/fillmap/mission/seed/FestivalMissionSeeder.java:211)의 `saveAll` 한 번을 INSERT 한 문장으로 볼 수 없다. [MissionGrid](/Users/ssomae/seongmin/coding/FillMap/src/main/java/com/msg/fillmap/mission/entity/MissionGrid.java:30)는 생성 시 복합 ID를 가진다. 실제 실행 로그에서 신규 격자도 행별 존재 조회 뒤 저장됨을 확인했다.

### 3.3 미션 조회

뷰포트는 위도 36.0~36.1·경도 127.0~127.1의 작은 범위다. 그래도 캐시 재생성은 전체 활성 미션을 읽는다. 각 실행 전 `invalidateSnapshot()`을 호출한 상태와 직후 캐시가 채워진 상태를 비교했다.

| 활성 미션 / 판정격자 | 캐시 무효화 뒤 3회 중앙값(범위) | prepare | 연결 점유 중앙값 | 웜 조회 중앙값 |
|---|---:|---:|---:|---:|
| 100 / 8,100 | 172.103ms (141.371~234.346) | 102 | 171ms | 0.295ms |
| 1,000 / 81,000 | 792.080ms (791.012~826.769) | 1,002 | 791ms | 0.515ms |
| 3,000 / 243,000 | 2,500.899ms (2,436.516~2,552.391) | 3,002 | 2,492ms | 0.826ms |

웜 조회 prepare는 모두 0회다. [recompute](/Users/ssomae/seongmin/coding/FillMap/src/main/java/com/msg/fillmap/mission/service/impl/MissionQueryServiceImpl.java:522)의 미션·격자 일괄 조회 2회 뒤 미션별 행정 귀속 조회가 추가돼 N+2가 됐다. 이번에는 행정동 3,558개를 넣었으므로 빈 지역 테이블만 대상으로 한 수치가 아니다.

### 3.4 SQL 로그와 계측 검증

최종 소규모 실행에서 알림 발송 TX도 업무 execute 1,003회(INSERT 1,000회+조회·잠금 3회)를 확인했다. 정리 TX는 별도다. DB 로그를 켠 이 실행의 시간은 위 성능 표에 섞지 않았다.

- DB 로그: [원본](/tmp/backend-audit/bench-final-db.log), 구간은 results.txt에 PID·시작/끝 줄 번호로 보존했다.
- 대규모 원문: [미션](/tmp/backend-audit/mission-bench-measured.txt), [알림](/tmp/backend-audit/event-bench-measured.txt), [잠금](/tmp/backend-audit/event-lock-measured.txt).
- 예비 미션 실행은 통계 카운터가 비활성이고 주기 조회가 섞여 표에서 제외했다. 최종 대규모 표는 통계 카운터를 직접 활성화하고 예약 작업을 취소한 실행이다.
- 종료 시 합성 사용자·미션·격자·알림·행사·구독 0건을 확인하고 별도 DB를 삭제했다. 원본 `fillmap`에는 조회만 수행했으며 전후 테이블 행 수 통계도 같았다. DB SQL 로깅은 `-1`로 복구했다.

## 4. 개선 방향

| 단계 | 행사 알림 | 축제 시드 | 미션 조회 |
|---|---|---|---|
| 증상 | 구독자가 많을수록 발송 TX와 행사 전역 잠금이 오래 유지됨 | 최초 적재가 수분 동안 연결 하나를 점유 | 애플리케이션 캐시 무효화 뒤 첫 조회가 수초 소요 |
| 가설 | 구독자별 INSERT 왕복, 영속성 컨텍스트 반복 검사 | 격자별 조회·저장과 전체 입력을 묶는 TX | 전체 미션·격자 로딩, 미션별 행정동 조회 |
| 검증 | N+5 prepare, 실제 경쟁 잠금 대기, 작업 스레드의 flush 스택 | 164N+2 prepare, 소규모 DB 실행 로그로 SELECT·INSERT 패턴 대조 | N+2 prepare, 캐시가 채워진 조회는 0 |
| 결론 | 네트워크 호출이 없어도 긴 잠금 대기가 발생함. flush 반복은 프로파일로 확인했으나 원인별 시간 비중은 미분리 | SQL 왕복을 늘리는 저장 방식과 하나의 긴 TX가 함께 존재 | 작은 뷰포트를 요청해도 전체 활성 데이터로 캐시를 만들고 개별 지역을 조회함 |
| 개선 후보 | 필요한 구독자 필드만 읽는 projection과 묶음 INSERT 검토. 청크 커밋은 일정 개정·중복 방지 계약을 먼저 보존 | 신규 격자의 불필요한 존재 조회 제거와 배치 적재를 먼저 검토. 미션 단위 커밋은 대표격자 FK·중간 상태 노출 조건 필요 | 행정 귀속 일괄 조회·이전 캐시 결과 재사용, 데이터 조회 후 순수 조립의 TX 분리 검토 |

**이번에는 애플리케이션을 개선하지 않았다.** 위 대안을 적용한 전후 비교는 없으므로 예상 개선율을 제시하지 않는다. 특히 flush 모드만 전역 변경하거나 행사 잠금만 제거하면 현재 원자성 계약을 깨뜨릴 수 있다.

## 5. 이전 감사 대비

이전에는 정적 P2 후보였다. 이번에는 행사 알림의 장시간 잠금 대기, 시드 적재의 긴 연결 점유, 캐시 무효화 뒤 조회 비용을 로컬 합성 데이터로 재현했다. 운영 장애 발생이나 운영 응답 SLO 위반을 입증한 것은 아니다. 실제 개선은 0건이며 측정 근거가 추가됐다.

## 6. 재현 방법

벤치는 기본 테스트 소스에 포함되지 않는다. 아래 Gradle 초기화 파일을 지정할 때만 컴파일·실행한다. 기존 PostgreSQL 컨테이너 안에 `fillmap_tx_bench_20260908`라는 별도 DB가 필요하다. 테스트 자체도 DB 이름을 검사한다.

- [벤치 초기화 설정](/Users/ssomae/seongmin/coding/FillMap/load-test/tx-workload/bench.init.gradle)
- [시드·조회 벤치](/Users/ssomae/seongmin/coding/FillMap/load-test/tx-workload/MissionWorkloadBenchmark.java)
- [알림 벤치](/Users/ssomae/seongmin/coding/FillMap/load-test/tx-workload/EventFanoutBenchmark.java)
- [측정 원문과 검증 기록](/Users/ssomae/seongmin/coding/FillMap/load-test/tx-workload/results.txt)

스키마가 없는 DB로 첫 기동하면 Flyway가 마이그레이션을 적용한다. 본 측정은 여기에 기존 로컬 `regions` 3,558개를 복사한 상태다. 다른 서비스 데이터는 복사하지 않았다. 같은 조건을 재현하려면 동일 마이그레이션의 스키마·Flyway 이력과 regions 데이터를 별도 DB에 준비한다.

```bash
./gradlew -I load-test/tx-workload/bench.init.gradle test --tests '*MissionWorkloadBenchmark'
```

```bash
./gradlew -I load-test/tx-workload/bench.init.gradle test --tests '*EventFanoutBenchmark'
```

부분 실행은 `BENCH_SIZES=100` 또는 `BENCH_FANOUT_SIZES=1000` 환경변수로 선택한다. 5만 명은 수분이 걸려 최종 벤치에서 단발 실행으로 제한했다. 알림 정합성 검증은 중복 없는 사용자별 N개 기록과 두 TX의 커밋을 확인한다. 네트워크 알림 발송은 실행하지 않는다.

경쟁 잠금은 발송 중 다른 psql 연결에서 같은 SQL을 실행했다. 별도 관찰 연결의 `pg_blocking_pids()`로 실제 보유자에게 차단됨을 확인했다.

```sql
BEGIN;
SET LOCAL lock_timeout = '600s';
SELECT pg_advisory_xact_lock(hashtextextended('event_seed', 0));
COMMIT;
```

## 7. 해석 범위와 한계

- 데이터 규모를 늘린 서비스 직접 호출 벤치다. 동시 HTTP 사용자 5만 명 테스트가 아니다. 동시성은 알림 TX와 경쟁 잠금 연결 1개만 측정했다.
- 신규 시드와 5만 명 알림은 규모별 1회다. 평균·p95로 해석하지 않는다. 재시드·조회는 3회, 1천·1만 명 신규 알림은 준비 실행을 뺀 3회다.
- cold는 애플리케이션 캐시 무효화다. PostgreSQL·OS 캐시는 비우지 않았다. TTL 만료 시 이전 세대를 재사용하는 경로와 다를 수 있다.
- `hibernate_prepares`는 Hibernate의 JDBC statement 준비 횟수다.[^prepare] DB execute 수와 자동으로 같다고 보지 않는다. 소규모 DB 로그 검증은 별도로 구분했다.
- 연결 점유는 Hikari timer 누적 시간 차이다.[^pool] 알림은 발송·정리 두 TX가 순차로 연결을 빌리므로 합계를 2로 나눈 값을 발송 TX 시간으로 쓰지 않았다. ms 단위 반올림 때문에 짧은 웜 조회는 0으로 기록될 수 있다.
- 잠금 획득 시간에는 요청 이후 남은 대기와 SQL·왕복이 포함된다. TX 시작부터 잠금을 보유한 전체 시간과 다르다.[^lock]
- 5만 명 실행 중 JFR 30초와 잠금 관찰을 추가했다. 단발 진단 수치이며 계측 없는 순수 처리 시간과 동일하지 않다. 스택 샘플 비율을 벽시계 시간 비율로 바꾸지 않았다.[^flush]
- 로컬 M5·2GB 테스트 힙·Hikari 2개·JaCoCo가 붙은 JVM 결과다. dev의 t3.small이나 운영 처리량으로 외삽하지 않는다. 실제 외부 S3·Kafka·FCM 지연은 측정하지 않았다.

[^prepare]: prepare 횟수는 JDBC SQL 문장을 준비한 횟수다. 배치 실행·재사용 여부에 따라 DB의 실제 execute 횟수와 다를 수 있다.
[^pool]: 연결 점유는 풀에서 연결을 빌려 반환할 때까지의 시간이다. SQL 자체가 실행되는 시간 외에 애플리케이션 연산·대기도 포함될 수 있다.
[^lock]: 트랜잭션 advisory lock은 지정한 키에 대해 명시적으로 얻는 DB 잠금이다. 같은 키를 얻으려는 다른 TX는 커밋·롤백까지 기다린다.
[^flush]: flush는 관리 중인 엔티티의 변경을 확인하고 DB에 반영하는 과정이다. native SQL 직전에 반복되면 실제 변경이 적어도 검사 비용이 커질 수 있다.
