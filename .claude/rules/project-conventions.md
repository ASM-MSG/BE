# Project Conventions

FillMap 백엔드 프로젝트의 코딩 컨벤션. 모든 커밋에 강제 적용.

## Java 코딩 스타일

[네이버 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/) 준수.

- **들여쓰기**: 하드탭
- **중괄호**: K&R 스타일 (개행 문자 아님)
- **최대 줄 길이**: 120자
- **한 줄 하나의 문장**: 세미콜론 뒤 개행

## Import 순서

```text
static import
─ 빈 줄 ─
java.*
javax.*
─ 빈 줄 ─
org.*
─ 빈 줄 ─
lombok.*
─ 빈 줄 ─
com.*
```

각 그룹 사이에 빈 줄 하나. 알파벳 정렬.

## 네이밍

### DTO
- Request: `XxxRequestDto` (예: `UserSignupRequestDto`)
- Response: `XxxResponseDto` (예: `UserProfileResponseDto`)
- 클래스 → 파일명 완전 일치

### Entity / 도메인
- 클래스명: 명사 단수 (`User`, `Video`, `Grid`)
- 테이블명 (DB): 명사 복수 소문자 (`users`, `videos`, `grids`)

### 서비스 / 컨트롤러
- Service 인터페이스: `XxxService` (예: `GridQueryService`)
- Service 구현체: `XxxServiceImpl` (예: `GridQueryServiceImpl`)
- Controller: `XxxController` (예: `GridController`)

### Enum
- 클래스명: PascalCase (예: `VideoStatus`, `ProcessingStatus`)
- 상수명: SCREAMING_SNAKE_CASE (예: `ACTIVE`, `BLINDED`, `UPLOADED`)

## 커밋 메시지

git hook으로 강제됨. 형식:

```text
MSG-{번호} {타입}: {요약}
```

타입: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `style`

예시:
- `MSG-78 feat: GridEncoder 유틸리티 및 테스트 4건 추가`
- `MSG-86 fix: GIST 인덱스 누락 수정`
- `MSG-1 chore: PR 템플릿 추가`

### 제목 가독성 (2026-08-04 신설)

커밋 이력은 코드를 같이 안 본 사람(리뷰 추적·나중의 복기)이 읽는 1차 자료다. 제목을
**압축 명사 나열**로 쓰지 않는다:

- 제목 = **코드를 안 연 사람이 무엇이 바뀌었는지 아는 문장.** 동작·효과 중심으로 쓴다
  ("무효 토큰 자동 삭제" O, "deleteAllByTokens 신설" X). 가운뎃점(·) 나열 금지.
- 전문용어는 제목에서 흔한 말로 풀고, 정확한 클래스명·수치·근거는 **커밋 본문**(둘째 줄
  이하)에 적는다.
- 커밋당 한 관심사 원칙은 그대로 — 관심사가 여럿이라 제목이 나열이 되면 커밋을 쪼갠다.

```text
MSG-179 fix: 컨슈머 poll 튜닝·말폼드 비재시도 분류               (X) 나열 압축체
MSG-179 fix: 소비 지연 시 그룹 이탈 방지, 깨진 메시지는 즉시 폐기  (O) 읽히는 문장
```

### 본문은 기본적으로 쓰지 않는다 (2026-08-06 신설)

**제목 한 줄이 기본이다.** 본문은 제목으로 설명되지 않는 근거가 있을 때만 2~3문장 붙인다
(실측 수치, 기각한 대안처럼 나중에 복기할 값). 제목이 다 말하는데 본문을 덧붙이면 군더더기다.

본문을 길게 쓰고 싶어지면 먼저 **커밋에 관심사가 여럿 섞였는지 의심하고 쪼갠다.** 소제목·표·
여러 문단이 필요하다면 그건 커밋이 아니라 PR 본문이나 스펙 작업 로그에 갈 내용이다.

## 브랜치 명명

git flow 브랜치 타입만 쓴다. **커밋 메시지 타입(`feat`·`fix`·`chore`·`docs`…)을
브랜치 접두어로 가져다 쓰지 않는다** — `chore/`, `refactor/`, `docs/`는 git flow에 없다.

```text
feature/MSG-{번호}-{짧은-설명}   # 일반 작업 전부 (기능·수정·리팩터링·문서·설정)
hotfix/MSG-{번호}-{짧은-설명}    # 운영 긴급 수정
release/{버전}                   # 릴리스 준비
```

티켓 번호와 설명은 **하이픈**으로 잇는다(슬래시 아님).

```text
feature/MSG-142-bench                 (O)
feature/MSG-142/bench                 (X)  슬래시
chore/MSG-1-pr-template               (X)  git flow에 없는 타입
```

작업은 **항상 새 브랜치에서 시작한다.** `develop`·`main`에 직접 파일을 만들거나
수정하지 않는다 — 문서(`docs/*.md`) 작업도 예외 없다.

## 테스트

- 프레임워크: JUnit 5 + AssertJ
- 파일 위치: `src/test/java/{패키지 미러링}/{클래스명}Test.java`
- 메서드명: 한국어 백틱 스타일
  ```java
  @Test
  void 강남역_좌표는_같은_격자로_매핑된다() { ... }
  ```

## 3-Layer MVC

```text
Controller → Service → Repository → DB
```

- Controller는 얇게. Request 파싱 + Service 호출 + Response 변환만.
- Service에 비즈니스 로직 집중.
- Repository는 JpaRepository 상속 + native UPSERT는 `@Modifying @Query nativeQuery = true`.

## 영속 계층 — JPA 사용 방식 (MSG-334 명문화)

