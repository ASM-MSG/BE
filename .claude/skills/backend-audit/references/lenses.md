# 렌즈별 검토 기준

각 렌즈 에이전트는 **자기 절만** 읽는다. 절마다 다섯 가지를 적었다: 질문(멘토 의도), 어디를
보나, 무엇이 근거인가, 판정 기준, 규칙이 이미 막고 있는 것(중복 보고 방지)·함정.

공통 원칙 — 관찰과 판단을 섞지 않는다. "트랜잭션 안에서 S3를 부른다"는 관찰이고, "그래서
커넥션을 오래 잡는다"는 판단이며 실측(소요 ms) 없이는 추측이다. 세 칸을 따로 적는다.
그리고 항목마다 결정 문서를 찾는다: 클래스명·MSG 번호로 `docs/spec/ docs/prd/ .claude/rules/
../LLM-WIKI/04-decisions/`를 grep한다. 스펙 작업 로그의 "런타임 동작 기록" 절에 트랜잭션·쿼리
근거가 있는 경우가 많다.

목차: [native](#native) · [plan](#plan) · [tx](#tx) · [osiv](#osiv) · [tz](#tz) ·
[calls](#calls) · [naming](#naming) · [iface](#iface)

---

## native

**질문**: native 108건(전체 162)을 "PostgreSQL 전용이라 불가피"와 "JPQL·파생 쿼리로 갈 수
있음"으로 가르면 후자는 몇 건이고 어디에 몰려 있나. 멘토는 후자를 QueryDSL 전환 검토 대상으로
봤다 — 빌드 때 깨짐이 보이도록.

**어디를**: `inventory.md`의 리포지토리별 native 수 상위부터. VideoRepository(22)·Region(12)·
Notification(12)·UserBadge(10)·Grid(8)·UserGrid(7)이 전체의 2/3다.

**근거**: 쿼리 본문 자체. 불가피 판정의 근거는 SQL에 있는 PostgreSQL 전용 구문이다
(`ST_*`, `ON CONFLICT`, `pg_advisory_*`, `SKIP LOCKED`, `::geometry`, `jsonb`, `DISTINCT ON`,
`LATERAL`, 윈도 함수, `statement_timestamp()`, 대량 배치 UPDATE/DELETE). 그 구문이 없으면 전환
후보다.

**판정**: 항목은 쿼리 단위가 아니라 **리포지토리 단위**로 접는다(불가피 n / 전환 후보 m / 후보 중
JPQL만으로 되는 것·프로젝션 필요한 것). 전환 후보의 대표 2~3건은 쿼리를 인용하고 JPQL 형태를
한 줄 제안한다. "일단 native" 관성인지, 스펙에 native 선택 근거가 있는지 문서를 확인한다 —
`project-conventions.md` 영속 계층 절(2026-08-19 개정)이 native 허용 범위를 정의하고 있으니
그 기준으로 판정한다.

**차단·함정**: 신규 코드의 native 남용은 이미 convention-reviewer 검사 대상이다. 기존 코드
소급 리팩터링은 하지 않는다는 합의가 같은 절에 있으니, 보고서는 "전환하라"가 아니라 "전환 가능
목록"이다. `@Modifying` 벌크문은 JPQL로도 가능하지만 영속성 컨텍스트 동기화 문제가 있어 native
유지가 합리적일 수 있다 — 그 경우 근거를 적고 불가피로 분류한다.

---

## plan

**질문**: 주요 API가 **실제로** 실행하는 SQL은 무엇이고, 인덱스를 타는가, 한 요청에 같은 쿼리가
반복되는가(N+1). 멘토는 격자 조회와 이벤트 영상 조회·집계를 지목했고 현장 검증은 못 마쳤다.

**어디를**: 우선순위 순으로 — ① 격자 뷰포트 조회(`GridController`, `/api/grids/aggregation` 등)
② 이벤트 영상 목록·집계(`/api/event-occurrences/{id}/locations/{id}/videos`, 도움돼요 수
실측 집계) ③ 핫구역 조회 ④ 내 도감(usergrid) ⑤ 영상 업로드 확정 경로의 SELECT들.

**근거 = 실측**. 절차:
1. 그 경로를 실행하는 통합 테스트를 찾는다(`src/test/java/com/msg/fillmap/{도메인}/service/`
   또는 `repository/`). 없으면 앱을 띄워 curl로 친다(로컬 시드 필요 — 없으면 미실측 표기).
2. `scripts/capture-sql.sh {라벨} -- ./gradlew test --tests '{테스트 FQCN}'` — DB 로그에서
   실행문·실행 횟수·소요를 뽑는다. `×n`이 높은 SELECT가 N+1 신호다(테스트가 여러 케이스를
   돌리면 케이스 수만큼 곱해지니 케이스 수로 나눠 본다).
3. 핵심 SELECT를 `scripts/explain.sh "<SQL>"`로 플랜을 뽑는다. 로그의 `$1` 자리는
   `DETAIL: parameters` 줄의 값으로 채운다.
4. 그 쿼리가 쓰는 WHERE 컬럼과 `src/main/resources/db/migration/`의 인덱스 정의를 대조한다.

**판정**: Seq Scan이 나와도 로컬은 행 수가 적어 플래너가 일부러 고른다 — `rows=`가 수십 이하면
"로컬 플랜 참고치, dev 실측 필요"로 P2, 인덱스 자체가 없으면 P1. 반복 실행은 요청당 횟수를
적고 원인 위치(루프 안 지연 로딩·per-row 조회)를 파일:줄로 지목한다.

**차단·함정**: `default_batch_fetch_size: 100`이 켜져 있어 컬렉션 지연 로딩은 IN 절로 접힌다 —
`×n`이 아니라 IN 크기로 보인다. 테스트는 hikari 풀 2라 커넥션 대기가 소요에 섞일 수 있다.
`log_min_duration_statement=0`은 스크립트가 켰다가 `trap`으로 끈다(명령이 죽어도 원복).
docker가 통째로 죽는 경우만 남으니 그때는 `show log_min_duration_statement`가 -1인지 확인한다.

---

## tx

**질문**: `@Transactional` 메서드 안에서 외부 I/O(S3·HTTP 클라이언트·Kafka·Redis·메일)를 부르는
곳은 어디이고, 그동안 DB 커넥션을 잡고 있나. 영상 저장이 대표 사례다 — 저장·S3 복사·통계·뱃지가
한 트랜잭션에 있어 보인다는 지적. 그리고 이미 `afterCommit`으로 분리한 곳(8파일)과의 일관성.

**어디를**: `inventory.md`의 "외부 I/O 클라이언트를 주입받는 서비스" 목록 전부. 각 파일에서
`@Transactional` 메서드(클래스 레벨 포함)를 찾고 그 안의 호출 그래프를 따라 외부 I/O에 닿는지
본다 — 직접 호출뿐 아니라 **주입받은 다른 서비스가 트랜잭션 안에서 호출되는 경우**도 포함
(`calls` 렌즈와 겹치면 tx는 I/O 관점만 적는다).

**근거**: 호출 경로(`A.save() → B.copy() → s3Client.copyObject()`)를 파일:줄로. 소요는
`capture-sql.sh`를 직접 켜지 않는다(DB 로깅 토글은 `query` 에이전트 몫 — 동시에 켜면 서로 끊긴다).
`/tmp/backend-audit/*.log`에 캡처가 있으면 트랜잭션 시작(BEGIN 뒤 첫 문)부터 COMMIT까지의 로그
타임스탬프 차를 적고, 없으면 "미실측". S3 호출 자체의 소요는 로컬에서 못 잰다.

**판정**: 트랜잭션 안 외부 I/O는 P1 후보이되 세 가지 정상 사유가 있다 — ① 실패 시 롤백이 필요해
일부러 안에 둔 것(스펙에 근거 있음) ② 이미 `TransactionSynchronization.afterCommit`으로 밖에
낸 것(관찰만 적고 항목 아님) ③ 트랜잭션이 readOnly 조회이고 I/O가 캐시 읽기. 판정 칸에 사유
번호를 적는다. 스펙 작업 로그의 "빈 동작·트랜잭션 프록시 경계" 기록이 근거 문서다.

**차단·함정**: `@Transactional`이 붙은 `private` 메서드나 같은 클래스 내부 호출(self-invocation)은
프록시를 안 타 트랜잭션이 안 열린다 — 이건 "트랜잭션이 없는데 있다고 믿는" 반대 방향 결함이라
따로 적는다. `@Async`·`@Scheduled`·`@KafkaListener` 진입점은 트랜잭션이 어디서 열리는지
명시 확인. `afterCommit` 안에서 던진 예외는 삼켜진다 — 그 안에 DB 쓰기가 있으면 별도 항목.
업로드 UX(처리 중 상태·완료 알림)는 설계 판단이라 보고서 후속 후보에만 적는다.

---

## osiv

**질문**: `open-in-view`가 주석 처리돼 기본값 true(켜짐)다. 끄면 어디가
`LazyInitializationException`으로 깨지나, 그리고 켜 둔 채로 커넥션이 컨트롤러·직렬화까지
붙들리는 요청은 무엇인가.

**어디를**: 트랜잭션 밖에서 엔티티를 만지는 곳 — 컨트롤러가 엔티티나 엔티티를 품은 DTO를 받아
연관 필드 getter를 부르는 경우, `@Transactional` 없는 서비스 메서드에서 `@ManyToOne`
LAZY 필드 접근, Jackson이 엔티티를 직접 직렬화하는 응답. 연관관계는 2026-08-19 개정 이후
신규 코드에만 있으니 `@ManyToOne`·`@OneToMany` grep으로 후보 파일을 먼저 좁힌다.

**근거**: 호출 경로 파일:줄. 실측이 가능하면 테스트 프로파일에서 `SPRING_JPA_OPEN_IN_VIEW=false`
환경변수로 관련 통합 테스트를 돌려 실패 여부를 본다(`@WebMvcTest`는 OSIV 무관하니 통합 테스트만).
성공하면 "끄면 깨질 곳 없음(테스트 범위 내)"이 실측 근거다.

**판정**: 깨지는 곳이 있으면 P1(끄기 전 수정 목록). 없으면 "끌 수 있음"과 근거를 적고, 켜 둔
채 커넥션 점유가 긴 요청(플랜 렌즈의 느린 응답 + 컨트롤러 후처리)을 P2로 적는다.

**차단·함정**: 대부분 id 보관 방식 도메인(Video·UserGrid 등)은 연관관계가 없어 OSIV 영향이
없다 — 항목 없음이 정상이다. yml의 주석 `# OSIV ?? (N+1 ??)`은 의도가 기록 안 된 상태이니
그 자체를 "근거 문서 없음"으로 적는다.

---

## tz

**질문**: 서버 OS·JVM·JDBC 세션·DB 컬럼 타입·애플리케이션 `Clock`의 시간대 기준이 한 줄로
설명되나. 멘토는 "9시간 차이 때문에 넣은 Clock"의 목적이 불명확했다고 했다.

**어디를**: `inventory.md`의 Clock·timestamptz 수치. `Clock.systemUTC()`가 어디서 만들어지는지
(중앙 `@Bean`인지 서비스마다 생성자 고정인지), DB 컬럼 `timestamp`(무존 75) vs `timestamptz`(3)의
분포와 그 3개가 왜 다른지, JDBC/JVM 타임존 설정 부재(`user.timezone` 없음 → 서버 OS TZ 의존),
`UtcLocalDateTimeJsonCodec`, KST 판정(스트릭·스케줄러·이벤트 생명주기 `KST-today`)의 변환 위치,
배포 컨테이너의 TZ(`Dockerfile`·compose·deploy 문서).

**근거**: 설정 파일·마이그레이션·코드 파일:줄. 실측: 로컬 DB `show timezone`, 앱이 저장한 행의
값과 저장 시각 비교(가능하면).

**판정**: 기준이 문서 한 곳(`project-conventions.md` 시각 처리 절)에 있고 코드가 그와 일치하면
항목 없음. 어긋나는 층(예: prod EC2 TZ가 KST인데 무존 timestamp에 `now()`를 DB 기본값으로
쓰는 컬럼)이 있으면 P1. `timestamptz` 3개가 예외인 이유가 문서에 없으면 P2.

**차단·함정**: 맨 `LocalDateTime.now()`는 규칙으로 금지돼 0건이다 — 재보고 안 한다. 대신
`Instant.now()` 11건과 `Clock.systemUTC()` 44건이 규칙 밖에 있으니 그 분포를 본다. DB DEFAULT
`now()`·`CURRENT_TIMESTAMP`는 세션 타임존을 따르니 마이그레이션 grep이 필요하다.

---

## calls

**질문**: 서비스가 다른 서비스를 부를 때 호출 방향이 한쪽으로 흐르나, 순환은 없나, 트랜잭션은
어디서 열리고(진입점) 실패하면 어디까지 롤백되나. 멘토 제안 구조(`Controller → Command/Query
Service → 공통 컴포넌트 → Repository`)와 현재 구조의 거리.

**어디를**: `*ServiceImpl`·`*Service` 클래스의 `private final *Service` 필드 전부를 모아
방향 그래프(A→B)를 만든다. 도메인 간 접점은 계약 인터페이스(`GridQueryService`·
`UserGridQueryService`·`ZoneNameQueryService` 등)로만 가야 한다는 원칙이 CLAUDE.md에 있다 —
그 원칙 위반(구현체 직접 주입, 반대 방향 호출)을 먼저 본다.

**근거**: 그래프 자체(파일:줄 목록)와, 진입점별 `@Transactional` 전파(REQUIRED 중첩이면 하나의
물리 트랜잭션 — 내부 서비스에서 던진 예외가 바깥까지 롤백 마킹). `REQUIRES_NEW`·`NOT_SUPPORTED`가
있는 곳은 왜인지 문서를 찾는다.

**판정**: 순환(A→B→A)은 P1. 계약 인터페이스를 우회한 도메인 간 직접 참조는 P1(경계 원칙 위반).
Command/Query 분리 현황은 "이미 분리된 도메인(예: `HotScoreCommandService`·`HotZoneService`,
`StreakCommandService`)"과 "한 서비스가 둘 다 하는 도메인" 목록으로 적고 판단은 후속 후보로.
롤백 범위가 스펙에 기록 안 된 진입점은 "근거 문서 없음".

**차단·함정**: 그래프가 커서 그림이 필요하면 mermaid 한 개로 도메인 수준만 그린다(클래스 수준은
표). 리스너·스케줄러·시더는 별도 진입점으로 따로 센다.

---

## naming

**질문**: `*View`(17)·`*Projection`(31)·`*ResponseDto`(117)가 각각 무슨 역할인지 정의가 있나,
실제 사용이 그 정의와 맞나. 긴 필터·변환 로직이 메서드로 나뉘어 있나. 같은 의미 상수가 여러 곳에
흩어져 있나.

**어디를**: 세 접미사 파일의 위치(리포지토리 프로젝션인지 서비스 중간 모델인지 컨트롤러
응답인지)와 실제 쓰임을 표로. 서비스 메서드 중 60줄 넘는 것(스트림 체인·조건 분기가 긴 것)을
찾아 분리 후보로. 상수는 `MAX_`·`_LIMIT`·`_DAYS`·`_METERS`·`KST`·`ZoneId.of("Asia/Seoul")` 같은
리터럴이 여러 클래스에 반복되는지 grep한다.

**근거**: 파일:줄과 반복 횟수. 명명 규칙 정의는 `project-conventions.md`에 DTO 접미사만 있고
View·Projection은 없다 — 그 부재 자체가 항목이다.

**판정**: 전부 P3(가독성). 다만 같은 상수가 값이 **다르게** 중복된 경우는 P2(동작 차이).

**차단·함정**: `DtoTimeTypeGuardTest`가 `*Dto` 네이밍을 전제로 하니 접미사 통일 제안은 그
가드에 미치는 영향을 같이 적는다. 긴 메서드는 줄 수만으로 나쁘다고 하지 않는다 — 분기 수·중첩
깊이를 같이 적는다.

---

## iface

**질문**: Service 인터페이스 52 · 구현체 42. 구현체가 하나뿐인 인터페이스 중 도메인 경계 계약이
아닌 것은 무엇인가. 멘토는 "전부 삭제하라는 뜻은 아니다"라고 했다 — 목적이 있는지 보라는 것.

**어디를**: `inventory.md`의 "구현체 1개" 목록. 각 인터페이스가 ① 다른 Owner 도메인에서
주입되는가(계약 — 유지) ② 테스트에서 목·페이크 구현이 있는가(테스트 이음새 — 유지 사유 될 수
있음) ③ 같은 도메인 안에서만 쓰이고 구현 1개(형식적 분리 후보).

**근거**: 인터페이스별 사용처 grep 결과(주입하는 클래스 목록, 테스트의 구현·목 여부).

**판정**: ③은 P3 후보 목록. `project-conventions.md`가 `XxxService`/`XxxServiceImpl` 쌍을
컨벤션으로 정해 두었으니, 제거는 컨벤션 개정이 선행해야 한다는 점을 보고서에 적는다 — 이
렌즈의 결론은 "컨벤션을 유지할지 결정할 재료"다.

**차단·함정**: 인터페이스 없이 `@Component`로 끝나는 서비스(예: `PasswordService`)도 있어
이미 혼재다 — 혼재 자체를 항목으로 만들지 말고 분포만 적는다.
