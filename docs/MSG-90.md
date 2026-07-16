# MSG-90: viewport 격자 응답 API + Cursor 페이지네이션

**Owner**: A (`com.msg.fillmap.grid.*`) — 순수 Owner A(KangJeong). 단, `GridQueryService` 시그니처가 바뀌므로 §계약 변경은 Owner B 확인 필수

> 부모 read 기능: **MSG-73**(viewport 색칠 조회 기본형, develop 머지 완료)의 **진화 티켓**이다.
> 새 엔드포인트가 아니라 기존 `GET /api/grids` viewport 조회에 **cursor 페이지네이션을 추가**하고, 벤치마크로 확정된 **전략 A를 기본 경로로 고정**(`?strategy` 제거)한다.
> Jira 설명이 비어 있어, 확정된 설계 결정(전략 A 채택·keyset 커서·level 없음·SLO)과 develop(a44f9c9) 실코드에서 도출했다.
> 형제 경계: MSG-73(기본형·완료) / MSG-89(Redis 캐시·스코프 밖) / MSG-134(전송·SLO 결정·완료) / MSG-87(대표영상·스코프 밖).

---

## 개요

지도 뷰포트 안에서 내가 점령한 격자가 서울급 넓은 범위에서 수만 개에 달할 수 있다. MSG-73은 이를 **한 번에 전체 리스트**로 내려준다 — 응답이 비대해지고 SLO(p95<300ms)를 위협한다.
MSG-90은 같은 조회를 **cursor(keyset) 페이지네이션**으로 쪼개 내려주고, MSG-73이 벤치마크용으로 남겨둔 **전략 A/B 선택(`?strategy`)을 정리**해 전략 A(정수 범위 스캔)를 기본 경로로 고정한다.

```
[MSG-73] bbox → 내가 점령한 격자 전체 리스트                       (한 방에)
[MSG-90] bbox + size + cursor → 정렬된 한 페이지 + nextCursor      (keyset 순회)
```

색칠의 정의·개인 도감 격리·미점령 제외는 MSG-73·glossary와 동일하다: **색칠 = 점령 = 로그인 사용자의 `user_grids` (user_id, grid_id) row 존재**. 본 티켓도 `user_grids`(개인 도감, Owner A)만 읽고 `videos`·`user` 도메인은 전혀 건드리지 않는다.

---

## 배경 · 목표

- **사용자/제품 관점**: 지도를 넓게 줌아웃하거나 밀도 높은 지역을 볼 때, 색칠 격자를 끊김 없이(FE가 순차 로딩) 렌더링해야 한다. 전체를 한 응답에 담으면 지연·메모리·타임아웃 위험이 있다.
- **목표**:
  1. viewport 색칠 조회를 **keyset cursor 페이지네이션**으로 제공(OFFSET 금지 — 깊은 페이지에서 선형 비용 회피, 전략 A의 btree 정렬을 그대로 활용).
  2. 벤치마크로 확정된 **전략 A를 기본 경로로 고정**하고 `?strategy` 파라미터를 API에서 제거해 계약을 단순화한다.
  3. SLO(MSG-134): **p95 < 300ms · 5xx < 1% · stale ≤ 30s** 를 페이지 단위에서 만족.

---

## 선행 상태 (develop a44f9c9 — 실코드 확인 결과)

MSG-73이 만든 색칠 조회 인프라가 이미 존재한다. MSG-90은 이를 **수정/확장**한다(신규 도메인 생성 아님).

