# MSG-73: 격자 색칠 응답 API (단일·viewport)

**Owner**: A (`com.msg.fillmap.grid.*`) — 순수 Owner A. `GridQueryService` 노출만 §계약 변경 참조

> 부모 에픽: MSG-48 "영상 기록" · 담당: KangJeong (Owner A) · Priority: Medium
> write 짝: **MSG-68** (첫 점령 판단 + 색칠 write 경로 — `GridOccupationService.occupy()`). 본 티켓은 그 **read 짝**이다.
> Jira 설명이 비어 있어 MSG-68 스펙의 반복 참조와 v6 스키마(`V1__init.sql`)·현재 코드(`status.md`)에서 도출했다.
> ✅ 최종 확정: **MSG-73 = 색칠 응답 전용.** `videos`(Owner B)를 전혀 건드리지 않는다. 미해결 질문 0개.
> ✅ 형제 티켓 경계: **MSG-85(bbox→쿼리 빌더)·MSG-86(공간 인덱스+EXPLAIN 검증)를 MSG-73이 흡수**. **MSG-90(대규모 viewport+커서)·MSG-87(대표 영상 랭킹)·격자 상세(내 영상 리스트)는 분리.**

---

## 개요

사용자가 지도에서 격자를 볼 때, **어떤 격자가 내 도감에 색칠돼 있는지**(점령 여부)를 내려주는 **read 경로**를 구현한다.
MSG-68이 만든 write 경로(`user_grids` row 생성 = 점령 = 색칠)의 결과를 화면에 되돌려주는 API 두 개다.

```
[단일]   grid_id  →  내 점령 여부 + video_count                    (격자 탭 시 색칠 상태)
[viewport] bbox(남서~북동)  →  그 범위 안에서 내가 점령한 격자 목록   (지도 팬/줌 시 색칠)
```

색칠의 정의는 glossary·MSG-68 D2와 동일하다: **색칠 여부 = 점령 여부 = `user_grids` (user_id, grid_id) row 존재 여부.**
백엔드는 색을 모른다 — 색상값(`users.grid_color`)은 프로필 표시용이고, 색칠 판정 자체는 이진(점령/미점령)이다.
미점령 격자는 응답에 담지 않는다(glossary "미점령 격자: 표시하지 않음").

**본 티켓은 `user_grids`(개인 도감, Owner A)만 읽는다. `videos` 테이블(Owner B)은 전혀 건드리지 않는다** — 그 격자의 내 영상 리스트를 보여주는 "격자 상세 조회"는 별도 티켓(Owner B)이다.

---

## 배경 · 목표

- **사용자 관점**: 지도를 움직이면 내가 방문(=점령)한 100×100m 격자가 내 색으로 채워져 보여야 한다. MSG-68은 "칠하는" 동작만 만들었고, "칠해진 것을 보여주는" 동작이 비어 있다.
- **목표**: 격자 색칠에 필요한 read 계약(`GridQueryService`)과 그것을 노출하는 조회 API 2종(단일·viewport)을 제공한다.
  Owner B(영상·도감 조회)가 이 계약을 소비할 수 있게 인터페이스로 경계를 긋는다.

---

## 선행 상태 (현재 코드 — `status.md` 확인 결과)

grid 도메인은 **순수 유틸 + 도감 엔티티만** 있고 조회 인프라는 전부 비어 있다. 본 티켓이 새로 만든다.

| 있음 | 없음 (본 티켓이 생성) |
|---|---|
| `grid/GridEncoder`, `grid/GridConstants` (순수 유틸) | `grid/entity/Grid` 엔티티 |
| `grid/entity/UserGrid`, `grid/entity/UserGridId` (복합 PK `@EmbeddedId`) | `grid/repository/GridRepository` |
| `response/*` (`SuccessResponse.of`, `ApiResponseDto`, `ErrorCodeIfs`) | `grid/service/GridQueryService` (+ `impl/`) |
| v6 스키마 `grids`(grid_y/grid_x + center_geom/bbox_geom), `user_grids`(복합 PK) | `grid/controller/GridController`, `grid/dto/*`, `grid/exception/GridErrorCode` |
| `video/exception/VideoErrorCode` (**3xxx 대역 이미 사용** — `3400 INVALID_COORDINATE`) | Testcontainers PostGIS 베이스(`AbstractPostgisTest`) — 재도입 |

