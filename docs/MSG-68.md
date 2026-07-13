# MSG-68: 첫 점령 판단 + 색칠 비즈니스 로직

**Owner**: A

> 부모 에픽: MSG-48 "영상 기록" · 연결 스토리: MSG-61 "사용자는 첫 방문 격자가 자동 색칠되는 것을 볼 수 있다" (relates to)
> 담당: KangJeong (Owner A — 지도 인프라 도메인 `com.msg.fillmap.grid.*`)
> 선행 티켓: MSG-78 (완료) — `GridEncoder` / `GridConstants` / `UserGrid` 엔티티
>
> ✅ 초기 Open Questions는 D1~D6으로 확정됨. 이 스펙은 구현 착수 가능한 상태다.
> ⚠️ 단, Owner B(성민) 사전 공유가 필요한 항목이 2개 있다 (§계약 변경).

---

## 개요

사용자가 영상을 올리면 그 격자가 도감에 **색칠**된다. 이 티켓은 그 "색칠"을 만드는 **write 경로 한 줄**을 구현한다.

```
좌표(lat, lon)  →  grid_id 양자화  →  전역 격자 등록(lazy insert)  →  개인 점령/재방문 판정
                   (MSG-78 유틸)        (grids)                       (user_grids)
```

핵심 산출물은 **`GridOccupationService.occupy()` 하나**이고, 그것이 반환하는 `isFirstOccupation` 불리언이
후속 티켓 전체의 트리거가 된다 — MSG-70(뱃지·타임라인 이벤트), MSG-73(색칠 응답 API), MSG-61(스토리 완결).

### "색칠"의 정의 (D2에서 확정)

**색칠 여부 = 점령 여부 = `user_grids` row 존재 여부.** 그 이상도 이하도 아니다.

MVP는 개인 도감만 다루므로 사용자별 색 구분이 필요 없다. 색상값은 FE의 단색 상수이고, **백엔드는 색을 모른다.**
`users.grid_color` 컬럼도, `grid_color` ENUM 타입도 만들지 않는다 (근거는 D2).

### 재방문 시 세 테이블의 동작 (개념 핵심)

glossary의 "점령은 상태(1회 발생), 방문은 이벤트(N회 반복)"가 스키마에 그대로 드러난다.

| 테이블 | 첫 업로드 | 재업로드(재방문) | 이유 |
|---|---|---|---|
| `grids` | row 생성 (전역 격자 등록) | **변화 없음** — `ON CONFLICT DO NOTHING` | 격자당 평생 1회 |
| `videos` | row 생성 | **row 추가** (N개로 증가) | 방문 = 이벤트 |
| `user_grids` | row 생성 (= 점령 = 색칠) | **row 유지**, `video_count++`, `last_uploaded_at` 갱신 | 점령 = 상태 |

즉 재방문해도 격자는 **이미 칠해져 있으므로 색칠 상태가 변하지 않는다**(`isFirstOccupation = false`).
영상만 계속 쌓인다. `videos` write는 MSG-66 소관이라 본 티켓 범위 밖 (D1).

---

## 확정된 결정 (구현 기준)

| # | 항목 | 확정 내용 |
|---|---|---|
| D1 | 범위 | **서비스 레이어만.** `Grid` 엔티티 + `GridRepository` + `UserGridRepository` + 점령 서비스. `videoId`는 `Long` 스칼라로만 받고 `Video` 엔티티를 참조하지 않는다. `videos` write는 MSG-66(Owner B) |
| D2 | `users.grid_color` | **추가하지 않음.** MVP 색칠 = 점령 여부(이진). 색상값은 FE 단색 상수. 8색 팔레트·사용자별 색 구분은 전체 지도(Phase 2+)로 유예 |
| D3 | `video_count` | **개인 기준.** 기존 `user_grids.video_count`("그 격자에 올린 *내* 영상 수") 그대로 사용. `grids`에 전역 카운터 추가 안 함 |
| D4 | `videos.view_count` | `BIGINT NOT NULL DEFAULT 0` **컬럼만** V1에 선반영. 엔티티·증가 로직 없음(MSG-66 몫). MSG-78의 D4 선례대로 V1 직접 수정 |
| D5 | 동시성 | **native UPSERT까지만.** `ON CONFLICT`로 경합 시에도 깨지지 않게. 낙관적 락·재시도·경합 통합 테스트는 **MSG-69** |
| D6 | 테스트 | **Testcontainers + PostGIS 도입** (`postgis/postgis:16-3.4`). Flyway가 V1을 실제로 돌리므로 스키마까지 검증됨. Docker 필요 |
| D7 | `region_code` | lazy insert 시 **NULL**. 행정동 태깅은 배치 서버가 비동기로 수행 — Confluence ADR "격자 저장(dong=null) → Kafka → 배치 → UPDATE"와 일치 |
| D8 | `cover_video_id` | 본 티켓에서는 **항상 NULL**. `videos(id)` FK인데 MSG-66 미완이라 세팅 시 FK 위반 (§쟁점 4) |
| D9 | 행정동 태깅 트리거 | **DB 폴링.** 배치 서버가 `WHERE region_code IS NULL`을 주기적으로 긁어 벌크 태깅한다. Kafka `grid.created` 이벤트를 **발행하지 않는다** → MSG-68에 프로듀서 책임 없음. Kafka는 영상 처리 파이프라인 전용 |