| 구성요소 | 현재(MSG-73) | MSG-90 변경 |
|---|---|---|
| `grid/controller/GridController#getOccupiedInViewport` | `swLat/swLng/neLat/neLng` + `@RequestParam(defaultValue="A") ViewportStrategy strategy`, 반환 `List<OccupiedGridResponseDto>` | `strategy` **제거**, `size`·`cursor` **추가**, 반환 페이지 래퍼 DTO |
| `grid/service/GridQueryService` | `getOccupiedInViewport(userId, bounds)` + `(userId, bounds, strategy)` 오버로드 | 페이지 메서드 **추가**, strategy 오버로드 **제거**(Open Q) |
| `grid/service/impl/GridQueryServiceImpl` | `switch(strategy)`로 A/B 분기, `MAX_VIEWPORT_SPAN_DEG=0.5` 검증 | A 고정 + keyset·size 검증·nextCursor 산출 |
| `grid/repository/GridRepository` | `findOccupiedInRange`(A, btree) / `findOccupiedByIntersects`(B, GIST) | A에 커서/정렬/limit 추가한 페이지 메서드 |
| `grid/dto/` | `OccupiedGridResponseDto(gridId, gridY, gridX)` | 재사용 + 페이지 래퍼 신설 |
| `grid/service/ViewportStrategy` (enum A/B, DEFAULT=A) | 사용 중 | 유지/제거 = Open Q |
| `grid/exception/GridErrorCode` | 4400/4401/4402 | 신규 상수 추가(§계약 변경) |

- `OccupiedGridProjection`: `getGridId()`/`getGridY():Integer`/`getGridX():Integer` — 실측 확인.
- `GridEncoder`: `encode(lat,lon)→String`, `decode(gridId)→GridIndex(gridY:long, gridX:long)` — 단일 진실 원천, cursor 파싱에 재사용 가능.
- `grids`에 `uq_grids_yx`(btree, `grid_y, grid_x`) 존재 → keyset `ORDER BY grid_y, grid_x`가 인덱스 정렬과 정합.

---

## 성공 기준

1. `GET /api/grids?swLat=..&swLng=..&neLat=..&neLng=..&size=..&cursor=..`가 bbox 안 내 점령 격자를 **정렬된 한 페이지**(최대 `size`개) + `nextCursor`로 반환한다.
2. `cursor` 없이 첫 요청 → 정렬 첫 페이지. 응답의 `nextCursor`를 그대로 다음 요청에 넣으면 **다음 페이지**가 이어진다(keyset 왕복).
3. **마지막 페이지의 `nextCursor`는 `null`**이다(더 이상 없음).
4. **모든 페이지를 이어붙인 집합 = 비페이지(MSG-73 전체 리스트) 결과 집합**과 동일하다(누락·중복 없음).
5. `?strategy` 파라미터가 API에서 **제거**되고, viewport 조회는 항상 전략 A(정수 범위 스캔) 경로로 실행된다.
6. 잘못된 `cursor`·범위 밖 `size`는 `GridErrorCode` 기반 `ApiException`으로 400 처리된다.
7. 응답은 `SuccessResponse.of(...)`로 감싸 HTTP 200 + `developCode 200`. OFFSET 미사용.
8. 스키마 변경 없음(v6, `V1__init.sql`). `videos`·`user` 미접근. 전체 테스트 green(기존 strategy 의존 테스트 정리 포함).

---

## 스코프

**하는 것**
- `GridController#getOccupiedInViewport` 시그니처 변경: `strategy` 제거, `size`(선택, 기본값)·`cursor`(선택) 추가.
- `GridQueryService`에 페이지 조회 메서드 추가, strategy 오버로드 정리(§계약 변경·Open Q).
- `GridRepository`: 전략 A 쿼리에 keyset 조건 + `ORDER BY grid_y, grid_x` + `LIMIT` 추가한 페이지 메서드.
- cursor 인코딩/디코딩 + 검증, `size` 상한 검증.
- 페이지 래퍼 응답 DTO 신설(`OccupiedGridResponseDto` 항목 재사용).
- `GridErrorCode` 신규 상수(cursor/size).
- 테스트: keyset 왕복·마지막 페이지·전체 순회 정합·검증 실패.

**스코프 밖**