> **중요**: MSG-68 백업 스펙이 만들었다고 기록한 `Grid` 엔티티·`GridRepository`·`GridOccupationService`·Testcontainers 인프라는
> **현재 브랜치 코드에 존재하지 않는다**(폐기됨). 본 티켓은 이들을 "이미 있다"고 가정하지 않고, 조회에 필요한 부분을 **처음부터** 만든다.
> `GridEncoder` 시그니처는 실측 확인됨: `encode(lat,lon)→String`, `decode(gridId)→GridIndex(gridY,gridX)`,
> `center(gridId)→GridPoint(lat,lon)`, `bbox(gridId)→List<GridPoint>`(닫힌 링 5점).

---

## 성공 기준

1. `GET /api/grids/{gridId}`가 로그인 사용자의 그 격자 **점령 여부**(`occupied`) + `videoCount`를 반환한다. `user_grids` lookup만 사용.
2. `GET /api/grids?swLat=..&swLng=..&neLat=..&neLng=..`가 bbox 범위 안에서 **내가 점령한 격자만** 목록(색칠용 최소 필드)으로 반환한다(미점령 제외).
3. 두 API 모두 `SuccessResponse.of(...)`로 감싸 HTTP 200 + `developCode 200`으로 응답한다.
4. 잘못된 bbox(남서 > 북동, 범위 초과 등)는 `GridErrorCode` 기반 `ApiException`으로 일관 처리된다.
5. `GridQueryService` 인터페이스가 grid 도메인에 노출돼 Owner B가 import 가능하다.
6. Testcontainers PostGIS 위에서 공간/범위 쿼리가 실제 스키마(V1 Flyway 적용)로 검증된다 — 전체 테스트 green.
7. **viewport 조회 전략을 A(정수 범위 스캔)/B(GIST 공간쿼리) 두 접근을 모두 구현하고 EXPLAIN(ANALYZE) 벤치마크로 채택**한다 — 하나를 기본 경로로, 다른 하나를 폴백/제거 대상으로 결정한 근거가 스펙/PR에 남는다.

---

## 스코프 (MSG-73이 하는 것)

- `Grid` 엔티티 (조회에 필요한 컬럼 매핑: `grid_id`, `grid_y`, `grid_x`. 지오메트리 매핑은 §데이터 모델 — 벤치마크 기간 B 비교용)
- `GridRepository` — 단일 조회(`user_grids` lookup) + viewport 범위 조회 메서드 (`user_grids` JOIN) — **접근 A·B 둘 다 구현**
- **MSG-85 흡수**: bbox 좌표 → `grid_y`/`grid_x` 범위 변환(쿼리 빌더) 로직
- **MSG-86 흡수**: `idx_grids_bbox`(GIST) 활용 + `EXPLAIN(ANALYZE)` 검증, 정수 범위 스캔과 성능 비교
- `GridQueryService` (인터페이스) + `impl/GridQueryServiceImpl` — 계약 인터페이스 노출
- `GridController` — 3-layer, 얇게 (파싱 + 서비스 호출 + `SuccessResponse` 변환만)
- 응답 DTO: `GridCellResponseDto`(단일, occupied+videoCount), `OccupiedGridResponseDto`(viewport 항목, 최소 필드)
- `GridErrorCode` — 신규 (§계약 변경에서 대역 확정)
- Testcontainers `AbstractPostgisTest` 재도입 + 조회 쿼리 테스트(A·B 각각)

## 스코프 밖

| 항목 | 소관 |
|---|---|
| **격자 상세 조회(그 격자의 내 영상 리스트)** | **별도 티켓 / Owner B** (`videos` 접근) — MSG-73은 색칠 상태만, videos를 건드리지 않음 |
| 격자별 대표 영상 **선정 랭킹 로직**(조회수→최신) | **MSG-87** (분리) |
| 대규모 viewport 응답 + **Cursor 페이지네이션** + 항목 확장 | **MSG-90** (분리) |
| 격자 write(점령/재방문), `cover_video_id` 세팅 | MSG-68 / MSG-66 |
| 사용자별 색 합성·친구/전체 지도 | Phase 2+ |

---

## API 명세

인증: 두 API 모두 로그인 필요(`userId`는 SecurityContext에서 획득 — auth 도메인 기존 방식 재사용).

### 1) 단일 격자 조회 — `GET /api/grids/{gridId}`

격자 탭 시 그 격자의 **색칠 상태**. `user_grids` lookup 하나로 끝난다 — `videos` 접근 없음.

- Path: `gridId` (예: `"41642_110458"`)
- 성공 200 `body` = `GridCellResponseDto`