---

## 1. 산출물 — `Grid` 엔티티

### 위치

`com.msg.fillmap.grid.entity.Grid` (Owner A). `UserGrid`와 같은 패키지.

### 지오메트리 컬럼은 매핑하지 않는다 (§쟁점 1 결론)

`grids.center_geom` / `bbox_geom`은 `GEOGRAPHY(POINT|POLYGON, 4326) NOT NULL`이다. `hibernate-spatial`이
의존성에 있어 JTS `Point`/`Polygon`으로 매핑할 수는 있으나, **엔티티에 매핑하지 않는다.**

근거:
- lazy insert를 native 쿼리 + `ST_MakePoint`/`ST_GeomFromText`로 처리하기로 했다(D5의 `ON CONFLICT DO NOTHING`).
  따라서 write 경로에서 JTS 객체가 필요 없다.
- 본 티켓에 지오메트리 **읽기** 요구가 없다 (뷰포트 조회는 MSG-73).
- 쓰지도 읽지도 않는 필드를 매핑하는 건 speculative — 필요해지는 MSG-73에서 추가한다.
- `ddl-auto: validate`는 **엔티티에 없는 DB 컬럼을 문제 삼지 않는다** (반대 방향만 검증). 안전하다.

| 필드 | 컬럼 | 타입/제약 | 비고 |
|---|---|---|---|
| `gridId` | `grid_id` | `VARCHAR(20) PRIMARY KEY` | `@Id`. 자연키 — `@GeneratedValue` 없음 |
| `regionCode` | `region_code` | `VARCHAR(10)` FK→regions, **nullable** | lazy insert 시 NULL (D7) |
| `firstSeenAt` | `first_seen_at` | `TIMESTAMP NOT NULL DEFAULT now()` | 전역 격자 등록 시각 |
| `createdAt` | `created_at` | `TIMESTAMP NOT NULL DEFAULT now()` | |
| — | `center_geom` | `GEOGRAPHY(POINT,4326)` | **미매핑** |
| — | `bbox_geom` | `GEOGRAPHY(POLYGON,4326)` | **미매핑** |

### 컨벤션

- `@Entity @Table(name = "grids")`, `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, `@Setter` 금지.
- 필드 4개 미만이므로 **`@Builder` 쓰지 않는다** (project-conventions.md: 4개 이상일 때만).
- `Region` 엔티티를 `@ManyToOne`으로 물지 않고 `regionCode` 스칼라로 매핑 (도메인 경계).

---

## 2. 산출물 — `GridRepository` (전역 격자 등록 / lazy insert)

`com.msg.fillmap.grid.repository.GridRepository extends JpaRepository<Grid, String>`

`GridEncoder`가 준 `grid_id`로부터 중심좌표·bbox를 계산해 `grids` row를 **없을 때만** 만든다.

```java
@Modifying
@Query(value = """
	INSERT INTO grids (grid_id, region_code, center_geom, bbox_geom)
	VALUES (
		:gridId,
		NULL,
		ST_SetSRID(ST_MakePoint(:centerLon, :centerLat), 4326)::geography,
		ST_SetSRID(ST_GeomFromText(:bboxWkt), 4326)::geography
	)
	ON CONFLICT (grid_id) DO NOTHING
	""", nativeQuery = true)
int registerIfAbsent(@Param("gridId") String gridId,
		@Param("centerLat") double centerLat,
		@Param("centerLon") double centerLon,
		@Param("bboxWkt") String bboxWkt);