| 항목 | 소관 |
|---|---|
| Redis 캐시(응답/타일 캐싱) | **MSG-89** (단, cursor 설계가 캐시 키와 충돌하지 않는지 §아키텍처 노트만) |
| 전송 방식·SLO 결정 | **MSG-134** (완료·문서) |
| 격자별 대표 영상·항목 확장(coverVideo 등) | **MSG-87** / Owner B (`videos` 접근) |
| level(줌) 파라미터·집계/클러스터링 | **없음/Phase 2** (MSG-134 확정: bbox가 줌을 담고 span 상한 0.5° 유지) |
| B(GIST) 경로를 실서비스 경로로 쓰는 것 | 폐기(전략 A 확정) |

---

## API 명세

인증: 로그인 필요(`userId`는 `@AuthenticationPrincipal AuthPrincipal`에서 획득 — 기존 방식).

### `GET /api/grids` (viewport 색칠, 페이지)

| 파라미터 | 타입 | 필수 | 기본 | 의미 |
|---|---|---|---|---|
| `swLat` `swLng` `neLat` `neLng` | double | ✅ | — | 남서·북동 bbox 좌표(MSG-73과 동일) |
| `size` | int | ✗ | **1000** | 한 페이지 최대 항목 수. 상한 **5000** |
| `cursor` | String | ✗ | null(첫 페이지) | 직전 응답의 `nextCursor`. opaque 토큰 |

- `strategy` 파라미터 **없음**(제거됨). 항상 전략 A.
- 성공 200 `body` = `OccupiedGridPageResponseDto`:

| 필드 | 타입 | 의미 |
|---|---|---|
| `grids` | `List<OccupiedGridResponseDto>` | 이 페이지의 점령 격자(정렬됨). 항목은 MSG-73과 동일: `gridId`, `gridY`, `gridX` |
| `nextCursor` | String \| null | 다음 페이지 커서. **더 없으면 `null`** |

**요청 예시**
```
GET /api/grids?swLat=37.50&swLng=127.00&neLat=37.55&neLng=127.05&size=1000
GET /api/grids?swLat=37.50&swLng=127.00&neLat=37.55&neLng=127.05&size=1000&cursor=NDE2NDNfMTEwNDYw
```

**응답 예시**
```json
{
  "developCode": 200,
  "httpStatus": "OK",
  "message": "성공",
  "body": {
    "grids": [
      { "gridId": "41642_110458", "gridY": 41642, "gridX": 110458 },
      { "gridId": "41642_110459", "gridY": 41642, "gridX": 110459 },
      { "gridId": "41643_110460", "gridY": 41643, "gridX": 110460 }
    ],
    "nextCursor": "NDE2NDNfMTEwNDYw"
  }
}
```

**마지막 페이지**: `"nextCursor": null`.

**에러**

| 조건 | 코드 |
|---|---|
| bbox 좌표 누락(하나라도 null) | `INVALID_VIEWPORT` (4401, 기존) |
| 남서 > 북동(뒤집힘) | `INVALID_VIEWPORT` (4401, 기존) |
| bbox 한 변 span > 0.5° | `VIEWPORT_TOO_LARGE` (4402, 기존) |
| `cursor` 디코드 실패·형식 불량 | **`INVALID_CURSOR` (4403, 신규)** |
| `size` ≤ 0 또는 > 5000 | **`INVALID_PAGE_SIZE` (4404, 신규)** |

---

## 도메인 로직

### cursor 인코딩/정렬 규칙 (핵심)

- **정렬 키**: `(grid_y, grid_x)` 오름차순 복합. 전략 A의 `uq_grids_yx`(btree) 정렬과 일치해 추가 정렬 비용 없음.
- **keyset 조건**: 커서가 있으면
  ```sql
  WHERE ... AND (g.grid_y, g.grid_x) > (:cursorY, :cursorX)
  ```
  PostgreSQL 행 값 비교(row-value comparison). **OFFSET 금지.** 커서 없으면 이 조건 생략(첫 페이지).