| 필드 | 타입 | 의미 |
|---|---|---|
| `gridId` | String | 격자 논리 식별자 |
| `occupied` | boolean | 내 점령 여부(= 색칠 여부). `user_grids` row 존재 |
| `videoCount` | Integer | 내가 그 격자에 올린 영상 수(`user_grids.video_count` 컬럼). 미점령이면 0 |

- `coverVideoId`(`user_grids` 컬럼)는 raw id라 videos 접근 없이 실을 수 있으나, **순수 색칠엔 불필요 → 기본 미포함**. (필요 시 raw id만 싣고 해석하지 않는다.)
- 에러: `gridId` 포맷 불량 → `GridErrorCode.INVALID_GRID_ID`.
- **미점령 격자도 404가 아니라 200 + `occupied=false` + `videoCount=0`**로 응답한다(격자는 항상 존재하는 논리 개념 — glossary).

### 2) viewport 조회 — `GET /api/grids`

지도 화면 bbox 안에서 내가 점령한 격자들(색칠용, **최소 필드**).

- Query: `swLat`, `swLng`, `neLat`, `neLng` (남서·북동 좌표, 모두 필수 `@RequestParam`)
- 성공 200 `body` = `List<OccupiedGridResponseDto>` (미점령 격자는 미포함 — glossary)

| 필드 | 타입 | 의미 |
|---|---|---|
| `gridId` | String | 점령한 격자 식별자 |
| `gridY` / `gridX` | int | FE 렌더링용 정수 인덱스(셀 위치 계산) |

- **coverVideo 미포함** — 색칠 = 셀 위치만. 영상 관련 확장은 MSG-90.
- 에러:
  - 남서 > 북동(위/경도 뒤집힘) → `GridErrorCode.INVALID_VIEWPORT`
  - bbox 면적이 상한 초과(과도한 스캔 방지) → `GridErrorCode.VIEWPORT_TOO_LARGE`. **본 티켓은 상한 내 기본 viewport만** 다루고, 상한 초과 대규모 범위는 MSG-90(Cursor 페이지네이션)로 넘긴다.

---

## 도메인 로직

### 단일 조회

1. `gridId` 포맷 검증(`GridEncoder.decode` 파싱 성공 여부). 실패 → `INVALID_GRID_ID`.
2. `user_grids`에서 `(userId, gridId)` 조회 → 있으면 `occupied=true` + `videoCount`(= `video_count` 컬럼), 없으면 `occupied=false` + `videoCount=0`.
3. 끝. `videos`·`grids` 지오메트리 접근 없음.

### viewport 조회 — 접근 A·B 둘 다 구현 후 벤치마크로 채택

**내가 점령한 격자 = `user_grids`(내 것) ∩ bbox 범위.** 두 접근을 **모두 구현**한다:

| 접근 | 방식 | 특성 |
|---|---|---|
| **(A) 정수 범위 스캔** | bbox 남서/북동 → `GridEncoder`로 `grid_y`/`grid_x` 범위 환산 → `WHERE grid_y BETWEEN .. AND grid_x BETWEEN ..` + `user_grids` JOIN | PostGIS 연산 없음, btree(`uq_grids_yx`) 사용. v6 주석이 이 목적("뷰포트 정수 범위 스캔용")으로 grid_y/grid_x를 추가함 |
| **(B) GIST 공간 쿼리** | `WHERE ST_Intersects(bbox_geom, ST_MakeEnvelope(swLng, swLat, neLng, neLat, 4326))` + `idx_grids_bbox` + `user_grids` JOIN | GEOGRAPHY/GIST 연산. 지오메트리 정밀 |

#### 벤치마크 절차 (MSG-86 흡수)

1. 동일 데이터셋(대·중·소 밀도, 경계에 걸친 셀 포함)에 대해 A·B 각각 `EXPLAIN(ANALYZE)`를 수집한다.
2. **판단 기준**:
   - **연산량/실행 계획**: 인덱스 사용 여부(btree vs GIST), 스캔 행 수, 실행 시간.
   - **경계 셀 정확도**: 뷰포트 경계에 걸친 셀을 A(정수 BETWEEN)와 B(ST_Intersects)가 **동일 집합**으로 반환하는지(불일치 시 A의 범위 환산 규칙을 정정).
   - **응답 시간**: 대표 뷰포트 크기에서 p50/p95.