**엔티티 간 연관관계 매핑(`@ManyToOne`·`@OneToMany`)을 만들지 않는다.** 참조는
`reporterId`·`videoId`처럼 **id 컬럼 보관**(Long/String)으로 통일한다 — 전 도메인 선례
(Video·UserGrid·Friendship·Report).

이유 4가지:

1. **Owner A/B 도메인 경계**: 연관관계는 엔티티 클래스 수준의 타 도메인 import를 만든다 —
   "접점은 인터페이스로만" 원칙이 엔티티에서 무너진다. id 보관이면 참조가 값 수준.
2. **부분 매핑 전략과 정합**: 엔티티는 사용 컬럼만 매핑한다(`Grid.geom` 미매핑,
   `Report.reviewed_by` 후속 티켓 매핑 등). 연관관계는 상대 엔티티의 온전한 매핑을 전제한다.
3. **N+1 · LAZY 프록시 함정 원천 차단**: 조회는 프로젝션·native로 명시적으로 짠다.
4. **무결성은 DB 소유**: FK 제약·ON DELETE는 Flyway DDL이 보장한다 (JPA는 `validate`만).

**예외 (애그리거트 내부)**: 동일 패키지·동일 Owner에서 생명주기를 공유하는 부모-자식
(예: Mission↔MissionGrid 류)은 근거를 주석과 PR에 남기고 연관관계를 쓸 수 있다.
도메인·Owner 경계를 넘는 참조는 예외 없이 id 보관.

조인이 필요하면 **세타 조인 + 생성자 프로젝션**으로 쓴다
(`FROM Report r, User ru WHERE ru.id = r.reporterId` — Friendship·Report 리포지토리 선례).

연관관계 없이도 JPA를 유지하는 이유: 상태 전이 더티 체킹(`Video.markBlinded()` 류 도메인
메서드가 UPDATE문 없이 성립), `@Lock` 파생 쿼리·페이징 인프라, enum·시각 타입 자동 매핑,
영속성 컨텍스트의 트랜잭션 정합성(1차 캐시 — 같은 트랜잭션의 같은 행 = 같은 객체).
PostgreSQL 전용 기능(ON CONFLICT upsert·PostGIS·advisory lock)은 native로 내린다.
MyBatis 전면 전환 반려 판정·번복 트리거·대안(QueryDSL 포크·JdbcClient·jOOQ)은 ADR 정본 참조:
[영속 계층 JPA 유지 — MyBatis 전환 반려 (2026-08-03, cf-29917209)](https://soma17-msg.atlassian.net/wiki/spaces/M/pages/29917209)
— LLM-WIKI `04-decisions/ADR 영속 계층 JPA 유지 MyBatis 반려.md`

## 시각(날짜·시간) 처리 (MSG-376 명문화)

API JSON 경계의 `LocalDateTime`은 전역 코덱(`global/config/UtcLocalDateTimeJsonCodec`,
`@JacksonComponent`)이 UTC `Z` 표기로 주고받는다. 이 체계의 전제는 **"naive `LocalDateTime` 값 =
UTC"라는 관례**인데, 코덱은 값이 진짜 UTC인지 검증할 수 없으므로 아래를 코드 리뷰에서 강제한다.

- **인자 없는 `LocalDateTime.now()` 금지 (`src/main` 전체).** 시스템 기본 시간대(로컬 개발
  머신 KST)의 시각이 만들어져 UTC 표기가 붙는 순간 9시간 어긋난다. 서비스는 주입받은 `Clock`
  (`BadgeAwardServiceImpl`·`VideoServiceImpl` 선례), DI가 안 되는 엔티티 상태 전이 메서드는
  `LocalDateTime.now(ZoneOffset.UTC)` (Report·Friendship·Video 선례 — MSG-376 Codex 적발 후
  일괄 교정). 스케줄러·폴러가 `Clock` 주입 전이라면 같은 형태를 임시 허용한다 (AiBlurPoller는
  MSG-379에서 Clock 주입으로 교체 완료 — 임시 허용 잔존은 StaleTokenCleaner 하나). 테스트
  코드는 값 대조가 존에 민감할 때만 같은 기준을 적용한다.
- **개별 필드 `@JsonFormat` 산발 적용 금지** — 시각 표기 수정은 코덱 한 곳(MSG-376 D-4).
- **KST 라벨이 필요하면 `LocalDateTime`이 아니라 `LocalDate`를 쓴다**
  (`UploadHistoryResponseDto.uploadDate` 선례). `LocalDateTime`이면 예외 없이 `Z`가 붙는다.
  "KST 시각 문자열" 요구는 와이어 계약 변경이라 논의 대상이다.
- **DTO의 시각 타입은 `LocalDateTime`(시각)·`LocalDate`(날짜 라벨)만** —
  `DtoTimeTypeGuardTest`가 classpath 스캔으로 강제한다. 가드는 `*Dto` 네이밍만 잡으므로
  시각 필드를 갖는 응답 타입은 `*Dto` 네이밍을 지킨다. **String에 시각을 담는 우회 금지**
  (가드가 못 잡는다 — 리뷰 몫).
- **직렬화가 걸린 테스트는 스프링이 조립한 매퍼로** (`@JsonTest`·`@WebMvcTest`).
  `new ObjectMapper()` 손조립에는 코덱이 등록되지 않아 코덱 없는 세상을 검증하게 된다.
- **시각을 쿼리 파라미터로 받지 않는다** — `@RequestParam LocalDateTime`은 Jackson이 아니라
  스프링 컨버터 경로라 코덱 밖이다. 시각은 요청 본문으로 받는다.

## Lombok 사용 원칙

- `@RequiredArgsConstructor` 로 DI (`@Autowired` 지양)
- `@Getter` 는 자유롭게, `@Setter` 는 지양 (불변성 우선)
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 로 JPA 요구사항 충족
- `@Builder` 는 필드 4개 이상일 때만