- **정렬**: `ORDER BY g.grid_y, g.grid_x`.
- **cursor 값**: 마지막으로 내려준 항목의 `(gridY, gridX)`. **opaque 토큰**으로 노출한다 —
  **결정: `Base64URL("{gridY}_{gridX}")`.** 예: `"41643_110460"` → `NDE2NDNfMTEwNDYw`.
  - 근거: 내부 정렬 키를 노출/의존하지 못하게 하는 opaque 계약(FE는 토큰을 그대로 되돌려주기만). 향후 키 구성이 바뀌어도 클라이언트 무영향. 디코드 후에는 `GridEncoder`와 동일한 `"{y}_{x}"` 파싱을 재사용.
  - 디코드 실패(Base64 불량)·파싱 실패(정수 아님·구분자 없음) → `INVALID_CURSOR`.

### 페이지 산출 절차 (`GridQueryServiceImpl`)

1. bbox 검증(기존 `validateBounds`: 뒤집힘 → `INVALID_VIEWPORT`, span 상한 → `VIEWPORT_TOO_LARGE`).
2. `size` 검증: `1 ≤ size ≤ 5000` 아니면 `INVALID_PAGE_SIZE`.
3. `cursor` 있으면 디코드 → `(cursorY, cursorX)`; 실패 → `INVALID_CURSOR`. 없으면 첫 페이지.
4. bbox 남서/북동을 `GridEncoder`로 `grid_y/grid_x` 정수 범위로 환산(기존 `queryByRange` 로직 재사용).
5. 리포지토리 A 페이지 쿼리를 **`LIMIT size + 1`(lookahead)** 로 실행 — `user_grids` JOIN, 로그인 사용자로 제한(개인 도감).
6. 반환 행이 `size + 1`개면: 다음 페이지 존재 → 앞 `size`개만 응답, `nextCursor = encode(size번째 항목의 gridY, gridX)`.
   `size`개 이하면: 마지막 페이지 → 전부 응답, `nextCursor = null`.
   - lookahead(+1)로 **빈 마지막 페이지를 만들지 않는다**(정확히 size로 나눠떨어질 때도 nextCursor가 null이 되도록).

핵심 쿼리는 항상 **로그인 사용자의 `user_grids`로 제한**(전역 아님 — glossary "MVP는 개인 도감만"). 미점령·타인 점령 격자는 응답에 담지 않는다.

### 전략 A 고정

- 부하테스트 판정(k6 40VU: A p95 96.5ms vs B 252.7ms, MSG-73 작업로그 EXPLAIN 근거와 방향 일치)으로 **전략 A 확정**.
- API에서 `?strategy` 제거. `switch(strategy)` 분기 삭제, A 경로 직접 호출.
- 전략 B 쿼리(`findOccupiedByIntersects`)·`ViewportStrategy` enum·`grids.center_geom/bbox_geom`+`idx_grids_bbox`의 **삭제 여부는 Open Question**(벤치 이력 보존 vs coding-principles §2 데드코드 제거).

---

## 데이터 모델

**Flyway 마이그레이션 불필요.** v6 `V1__init.sql`이 필요한 모든 컬럼/인덱스를 이미 보유.
- `grids.grid_y/grid_x` + `uq_grids_yx`(btree) — keyset 정렬·범위 스캔의 인덱스 지지.
- `user_grids` 복합 PK `(user_id, grid_id)` — viewport JOIN·개인 도감 격리.
- 신규 컬럼/인덱스/엔티티 없음. `videos`·`users` 미접근.

---

## 계약 변경

**Owner B(성민) 확인 필요.** `GridQueryService`는 infrastructure.md 계약 인터페이스(A 제공 → B 소비) 경계면이며, 시그니처가 바뀐다.

### `GridQueryService` 변경

```
// 추가 (컨트롤러가 사용하는 페이지 경로)
OccupiedGridPage getOccupiedInViewport(long userId, ViewportBounds bounds, GridCursor cursor, int size);

// 제거 (전략 A 확정 → 선택 오버로드 불필요) — Open Q1에 따라 유지/제거
List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds, ViewportStrategy strategy);   // ← 삭제 대상

// 유지 (전체 리스트 — B 소비 호환 + 테스트 오라클: 전체 순회 == 이 결과)
List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds);
```