3. **채택**: 우수한 쪽을 **기본 경로**로 확정하고, 다른 쪽은 폴백으로 남기거나 제거한다. 결정 근거를 PR·본 스펙 작업로그에 남긴다.
   - 채택 결과에 따라 `grids`의 `center_geom`/`bbox_geom` + `idx_grids_bbox` **유지/제거**를 v6 주석("벤치마크 후 제거 검토")대로 판단한다.

#### 축 순서 (회귀 테스트 필수)

PostGIS는 `(경도, 위도)`, `GridEncoder.GridPoint`는 `(lat, lon)`. (B)의 `ST_MakeEnvelope(swLng, swLat, neLng, neLat, 4326)` 인자 순서를 **회귀 테스트로 고정**한다(MSG-68 백업이 겪은 축 뒤집힘 버그 재발 방지 — 한국 좌표는 lat≈37, lon≈127로 값이 비슷해 뒤집혀도 조용히 통과할 수 있다).

핵심 쿼리는 항상 **로그인 사용자의 `user_grids`로 제한**한다(전역 점령이 아니라 개인 도감 — glossary "MVP는 개인 도감만").

---

## 데이터 모델

**Flyway 마이그레이션 불필요.** v6 `V1__init.sql`이 조회에 필요한 컬럼·인덱스를 이미 갖고 있다(스키마 변경 없음).
- `grids`: `grid_id`(PK), `grid_y`/`grid_x INT` + `uq_grids_yx`(정수 범위 스캔), `center_geom`/`bbox_geom` + `idx_grids_bbox`(GIST — 접근 B 비교용).
- `user_grids`: 복합 PK `(user_id, grid_id)` — 단일 조회의 정확한 lookup 키, viewport JOIN 대상.

**신규 엔티티 `grid/entity/Grid`** (조회 전용 최소 매핑):

| 필드 | 컬럼 | 매핑 | 비고 |
|---|---|---|---|
| `gridId` | `grid_id` | `@Id VARCHAR(20)` | 자연키, `@GeneratedValue` 없음 |
| `gridY` | `grid_y` | `Integer` | viewport 범위 스캔·응답 |
| `gridX` | `grid_x` | `Integer` | |
| — | `center_geom`/`bbox_geom` | **접근 B 비교를 위해 벤치마크 기간 매핑 또는 네이티브 쿼리 처리** | `hibernate-spatial` JTS 매핑 또는 native `ST_Intersects`. 벤치마크로 A가 채택되면 제거 검토(coding-principles §2 — 최종적으로 쓰지 않는 매핑은 남기지 않음) |

- `first_seen_at`/`created_at`은 조회 응답에 불필요하면 매핑하지 않는다. `region_code`는 grids에 없음(v6에서 videos로 이동).
- 컨벤션: `@Entity @Table(name="grids")`, `@Getter`, `@NoArgsConstructor(access=PROTECTED)`, `@Setter` 금지.
- `ddl-auto: validate`는 엔티티에 없는 DB 컬럼을 문제 삼지 않으므로 부분 매핑이 안전하다.

- **단일 조회는 `user_grids` lookup으로 `occupied`+`videoCount`만** 산출한다(`Grid` 엔티티·지오메트리 불필요).
- viewport 조회는 `Grid` 엔티티를 직접 반환하지 않고 `user_grids` JOIN 결과를 DTO/프로젝션으로 받는 편이 N+1을 피한다.

---

## 계약 변경

**1건. Owner B(성민) 확인 필요.**

### `GridQueryService` 신규 노출 (A 제공 → B 소비)

`grid/service/GridQueryService` — infrastructure.md 계약 인터페이스 목록에 등재된 경계면.

```
GridCellView getCell(Long userId, String gridId);              // occupied + videoCount (videos 접근 없음)
List<OccupiedGridView> getOccupiedInViewport(Long userId, ViewportBounds bounds);
```

- 반환은 서비스 간 내부 뷰 객체(`XxxView`/record), HTTP 응답 DTO(`GridCellResponseDto` 등)는 컨트롤러에서 변환.
  (MSG-68이 `OccupationResult`에 `ResponseDto` 접미사를 붙이지 않은 선례와 일치.)
- `getCell`은 색칠 상태(occupied+videoCount)만 반환한다 — **영상 리스트 없음, `videos` 미접근.**
- B가 소비 시점을 확정해야 최종 시그니처가 고정된다 → **리뷰 시 상대 팀원 확인 필수.**

`GridOccupationService`(MSG-68, write) / `HotZoneService` / `UserGridQueryService`(B 제공) / `UserOidcCommandService` 시그니처는 **불변**.