```

- 반환값(영향받은 row 수)이 `1`이면 **이번 호출이 전역 격자 등록을 일으켰다**, `0`이면 이미 있었다.
  (서비스는 이 값을 쓰지 않는다 — 첫 점령 판정은 `user_grids` 쪽에서 한다. 디버깅·로깅용.)
- `region_code`는 NULL (D7). **이벤트를 발행하지 않는다** — 배치 서버가 `region_code IS NULL`을 폴링해
  태깅하므로, NULL을 넣는 것 자체가 "할 일"의 표시다 (D9).

### ⚠️ 축 순서 — 실제 버그 원천

**PostGIS는 `(경도, 위도)` 순서**다. `ST_MakePoint(lon, lat)`, WKT `POLYGON((lon lat, ...))`.
그런데 `GridEncoder.bbox()`는 `GridPoint(lat, lon)` **레코드를 위도 먼저** 반환한다.

두 규약이 반대이므로 변환 어댑터를 한 곳에 격리하고, **뒤집힘을 잡는 회귀 테스트를 반드시 둔다.**
(한국 좌표는 `lat≈37`, `lon≈127`로 값이 비슷해 뒤집혀도 조용히 통과할 수 있다. 저장된 `center_geom`이
실제로 원본 좌표를 `ST_Contains`하는지 PostGIS로 검증하는 것이 유일하게 확실한 방법이다.)

WKT 조립은 `GridEncoder.bbox()`의 닫힌 링 5점(남서→남동→북동→북서→남서)을 그대로 `lon lat` 순으로 쓴다.

---

## 3. 산출물 — `UserGridRepository` (점령 / 재방문 UPSERT)

`com.msg.fillmap.grid.repository.UserGridRepository extends JpaRepository<UserGrid, Long>`

첫 점령인지 재방문인지를 **DB 왕복 1회**로 판정한다.

```java
@Modifying
@Query(value = """
	INSERT INTO user_grids (user_id, grid_id, video_count)
	VALUES (:userId, :gridId, 1)
	ON CONFLICT (user_id, grid_id) DO UPDATE
	   SET video_count = user_grids.video_count + 1,
	       last_uploaded_at = now()
	RETURNING video_count
	""", nativeQuery = true)
int upsertOccupation(@Param("userId") Long userId, @Param("gridId") String gridId);
```

### 첫 점령 판정: `RETURNING video_count == 1`

INSERT된 행은 `video_count = 1`, 충돌해서 UPDATE된 행은 반드시 `≥ 2`다(기존 row의 최솟값이 1이므로).
따라서 **`video_count == 1` ⟺ 첫 점령**이다.

> **왜 `xmax = 0` 트릭을 쓰지 않는가.** `RETURNING (xmax = 0) AS is_first`가 널리 쓰이는 관용구지만,
> PostgreSQL 내부 컬럼에 의존하고 **동시 락이 걸린 상황에서 INSERT된 행의 `xmax`가 0이 아닐 수 있어**
> 첫 점령을 거짓 음성으로 판정할 여지가 있다. 이 값은 MSG-70의 뱃지 지급을 트리거하므로 오판이 비싸다.
> `video_count` 판정은 동시 INSERT 두 건에서도 정확히 하나만 `1`을 받으므로 더 견고하고, 설명도 필요 없다.
>
> **불변식 의존**: 이 판정은 "`video_count`가 0인 row는 존재하지 않는다"에 의존한다. MSG-72(점령 롤백)가
> `video_count`가 0이 되는 시점에 row를 **삭제**하도록 구현해야 한다. 이 제약을 MSG-72에 전달한다(§후속 작업).

### 영속성 컨텍스트 (§쟁점 3 결론)

native `RETURNING` 값만 쓰고 엔티티를 재조회하지 않으므로 `@Modifying(clearAutomatically = true)`가
**필요 없다.** 같은 트랜잭션에서 `UserGrid`를 다시 읽는 코드를 넣지 말 것 — 넣는 순간 1차 캐시가
stale해진다.

MSG-78이 만든 `UserGrid`의 `@Builder`는 이 write 경로에서 **쓰이지 않는다**(native insert이므로).
쓰이지 않지만 **삭제하지 않는다** — 본 티켓이 만든 orphan이 아니다 (coding-principles §3 surgical changes).

---

## 4. 산출물 — `GridOccupationService`

Owner B(MSG-66)가 소비하는 **신규 계약 인터페이스**다. 따라서 인터페이스 + 구현체로 분리한다.

- `com.msg.fillmap.grid.service.GridOccupationService` — 인터페이스
- `com.msg.fillmap.grid.service.impl.GridOccupationServiceImpl` — 구현체 (`@RequiredArgsConstructor`)

```java
public interface GridOccupationService {