- `GridCursor`: nullable 내부 값객체(`Long gridY`, `Long gridX`) 또는 opaque String을 서비스가 파싱. **컨트롤러 ↔ 서비스 경계에서 opaque String을 받고 서비스가 디코드**하는 방안 권장(인코딩 규칙을 도메인에 응집). HTTP 응답 DTO 변환은 컨트롤러 책임(MSG-73 패턴 일치).
- `OccupiedGridPage`: 서비스 간 내부 뷰 record `{ List<OccupiedGridView> items, GridCursor nextCursor }`. HTTP DTO(`OccupiedGridPageResponseDto`)는 컨트롤러에서 변환.
- **2-arg `getOccupiedInViewport(userId, bounds)`는 유지**(비페이지 전체 리스트) — B가 소비 중일 수 있고, 성공 기준 4의 정합 테스트 오라클이다. 제거하지 않는다(non-breaking).
- **B가 페이지 API로 이행할지/2-arg를 계속 쓸지 리뷰에서 확정 필요.**

`GridOccupationService` / `HotZoneService` / `UserGridQueryService` / `UserOidcCommandService` 시그니처는 **불변**.

### `GridErrorCode` 신규 (4xxx 대역)

| 상수 | code | HttpStatus | message |
|---|---|---|---|
| `INVALID_CURSOR` | 4403 | BAD_REQUEST | 유효하지 않은 커서입니다 |
| `INVALID_PAGE_SIZE` | 4404 | BAD_REQUEST | 페이지 크기가 허용 범위를 벗어났습니다 |

`ApiException(GridErrorCode.XXX)`로만 던진다(response-pattern.md). 컨트롤러/서비스에서 응답을 직접 조립하지 않는다.

---

## 아키텍처 노트 (MSG-89 캐시와의 정합)

cursor는 `(grid_y, grid_x)` 결정적 정렬에서만 파생되며 요청 파라미터로만 좌우된다(서버 상태 무관). MSG-89가 응답을 캐싱할 때 캐시 키는 `(userId, bbox, size, cursor)` 조합으로 안전하게 구성 가능하고, keyset 페이지 경계가 커서 값에 고정돼 있어 **캐시 키 충돌·경계 흔들림이 없다**. stale ≤ 30s(MSG-134)는 캐시 TTL로 흡수(MSG-89 소관). 본 티켓은 캐시를 구현하지 않는다.

---

## 테스트 시나리오 (JUnit5 + AssertJ · 한국어 백틱 메서드명)

테스트 인프라는 MSG-73과 동일: `@SpringBootTest` + `local` 프로파일(실 PostGIS, Flyway V1), 컨트롤러는 `+@AutoConfigureMockMvc`.

### `GridRepository` (페이지 쿼리 A)
- `커서없이_조회하면_정렬첫페이지를_size만큼_반환한다`
- `커서이후_조회하면_그_커서보다_큰_격자만_grid_y_grid_x_순으로_반환한다`
- `페이지를_끝까지_이어붙이면_비페이지_전체결과와_동일_집합이다` (누락·중복 없음)
- `결과는_항상_grid_y_grid_x_오름차순_정렬이다`
- `뷰포트_밖_격자와_타인_점령_격자는_페이지에_포함되지_않는다` (개인 도감 격리 유지)

### `GridQueryServiceImpl` (페이지 서비스)
- `첫페이지_nextCursor를_다음요청에_넣으면_다음페이지가_이어진다` (keyset 왕복)
- `마지막페이지의_nextCursor는_null이다`
- `정확히_size로_나눠떨어져도_빈_마지막페이지없이_nextCursor가_null이_된다` (lookahead +1 검증)
- `전체페이지_순회결과는_비페이지_조회결과와_동일_집합이다`
- `잘못된_커서는_INVALID_CURSOR를_던진다` (Base64 불량·정수 아님·구분자 없음)
- `size가_0이하거나_상한초과면_INVALID_PAGE_SIZE를_던진다`
- `남서가_북동보다_크면_INVALID_VIEWPORT를_던진다` (기존 유지)
- `면적_상한초과면_VIEWPORT_TOO_LARGE를_던진다` (기존 유지)