> **developCode 대역 충돌 주의**: MSG-68 백업 스펙은 grid에 `3xxx`를 예약한다고 적었으나, **`video/exception/VideoErrorCode`가 이미 `3xxx`를 사용 중**(`3400 INVALID_COORDINATE`, 확인함). auth=`2xxx`, video=`3xxx`, 공통 `ErrorCode`=`9xxx`(infrastructure.md).
> → `GridErrorCode`는 **`4xxx` 대역을 제안**한다(겹치지 않는 대역). 팀 대역표에 grid=4xxx를 기입해야 한다.

`GridErrorCode` 초안:

| 상수 | code | HttpStatus | message |
|---|---|---|---|
| `INVALID_GRID_ID` | 4400 | BAD_REQUEST | 올바르지 않은 격자 식별자입니다 |
| `INVALID_VIEWPORT` | 4401 | BAD_REQUEST | 유효하지 않은 지도 범위입니다 |
| `VIEWPORT_TOO_LARGE` | 4402 | BAD_REQUEST | 조회 범위가 너무 넓습니다 |

`ApiException(GridErrorCode.XXX)`로만 던진다(response-pattern.md). 컨트롤러/서비스에서 응답을 직접 조립하지 않는다.

---

## 테스트 시나리오 (JUnit5 + AssertJ · 한국어 백틱 메서드명)

### 테스트 인프라 (재도입)

MSG-68 백업이 폐기됐으므로 Testcontainers PostGIS 베이스를 다시 만든다. 이미지 `postgis/postgis:16-3.4-alpine`,
**싱글턴 컨테이너 + `@ServiceConnection`**(캐시된 `@DataJpaTest` 컨텍스트가 죽은 컨테이너를 가리키는 문제 회피 — MSG-68 백업 작업로그의 교훈).
Flyway가 `V1__init.sql`을 실제로 실행하므로 스키마·인덱스·쿼리가 함께 검증된다. 공통 베이스 `AbstractPostgisTest`로 뺀다.

### `GridRepository` (`@DataJpaTest` + Testcontainers) — 접근 A·B 각각

- `점령한_격자를_단일_조회하면_occupied가_참이고_videoCount를_반환한다`
- `점령하지_않은_격자를_단일_조회하면_occupied가_거짓이고_videoCount는_0이다`
- `정수범위스캔_A는_뷰포트_안의_내_점령_격자만_반환한다`
- `GIST공간쿼리_B는_뷰포트_안의_내_점령_격자만_반환한다`
- `정수범위스캔_A와_GIST_B는_동일한_격자_집합을_반환한다` — 경계 셀 정확도(벤치마크 정합성)
- `뷰포트_범위_밖의_점령_격자는_반환하지_않는다`
- `뷰포트에_내가_점령하지_않은_격자는_반환하지_않는다` — 미점령/타인 점령 제외
- `다른_사용자의_점령_격자는_내_뷰포트_결과에_포함되지_않는다` — 개인 도감 격리
- `경계_셀이_뷰포트_범위에_포함된다` — grid_y/grid_x BETWEEN 경계 정확도
- `GIST쿼리는_경도위도_순서로_envelope를_만든다` — **축 순서 회귀(필수)**

### `GridQueryServiceImpl` (통합)

- `미점령_격자_조회는_occupied가_거짓이고_videoCount가_0이다`
- `점령_격자_조회는_occupied가_참이고_videoCount를_반환한다`
- `뷰포트_조회는_내가_점령한_격자_목록을_최소필드로_반환한다`
- `남서_좌표가_북동보다_크면_INVALID_VIEWPORT를_던진다`
- `면적_상한을_초과하면_VIEWPORT_TOO_LARGE를_던진다`
- `포맷이_틀린_gridId는_INVALID_GRID_ID를_던진다`

### `GridController` (`@WebMvcTest` 또는 통합)

- `단일_격자_조회_API는_200과_점령여부와_videoCount를_반환한다`
- `뷰포트_조회_API는_필수_좌표가_없으면_400이다`

### 벤치마크 (MSG-86 흡수)

- A·B 각각 `EXPLAIN(ANALYZE)` 계획 수집(테스트가 아닌 검증 스크립트/문서화 가능) → 채택 근거를 작업로그에 기록.

---

## 미해결 질문

**없음 — 전부 확정.** (MSG-73은 색칠 응답 전용, `videos` 미접근. 격자 상세 조회는 별도 티켓/Owner B.)