	/**
	 * 영상 업로드 좌표를 격자에 반영한다.
	 * 전역 격자 등록(lazy insert) + 개인 점령/재방문 판정을 수행한다.
	 * 호출자(MSG-66)의 트랜잭션에 참여한다.
	 */
	OccupationResult occupy(Long userId, double lat, double lon, Long videoId);
}
```

### 흐름

```
1. gridId = GridEncoder.encode(lat, lon)
2. gridRepository.registerIfAbsent(gridId, center, bboxWkt)   // 전역 격자 등록
3. videoCount = userGridRepository.upsertOccupation(userId, gridId)
4. return new OccupationResult(gridId, videoCount == 1, videoCount)
```

**순서가 중요하다.** `user_grids.grid_id`는 `grids(grid_id)` FK이므로 2가 3보다 먼저여야 한다.

`videoId`는 시그니처에 받되 **본 티켓에서는 사용하지 않는다** (D8 — `cover_video_id`는 NULL).
MSG-66이 대표 영상 지정을 붙일 자리를 미리 열어두는 파라미터다.

### 반환 타입 — `OccupationResult`

`com.msg.fillmap.grid.dto.OccupationResult` (record)

```java
public record OccupationResult(String gridId, boolean isFirstOccupation, int videoCount) {
}
```

`XxxResponseDto` 접미사를 **붙이지 않는다.** project-conventions.md의 DTO 네이밍은 HTTP Request/Response
경계용이고, 이건 서비스 간 내부 반환 객체다. HTTP 응답 DTO는 MSG-73이 별도로 만든다.

`isOccupied`/`isFirstOccupation` 계열 명명 (glossary: `owned`/`conquered` 금지).

### 트랜잭션 경계 (§쟁점 5 결론)

`@Transactional`을 붙이되 **MSG-66의 영상 저장 트랜잭션에 참여**해야 한다(기본 `REQUIRED`).
영상 저장과 점령이 원자적이어야 하기 때문이다 — videos row는 생겼는데 점령이 안 되거나 그 반대는 안 된다.

기존 계약 인터페이스와의 관계:

| 인터페이스 | 성격 | 소유 | 상태 |
|---|---|---|---|
| `GridQueryService` | 격자 **조회** | A 제공 → B 소비 | MSG-73에서 노출 |
| `GridOccupationService` | 격자 **write** | A 제공 → B 소비 | **본 티켓 신규** |
| `UserGridQueryService` | 개인 점령 조회 | B 제공 → A 소비 | 미구현 |

`GridQueryService`(read)와 역할이 겹치지 않으므로 별도 인터페이스로 둔다 (CQS).

---

## 5. 데이터 모델 — `V1__init.sql` 수정

MSG-78의 D4 선례를 따른다: V1이 아직 어떤 DB에도 미적용이므로 V2 신규 파일 대신 V1을 직접 수정한다.

### 변경 1건 (D4)

```sql
-- videos 테이블
+ view_count  BIGINT NOT NULL DEFAULT 0,
```

**컬럼만.** 엔티티·증가 로직·조회 API 없음. MSG-66 / 영상 재생 도메인이 채운다.

> ⚠️ `videos`는 **Owner B(성민) 소유 테이블**이다. Owner A가 컬럼을 추가하므로 **머지 전 사전 공유 필요**
> (§계약 변경).

### 변경 없음

- `grids` — `region_code` nullable FK 그대로 (D7). `regions` 테이블이 비어 있어도 NULL 삽입은 FK 위반이 아니다.
- `user_grids` — MSG-78이 확정한 스키마 그대로. `uq_user_grids UNIQUE (user_id, grid_id)`가 UPSERT의 충돌 대상.
- `users` — `grid_color` 추가하지 않음 (D2).

---

## 계약 변경

**2건. 둘 다 Owner B(성민) 확인 필요.**

1. **`GridOccupationService` 신규 노출** — MSG-66의 영상 저장 서비스가 이 인터페이스를 호출해야 한다.
   시그니처 `occupy(Long userId, double lat, double lon, Long videoId)` → `OccupationResult`.
   호출 시점은 videos row insert **직후, 같은 트랜잭션 안**.
2. **`videos.view_count` 컬럼 추가** — Owner B 소유 테이블에 대한 스키마 변경 (D4).

`GridQueryService` / `UserGridQueryService` / `HotZoneService` / `UserOidcCommandService` 시그니처는 **불변**.

---

## 테스트 시나리오 (TDD 대상, JUnit5 + AssertJ · 한국어 백틱 메서드명)

### 테스트 인프라 (D6)

`build.gradle` 추가:

```gradle
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:postgresql'
testImplementation 'org.testcontainers:junit-jupiter'
```

컨테이너 이미지가 `postgres`가 아니라 `postgis/postgis`이므로 호환 선언이 필요하다:

```java
@Container
static PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
	DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