### `GridController` (MockMvc)
- `뷰포트_페이지_조회는_200과_grids배열과_nextCursor를_반환한다`
- `마지막페이지_응답의_nextCursor는_null이다`
- `잘못된_커서는_400과_4403을_반환한다`
- `size가_상한을_초과하면_400과_4404를_반환한다`
- `뷰포트_조회_API는_필수_좌표가_없으면_400이다` (기존 유지 — 4401)

### cursor 인코딩 단위 테스트
- `커서는_gridY_gridX를_Base64URL로_왕복인코딩한다`
- `형식이_틀린_커서_디코드는_예외다`

### 기존 테스트 영향도 (반드시 처리)

| 기존 테스트 | 영향 | 조치 |
|---|---|---|
| `GridQueryServiceIntegrationTest#접근_A와_B는_동일한_격자_집합을_반환한다` | `getOccupiedInViewport(.., ViewportStrategy)` 오버로드 사용 | 오버로드 제거 시 **삭제 또는 benchmark 패키지로 이관**(Open Q1과 연동) |
| `GridRepositoryTest`의 `gistB()` 경유 테스트 4건(`GIST공간쿼리_B는_..`, `정수범위스캔_A와_GIST_B는_동일..`, `GIST쿼리는_경도위도_순서로..`, A/B 대조) | `findOccupiedByIntersects`(B) 직접 호출 | B 메서드 유지 시 그대로, 제거 시 **삭제 또는 benchmark로 이관**(Open Q1) |
| `GridRepositoryTest`의 `rangeScanA()` 경유 테스트 | A 경로 — 유지. 단 페이지 메서드로 분리 시 시그니처 조정 | keep/시그니처 정합 |
| `GridViewportExplainBenchmark` | A·B EXPLAIN — 벤치 전용 | 유지(B 이력 보존 근거) |
| `GridControllerTest#뷰포트_조회_API는_필수_좌표가_없으면_400이다` | strategy 미사용, 에러 경로만 검증 | 영향 없음(유지) |

> **B(GIST) 경로를 삭제하느냐 벤치 이력으로 남기느냐가 위 테스트 4~5건의 삭제/이관을 좌우한다 → Open Q1에서 확정.**

---

## 미해결 질문 (Open Questions)

1. **전략 B 경로 처리** — API에서 A로 고정된 뒤, 전략 B(`findOccupiedByIntersects`)·`ViewportStrategy` enum·`getOccupiedInViewport(..strategy)` 오버로드·`grids.center_geom/bbox_geom`+`idx_grids_bbox`를
   (a) **완전 삭제**(coding-principles §2 데드코드 제거, 마이그레이션 별도) 하는지,
   (b) **`benchmark` 패키지/문서로만 이력 보존**하고 실경로에서만 분리하는지.
   → 위 "기존 테스트 영향도" 4~5건의 삭제/이관 범위가 여기에 종속. **권고: (b)** (부하 재검증 여지 남김, 지오메트리 컬럼/인덱스는 유지). Owner A 판단 + reviewer 합의 필요.
2. **`size` 기본값 1000 / 상한 5000의 적정성** — MSG-134 SLO(p95<300ms)·평균 응답 크기 기준으로 부하테스트 후 조정 가능. 우선 제안값으로 착수, MSG-89 캐시 도입 시 재검토.
3. **B의 계약 소비 형태** — B가 페이지 메서드로 이행하는지, 2-arg 전체 리스트를 계속 쓰는지. 2-arg는 non-breaking으로 유지하되, B의 실제 사용처를 리뷰에서 확인해 불필요하면 후속 티켓에서 정리.