```

Flyway가 `V1__init.sql`을 실제로 실행하므로 **스키마와 UPSERT 쿼리가 함께 검증된다.**
이 컨테이너 세팅은 팀 공용 자산이다 — MSG-69 경합 테스트, MSG-73 뷰포트 공간 쿼리가 그대로 재사용한다.
공통 베이스 클래스(`AbstractPostgisTest` 등)로 빼는 것을 권한다.

### `GridRepository` (`@DataJpaTest` + Testcontainers)

- `새_격자를_등록하면_grids_row가_생성된다`
- `이미_등록된_격자에_다시_올리면_grids_row는_중복_생성되지_않는다` — `ON CONFLICT DO NOTHING`, 영향 row 수 `0`
- `전역_격자_등록시_region_code는_null이다` — D7
- **`center_geom은_원본_좌표를_포함한다`** — `ST_Contains(bbox_geom, ST_MakePoint(lon, lat))`로 축 순서 뒤집힘 검출
- **`bbox_geom은_경도위도_순서로_저장된다`** — 저장된 `ST_X()`가 경도(≈127), `ST_Y()`가 위도(≈37)임을 확인
- `bbox_geom은_닫힌_링_폴리곤이다`

### `UserGridRepository` (`@DataJpaTest` + Testcontainers)

- `첫_업로드는_첫_점령으로_판정된다` — `video_count == 1` 반환
- `재방문은_첫_점령이_아니다` — 두 번째 호출이 `2` 반환
- `재방문시_video_count가_증가한다`
- `재방문시_last_uploaded_at이_갱신된다`
- `재방문해도_user_grids_row는_하나다` — 점령은 상태
- `재방문해도_first_collected_at은_변하지_않는다`
- `다른_사용자는_같은_격자에_각자_점령한다` — `(user_id, grid_id)` 조합이므로 row 2개
- `cover_video_id는_null로_저장된다` — D8

### `GridOccupationServiceImpl` (`@DataJpaTest` + Testcontainers, 통합)

- `같은_격자_내_서로_다른_좌표는_동일한_격자를_점령한다` — 양자화 불변식이 서비스 레벨까지 유지
- `미점령_격자에_업로드하면_isFirstOccupation은_true다`
- `점령한_격자에_재업로드하면_isFirstOccupation은_false다`
- `전역_격자_등록이_개인_점령보다_먼저_일어난다` — FK 제약 위반 없이 성공
- `이미_다른_사용자가_등록한_격자에_첫_업로드하면_isFirstOccupation은_true다`
  — 전역 격자 등록(`grids`)과 개인 점령(`user_grids`)의 분리 검증. **가장 중요한 시나리오.**

> **`videos` 픽스처 주의**: `user_grids.cover_video_id`는 `videos(id)` FK지만 D8에 따라 NULL이므로
> videos row 없이 테스트 가능하다. `videos` 픽스처를 만들 필요 없다.

### 에러 코드

**새 에러 코드를 추가하지 않는다.** `occupy()`는 사용자 대면 API가 아니라 내부 write 경로이고,
좌표 검증은 호출자(MSG-66의 `@Valid` Request DTO)가 이미 끝냈다. 던질 비즈니스 예외가 없다
(coding-principles §2: 불가능한 시나리오에 에러 처리 금지).

`GridErrorCode`가 필요해지는 건 사용자 대면 조회 API인 **MSG-73**이다. developCode 대역은 `3xxx`를
예약해 둔다 (auth가 `2xxx`).

---

## 명시적 범위 밖

| 항목 | 소관 |
|---|---|
| `videos` 테이블 write, `Video` 엔티티 | MSG-66 (성민) |
| `videos.view_count` **증가 로직**·엔티티 매핑 | MSG-66 / 영상 재생 도메인 |
| "격자를 누르면 내가 올린 영상 리스트" 조회 API | **MSG-73** (§쟁점 6) — 본 티켓은 write만 |
| 격자 색칠 **응답** API (단일·viewport) | MSG-73 |
| 낙관적 락(`@Version`)·재시도·동시 업로드 경합 통합 테스트 | MSG-69 (D5) |
| 첫 점령 → 뱃지·타임라인 이벤트 발행 | MSG-70 |
| 영상 삭제 시 점령 롤백 | MSG-72 |
| 배치 서버 행정동 태깅 (`region_code IS NULL` 폴링 → 벌크 지오코딩) | 배치 서버 (Confluence ADR, D9) |
| `users.grid_color` · 8색 팔레트 · 친구/전체 지도 색 구분 | Phase 2+ (D2) |
| `grids` 전역 `video_count` 카운터 | Phase 2+ (D3) |
| **격자 크기 가변** (인구 밀집도에 따라 격자 크기 조절) | **Phase 2+** — 티켓 원문 "당장 적용X" |

---

## 후속 작업

### 본 티켓 PR에 포함

- **glossary.md 정정 (D2)**: "도감 색상(grid_color)" 절이 **존재하지 않는 컬럼**(`users.grid_color`, 8색 ENUM)을
  MVP 확정 용어로 문서화하고 있다. MVP는 단색·이진 색칠임을 명기하고, 사용자별 색 구분은
  "🚧 Phase 2+ 미확정"의 친구 도감 절로 옮긴다.
- **브랜치명 주석**: 현재 브랜치가 `feature/MSG-68-deal-with-grid-color`지만 D2에 따라 색상 컬럼을 만들지
  않는다. 커밋 메시지에 근거를 남긴다.

### Owner B(성민)에게 전달

- `videos.view_count` 컬럼 추가 사전 공유 (계약 변경 2).
- `GridOccupationService` 호출 규약 — videos insert 직후, 같은 트랜잭션 (계약 변경 1).
- **대표 영상 자동 지정 (Q1)**: `occupy()`가 `isFirstOccupation == true`를 반환하면 그 `videoId`를
  `user_grids.cover_video_id`에 세팅한다 (첫 점령 영상 = 대표 영상). 재방문 시엔 손대지 않는다.
- **MSG-72 제약**: 점령 롤백 시 `video_count`가 0이 되면 `user_grids` row를 **반드시 삭제**해야 한다.
  `video_count == 1` 첫 점령 판정이 이 불변식에 의존한다 (§3).

### Confluence ADR 갱신 필요 (문서-스키마 불일치, MSG-68 구현 차단 아님)

[[DB 선택(PostgreSQL) + 지오코딩 서버 분리]] ADR의 비동기 태깅 설계는 D7의 근거이며 유효하다.
MSG-68은 **지오코딩 서버를 호출하지 않는다** — `region_code`를 NULL로 두는 것이 배치 서버로 넘기는 이음매다.
`ON CONFLICT DO NOTHING`이 실제 insert를 일으킨 경우(영향 row = 1)에만 이벤트를 발행하면 ADR의
"격자당 지오코딩 호출은 평생 1회"가 자연히 만족된다.

다만 **배치 서버가 착수 전에 풀어야 할 문제가 있다.** MSG-68을 막지는 않으나 기록해 둔다.

0. **데이터 버전이 한 분기 낡았다.** ADR은 "현재 최신 버전 `ver20260401` (2026-07-10 기준)"이라 적었으나,
   실제로는 **`ver20260701`이 이미 발행돼 있다** (2026-07-10 확인, 34,653,221 bytes). `ver20261001`은 아직 없음(404).
   → 채택 버전: **`ver20260701`**.
   ```
   https://raw.githubusercontent.com/vuski/admdongkor/master/ver20260701/HangJeongDong_ver20260701.geojson
   ```
1. **배치가 UPDATE할 컬럼이 스키마에 없다.** ADR 예제는 `gridRepo.updateDong(gridId, sido, sigungu, dong)`로
   `grids`에 시도/시군구/동 3컬럼이 있는 것처럼 쓴다. 실제 V1 스키마는 `region_code VARCHAR(10)` FK→`regions`
   **한 컬럼**이다. 배치는 `grids.region_code` **한 컬럼만** UPDATE하는 것으로 정정해야 한다.
2. **지오코딩 응답에 `region_code`로 쓸 값이 없다.** ADR의 `/find_district` 응답은 `{sido, sigungu, dong}`
   문자열만 준다. `grids.region_code`를 채우려면 응답에 행정동 **코드**를 포함시켜야 한다.

   `ver20260701`의 `properties` 실측 (ADR의 필드 목록은 불완전 — `adm_cd2`·`sgg`·`sido` 누락):

   ```json
   { "adm_nm": "서울특별시 종로구 사직동", "adm_cd2": "1111053000",
     "sgg": "11110", "sido": "11", "sidonm": "서울특별시",
     "sggnm": "종로구", "adm_cd": "11010530" }
   ```

   **`adm_cd2`(10자리)를 써야 한다. `adm_cd`(8자리)가 아니다.**
   - `adm_cd2` = `1111053000` → 앞 5자리가 `sgg`(`11110`), 앞 2자리가 `sido`(`11`).
     **prefix가 곧 부모 코드**라 `regions.parent_code` 자기참조 계층이 그대로 성립한다. 폭도 `VARCHAR(10)`에 정확히 맞는다.
   - `adm_cd` = `11010530` → `sgg`로 시작하지 않는 통계청 별도 체계. 계층이 깨진다.
3. **`regions` 테이블이 비어 있다.** `grids.region_code`는 `regions(region_code)` FK이므로, 배치가 값을 UPDATE하려면
   `regions` row가 먼저 있어야 한다. **같은 GeoJSON 파일로 시딩 가능하다** — geometry가 `MultiPolygon`이고 CRS가
   `CRS84`(= EPSG:4326, 경도 먼저)라 `regions.boundary_geom GEOGRAPHY(MULTIPOLYGON, 4326)`에 그대로 들어간다.
   → **`regions` 시딩 티켓이 필요하다** (Owner A, 3레벨: `sido` → `sgg` → `adm_cd2`).
   행정경계를 "DB에 안 넣는다"는 ADR 결정은 **지오코딩 서버의 조회용 폴리곤**에 대한 것이고, `regions`는
   FK 무결성·수집률(`region_stats`) 집계용이므로 둘은 양립한다. ADR에 이 구분을 명시할 것.
4. 지오코딩 API 예제의 `"grid_id": 1001`은 정수인데, MSG-78이 확정한 `grid_id`는 `"41642_110458"` 문자열이다.
   (상관관계 ID일 뿐이라 실해는 없으나 예제 갱신 권장.)
5. PostgreSQL 채택 근거로 "GeoHash 7 격자 양자화", "`ST_GeoHash` 내장"을 든다. MSG-78에서 표준 geohash를
   쓰지 않기로 확정했으므로 근거가 낡았다. (결론인 PostGIS 채택 자체는 유효 — GIST·뷰포트 쿼리 때문.)

---

## 미해결 질문

| # | 질문 | 상태 |
|---|---|---|
| Q1 | 대표 영상(`cover_video_id`) 지정 주체·시점 — 첫 점령 영상 자동 지정? 사용자 선택? | **결정됨 — 자동 지정** (아래) |
| Q2 | 전역 지도(Phase 2+)에서 여러 사용자의 점령을 어떻게 합성해 보여줄 것인가 — 사용자 원문 "그 이후에는 전역 지도가 있어서 다른 사람 건 어떻게 처리해야 할지 모르겠어" | **방향 확정 (Phase 2+ 설계 노트)** (아래) |

### Q1 결정 — 대표 영상 자동 지정

**첫 점령 영상을 `cover_video_id`로 자동 지정한다.** 사용자 선택 UI는 두지 않는다(Phase 2+ 여지).

- **MSG-68 구현은 바뀌지 않는다.** 본 티켓은 D8에 따라 `cover_video_id`를 계속 **NULL**로 둔다 —
  `videos` insert(MSG-66)가 먼저 있어야 FK 위반 없이 세팅 가능하기 때문이다.
- 자동 지정의 **구현 위치는 MSG-66**이다: videos row insert 직후, `occupy()`가 `isFirstOccupation == true`를
  돌려준 경우 그 `videoId`를 `user_grids.cover_video_id`에 세팅한다.
  → `occupy(userId, lat, lon, videoId)` 시그니처의 `videoId` 파라미터가 바로 이 자리다(§4 흐름 주석과 일치).
- 재방문(재업로드)은 대표 영상을 **바꾸지 않는다** — `isFirstOccupation == false`이면 손대지 않는다.
- **§후속 작업(Owner B 전달)에 규약 1건 추가 필요**: "첫 점령 시 그 영상을 cover로 자동 지정".

### Q2 방향 — 전역 지도 점령 합성 (Phase 2+ 설계 노트, MSG-68 범위 밖)

D2가 유예한 "사용자별 색 구분"의 후속 방향을 확정한다. **본 티켓(개인 write 경로)은 구현하지 않는다** —
전역 지도 read/타일 API가 생기는 별도 Phase 2+ 티켓의 입력이다.

- **내 점령**: 색으로 칠하지 않고 **체크 표시**로만 구분한다. (내 도감은 "채웠다/안 채웠다" 이진이면 충분)
- **타인 점령**: 색상의 **투명도 5단계(100 / 80 / 60 / 40 / 20 %)** 로 표현한다.
  투명도 = **그 격자를 점령한 사람 수(밀도)** 를 5구간으로 버킷팅한 값이다 (히트맵 성격). ※ 강도의 정의는 이 가정으로 확정.
- **줌 레벨별 렌더링(클러스터링) — 실현 가능하나 LOD 집계 필수.** 100×100m 셀을 모든 줌에서 그대로
  내려보내는 것은 불가능하다(전국 ≈ 수백만~수천만 셀). 표준 해법:
  - **PostGIS 벡터 타일(`ST_AsMVT`) + `{z}/{x}/{y}` 요청 단위.** 줌 `z`에 맞는 해상도로 서버에서 집계.
  - 고줌 = 실제 100m 셀, 중줌 = 격자 묶음(400m/1km) 집계, 저줌 = 광역 히트맵.
  - 밀도 집계 결과를 5구간으로 나누면 위 투명도 5단계에 그대로 대응된다.
  - 성능은 밀도 사전 집계 테이블/머티리얼라이즈드 뷰 + GIST 인덱스로 확보(뷰포트 쿼리 MSG-73 인프라 재사용).
- **차단 여부**: MSG-68을 차단하지 않는다. 다만 밀도 집계를 하려면 "격자별 전역 점령자 수"가 필요한데,
  D3에서 `grids` 전역 카운터를 **추가하지 않기로** 했으므로 → Phase 2+에서 집계 소스(전역 카운터 또는
  `user_grids` 집계 쿼리)를 별도로 정해야 한다. 이 결정은 그 티켓으로 넘긴다.

> ~~Q3: `grid.created` 이벤트를 어느 티켓이 발행하는가~~ — **D9로 해소됨.** 행정동 태깅을 DB 폴링으로
> 바꾸면서 이벤트 자체가 사라졌다. MSG-68은 이벤트 프로듀서를 갖지 않는다.

---

## 작업 로그

### 2026-07-13 — 구현 완료 (v6 스키마 기준)

**중대 변경: 구현 중 DB 스키마가 v6로 합의·재작성됨** (`V1__init.sql`, 사용자 직접 수정). 이로 인해 아래 스펙
결정들이 **폐기/변경**됐다 — 스펙 본문(§1·§2·§3·§5·D2·D4·D7)을 읽을 때 이 로그를 우선한다.

| 스펙 결정 | v6에서의 상태 |
|---|---|
| D7 `grids.region_code` (lazy insert 시 NULL, 배치 태깅) | **폐기.** grids에 `region_code` 컬럼 자체가 없다. 행정동 레이블은 `videos.region_code`로 이동(업로드 시점 판정). `Grid.regionCode`·registerIfAbsent의 region_code 삽입 제거 |
| §grids 컬럼 | **`grid_y`, `grid_x INT NOT NULL` 신규**(뷰포트 정수 스캔용) + `unique(grid_y,grid_x)`. registerIfAbsent가 `GridEncoder.decode`로 얻은 grid_y/grid_x를 함께 삽입 |
| §3 `user_grids` 서러게이트 `id` PK | **복합 PK `(user_id, grid_id)`로 전환.** `UserGrid`에서 `id` 제거, `@IdClass(UserGridId)`. `UserGridRepository<UserGrid, UserGridId>`. UPSERT 충돌 대상은 이제 PK |
| D2 `users.grid_color` 추가 안 함 | **뒤집힘.** v6가 `users.grid_color VARCHAR(10) NOT NULL DEFAULT 'BLUE'`를 포함(Q1/Q2 논의 및 브랜치명과 일치). 단 occupy 로직은 여전히 색 무관(이진) — glossary `grid_color` 절 정정 후속작업은 **취소** |
| D4 `videos.view_count` 컬럼 추가 | v6 스키마에 **이미 포함**. 별도 작업 불필요 |
| ENUM 타입 전반 | v6가 모든 `CREATE TYPE ENUM`을 `VARCHAR + CHECK`로 전환. 그 결과 **`User` 엔티티(Owner B)의 `provider`/`role` 매핑을 `@JdbcTypeCode(NAMED_ENUM)` → plain `@Enumerated(STRING)`로 수정**(부팅/validate 통과 위해 강제) |

**완료 모듈 (Owner A + 경계 1건):**
- `grid/entity/Grid` (region_code 제거, grid_y/grid_x·geom 미매핑)
- `grid/entity/UserGrid` + `grid/entity/UserGridId` (복합 PK)
- `grid/repository/GridRepository.registerIfAbsent` (grid_y/grid_x 삽입, `ON CONFLICT DO NOTHING`)
- `grid/repository/UserGridRepository.upsertOccupation` — **data-modifying CTE를 최상위 SELECT로 감싸** `RETURNING video_count`를 안정적으로 반환(스펙 §3이 경고한 `@Modifying`/RETURNING 함정 회피). 첫 점령 = 반환값 1
- `grid/service/GridOccupationService` + `impl/GridOccupationServiceImpl` (계약 인터페이스, `@Transactional`)
- `grid/dto/OccupationResult` (record, ResponseDto 접미사 없음)
- `grid/GridWkt` (축 순서 lat/lon → lon/lat WKT 어댑터)
- 경계: `user/entity/User` enum 매핑만 v6 정합 수정(Owner B 파일 — §후속작업 플래그)

**테스트 인프라:** Testcontainers **2.0.5**(Boot 4.1 BOM 관리 — 아티팩트명 `testcontainers-postgresql`/`testcontainers-junit-jupiter`, 패키지 `org.testcontainers.postgresql.PostgreSQLContainer`). `AbstractPostgisTest`는 **싱글턴 컨테이너**(JVM당 1회 start, `@ServiceConnection`) — `@Container` per-class 생명주기는 캐시된 `@DataJpaTest` 컨텍스트가 죽은 컨테이너를 가리켜 실패하므로. 이미지 `postgis/postgis:16-3.4-alpine`(CI 동일·arm64 네이티브).

**검증:** `./gradlew build` / `test --rerun-tasks` — **전체 79개 테스트 통과, 실패 0**(auth `@SpringBootTest`·`MsgbeApplicationTests` 포함 → 앱이 v6 위에서 정상 부팅). MSG-68 grid 테스트 22개: 축순서 회귀·CTE RETURNING·복합 PK·grids/user_grids 분리 모두 실증.

**제안 커밋 메시지:**
```
MSG-68 feat: 첫 점령 판단 + 색칠 write 경로 (GridOccupationService) — v6 스키마 정합
```

**Owner B(성민)에게 전달 필요:**
- `GridOccupationService` 호출 규약(videos insert 직후, 같은 트랜잭션) + 대표 영상 자동 지정(Q1)
- **`user/entity/User` enum 매핑 수정** — v6 ENUM→VARCHAR 전환에 따른 강제 변경. Owner B 소유 파일이라 공유.
- MSG-72 제약: `video_count == 0` 시 `user_grids` row 삭제 (첫 점령 판정 불변식)
