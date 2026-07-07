# MSG-78: 100×100m 양자화 유틸 + user_grid Entity

**Owner**: A

> 부모 에픽: MSG-48 "5초 영상 기록" · 연결 스토리: MSG-61 "사용자는 첫 방문 격자가 자동 색칠되는 것을 볼 수 있다" (relates to)
> 담당: KangJeong (Owner A — 지도 인프라 도메인 `com.msg.fillmap.grid.*`)
>
> ✅ **초기 Open Questions는 모두 확정됨** — 아래 §확정된 결정 참조. 이 스펙은 구현 착수 가능한 상태다.
> (초기 스키마 컬럼 충돌·엔티티 오너십 충돌은 **해소됨 — 아래 결정 참조.**)

---

## 개요

FillMap의 공간 모델은 지구 표면을 위경도 등간격 **격자(Grid)** 로 나눈다 (한국 위도 기준 ≈100m × 100m,
엄밀한 정사각형이 아닌 등간격 근사 — D1 참조). 이 티켓은 그 격자
시스템의 **가장 낮은 인프라 2개**를 만든다. 후속 색칠/조회 티켓(MSG-73, MSG-90 등)이 이 위에 얹힌다.

1. **양자화 유틸** — WGS84 좌표(lat, lon)를 100m 셀 인덱스 `(grid_y, grid_x)` 로 변환하고,
   논리 식별자이자 저장 키인 `grid_id` 문자열(`"{grid_y}_{grid_x}"`, 예 `"41642_110458"`)을 산출한다.
   같은 셀 안의 모든 좌표는 반드시 같은 `grid_id`로 매핑되어야 한다. 역변환(키 → 중심좌표/bbox)도 제공한다.
2. **user_grid Entity** — `user_grids` 테이블(개인 도감)을 매핑하는 JPA 엔티티.

> **명명 주의**: 티켓 원제목의 "GeoHash7"은 **초기 스키마(V1)에서 표준 geohash를 쓰려던 흔적**이다.
> 확정된 인코딩 방식은 표준 geohash가 아니라 **자체 100×100m 양자화**다. 클래스명은 `GridEncoder` /
> `GridConstants`, 저장 키 컬럼명은 `grid_id`로 확정한다. "geohash" 라는 이름을 코드 심볼·DB 컬럼에 새로 쓰지 않는다.

---

## 확정된 결정 (구현 기준)

| # | 항목 | 확정 내용 |
|---|---|---|
| D1 | 인코딩 방식 | 자체 위경도 등간격 양자화. `GRID_LAT_STEP=0.0009`, `GRID_LNG_STEP=0.00115`. 논리/저장 키 `"{grid_y}_{grid_x}"`. **"100×100m 정사각형"은 근사** — 한국 위도(37.5°N)에서 ≈100×102m, 위도 낮아질수록 가로로 긴 직사각형(적도 ≈128m). MVP(한국)엔 충분, 위도 보정/투영격자는 안 함 |
| D2 | 격자 키 컬럼 | **단일 문자열 키**. `geohash` → **`grid_id VARCHAR(20)`** 로 rename. 정수 2컬럼 분리 안 함 |
| D3 | 인덱스 | `idx_grids_prefix_5` **삭제**. 뷰포트 조회는 기존 `idx_grids_bbox`(GIST)로 충분 → 새 인덱스 추가 안 함 |
| D4 | 마이그레이션 | **`V1__init.sql` 직접 수정** (아직 어떤 DB에도 미적용, JPA 엔티티는 user 도메인뿐이라 rename 안전). V2 신규 파일 만들지 않음 |
| D5 | 역변환 범위 | **본 티켓 포함**. `GridEncoder`가 `grid_id`/`(grid_y,grid_x)` → 중심좌표 + bbox 폴리곤 산출 제공 |
| D6 | user_grid 오너십 | 본 티켓은 **Owner A(KangJeong) 구현**. 엔티티는 `grid` 도메인 소유. infrastructure.md 규정 갱신은 후속작업(§후속 작업) |
| D7 | 문서 갱신 | glossary·grid-system.md의 "저장 키 컬럼명=geohash" 표기를 "grid_id·자체 100×100m 양자화"로 갱신 필요 → 후속작업 |

---

## 1. 산출물 1 — 양자화 유틸 (GridConstants / GridEncoder)

### 위치 (infrastructure.md 패키지 구조 기준)

- `com.msg.fillmap.grid.GridConstants` — 인코딩 상수 (순수 유틸, Owner A)
- `com.msg.fillmap.grid.GridEncoder` — 좌표 ↔ 격자 변환 유틸 (순수 유틸, Owner A)

`infrastructure.md` 패키지 트리에서 `grid/` 바로 아래에 `GridEncoder`, `GridConstants`가 명시돼 있으므로
`entity/`·`service/` 하위가 아니라 `grid` 패키지 루트에 둔다.

### 상수 (GridConstants)

| 상수 | 값 | 출처 |
|---|---|---|
| `GRID_LAT_STEP` | `0.0009` | glossary "격자 계산 규칙" |
| `GRID_LNG_STEP` | `0.00115` | glossary "격자 계산 규칙" |

- FE·BE·모바일이 **공유하는 단일 상수**다. 값을 바꾸면 전 격자 매핑이 바뀌므로 임의 변경 금지.
- **이 유틸의 상수·수식이 단일 진실 원천이다** (grid-system.md 명시: 문서와 코드가 다르면 코드가 맞다).
  이 티켓 이후 grid-system.md에 수식을 중복 기재하지 않는다.

### 정방향 변환 (좌표 → 격자)

```
grid_y = floor(lat / GRID_LAT_STEP)
grid_x = floor(lon / GRID_LNG_STEP)
grid_id = grid_y + "_" + grid_x        // "{grid_y}_{grid_x}"
```

- 입력: WGS84 위경도(double lat, double lon)
- 출력: `(grid_y, grid_x)` 정수 쌍 → `grid_id` 문자열 (`grid_id`가 저장 키이자 논리 식별자)
- **불변식**: 같은 셀 내 모든 좌표 → 동일 `grid_id`. 경계는 `floor` 반열림 구간 `[n·step, (n+1)·step)`.
- 정확한 기준점/투영 처리는 구현(TDD)에서 확정한다. 위 식은 MVP 기준(단순 등간격 격자)이며, 예시
  `"41642_110458"` 이 강남 근방 좌표와 일치하는지 테스트로 검증한다.

### 역방향 변환 (격자 → 중심좌표 / bbox) — **본 티켓 포함 (D5)**

`grids` 테이블은 `center_geom`(POINT), `bbox_geom`(POLYGON)이 **NOT NULL**이다. lazy insert(MSG-73) 시
`grid_id`로부터 이 두 지오메트리를 산출해야 하므로, 산출 책임을 인코딩 수식을 아는 유일한 지점인 이 유틸에 둔다.

```
decode(grid_id) → (grid_y, grid_x)                 // "{grid_y}_{grid_x}" 파싱
center(grid_id) → (centerLat, centerLon)
    centerLat = (grid_y + 0.5) * GRID_LAT_STEP
    centerLon = (grid_x + 0.5) * GRID_LNG_STEP
bbox(grid_id)   → 폴리곤 4점 (닫힌 링, 5점 좌표열)
    남서 (grid_y*STEP, grid_x*STEP) ~ 북동 ((grid_y+1)*STEP, (grid_x+1)*STEP)
```

- 반환 폴리곤은 WGS84(SRID 4326) 좌표열. PostGIS `GEOGRAPHY(POLYGON)` 로 저장 가능한 형태(닫힌 링)로 제공.
- 지오메트리 객체 생성(JTS `Polygon`/`Point` vs 좌표열 DTO)은 구현 판단. 유틸은 좌표 계산까지 책임지고,
  PostGIS 타입 변환은 소비 측(MSG-73)에서 수행 가능하다.

---

## 2. 도메인 로직 (glossary 용어로 서술)

- **격자(Grid)**: 논리적으로 항상 존재하는 100m 셀. `grid_id`로 유일 식별. 이 유틸은 순수 계산이며 DB를 모른다.
- **전역 격자 등록 / lazy insert**: `grids` row 생성은 첫 영상 업로드 시(MSG-73 범위). 본 유틸은 그 트리거가
  키/중심/폴리곤을 계산할 때 쓰인다.
- **개인 점령 / 도감**: `user_grids` row = 사용자별 점령 격자(도감). 본 엔티티가 그 row를 매핑한다.
- **방문/재방문**: `videos` row(방문). 첫 방문 = 점령 = `user_grids` row 생성. 재방문 = `video_count++`,
  `last_uploaded_at` 갱신. **단, 점령/재방문 write 로직 자체는 본 티켓 범위 밖**(엔티티 필드/제약만 제공).
- **저장 키 표기 (rename 후 통일)**: DB 컬럼명·논리 식별자 모두 **`grid_id`** 로 통일한다. 기존 `geohash`
  표기는 폐기(D2). 코드/문서에서 `geohash` 라는 명칭을 새로 쓰지 않는다.

---

## 3. 산출물 2 — user_grid Entity

### 위치

- **`com.msg.fillmap.grid.entity.UserGrid`** (grid 도메인 소유, Owner A — D6 확정).
- infrastructure.md는 개인 도감 엔티티를 `usergrid.*`(Owner B)로 규정하나, 본 티켓 결정으로 grid 도메인이
  소유한다. **문서 규정 갱신은 후속작업**(§후속 작업)으로 처리한다.

### 매핑 (V1 스키마 `user_grids`, rename 반영)

| 필드 | 컬럼 | 타입/제약 | 비고 |
|---|---|---|---|
| `id` | `id` | `BIGSERIAL PRIMARY KEY` | `@Id @GeneratedValue(IDENTITY)` |
| `userId` | `user_id` | `BIGINT NOT NULL` FK→users(id) | 다른 도메인 엔티티 직접 참조 지양, FK id 스칼라 매핑 |
| `gridId` | `grid_id` | `VARCHAR(20) NOT NULL` FK→grids(grid_id) | **격자 키 (rename 후)** |
| `firstCollectedAt` | `first_collected_at` | `TIMESTAMP NOT NULL DEFAULT now()` | 최초 점령 시각 |
| `lastUploadedAt` | `last_uploaded_at` | `TIMESTAMP NOT NULL DEFAULT now()` | 재방문 시 갱신 |
| `videoCount` | `video_count` | `INTEGER NOT NULL DEFAULT 1` | 0이 되면 점령 롤백 대상 |
| `coverVideoId` | `cover_video_id` | `BIGINT` FK→videos(id) ON DELETE SET NULL | 도감 대표 영상. nullable |
| — | `uq_user_grids` | `UNIQUE (user_id, grid_id)` | `@Table(uniqueConstraints=...)` |

### 컨벤션 (project-conventions.md 준수)

- 클래스명 단수 `UserGrid`, 테이블 `user_grids`.
- `@Entity @Table(name = "user_grids", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "grid_id"}))`
- Lombok: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@Setter` 금지(불변성).
  필드 7개 → `@Builder` 사용 허용(4개 이상 조건 충족).
- 도메인 경계상 `User`/`Video` 엔티티를 `@ManyToOne`으로 직접 물지 않고 `userId`/`coverVideoId` 스칼라로 매핑.
- 상태 변경(재방문 count 증가 등)은 본 티켓 범위 밖. 필요 시 setter 대신 의미 있는 도메인 메서드로 후속 티켓 추가.

---

## 4. 데이터 모델 — V1__init.sql 직접 수정 (D2·D3·D4)

**방침**: V1이 아직 어떤 DB에도 적용되지 않았고 현존 JPA 엔티티가 user 도메인뿐이므로, 신규 V2를 만들지 않고
`src/main/resources/db/migration/V1__init.sql` 을 직접 수정한다. (실제 파일 수정은 구현 단계에서 수행.)

### 정확한 변경 목록 (4개 테이블 + 인덱스)

1. **grids** — `geohash VARCHAR(7) PRIMARY KEY` → **`grid_id VARCHAR(20) PRIMARY KEY`**
2. **videos** — `geohash VARCHAR(7) NOT NULL REFERENCES grids(geohash)` →
   **`grid_id VARCHAR(20) NOT NULL REFERENCES grids(grid_id)`**
   - 인덱스 `idx_videos_geohash (geohash)` → `idx_videos_grid_id (grid_id)`
   - 부분 인덱스 `idx_videos_active (geohash, created_at DESC) WHERE ...` → `(grid_id, created_at DESC)` 로 컬럼명 변경
3. **user_grids** — `geohash VARCHAR(7) NOT NULL REFERENCES grids(geohash)` →
   **`grid_id VARCHAR(20) NOT NULL REFERENCES grids(grid_id)`**
   - `CONSTRAINT uq_user_grids UNIQUE (user_id, geohash)` → `UNIQUE (user_id, grid_id)`
4. **sponsor_ads** — `geohash VARCHAR(7) NOT NULL REFERENCES grids(geohash)` →
   **`grid_id VARCHAR(20) NOT NULL REFERENCES grids(grid_id)`**
   - 부분 인덱스 `idx_sponsor_ads_active (geohash, end_date) WHERE ...` → `(grid_id, end_date)` 로 컬럼명 변경
5. **인덱스 삭제** — `idx_grids_prefix_5 ON grids (LEFT(geohash, 5))` **제거**(D3). 뷰포트 조회는 기존
   `idx_grids_bbox`(GIST) 로 처리하므로 대체 인덱스 추가하지 않는다.

> `grids.center_geom` / `bbox_geom` / `idx_grids_bbox` 는 **변경 없음** — 자체 양자화 뷰포트 조회의 기반.

### 영향 범위 (검증 완료)

- **Java 소스에서 `geohash` 참조: 0건** (`src/main/java` 전체 Grep, 대소문자 무시). rename은 **DB 마이그레이션과
  본 티켓 신규 엔티티(`UserGrid`)에만** 영향. videos/sponsor_ads/grids 매핑 엔티티는 아직 미구현이라 rename 충돌 없음.
- 현존 JPA 엔티티는 user 도메인뿐이며 `geohash`를 참조하지 않음 → V1 직접 수정 안전(D4 근거와 일치).

---

## 계약 변경

**없음.**

순수 유틸(`GridEncoder`/`GridConstants`)과 엔티티/스키마만 다룬다. 계약 인터페이스
(`GridQueryService`, `HotZoneService`, `UserGridQueryService`, `UserOidcCommandService`) 시그니처는 불변.
(이 유틸을 소비하는 `GridQueryService` 노출은 MSG-73/MSG-90에서 다룬다.)

---

## 테스트 시나리오 (TDD 대상, JUnit5 + AssertJ · 한국어 백틱 메서드명)

### GridEncoder — 정방향

- `강남역_좌표는_같은_격자로_매핑된다` — grid-system.md 대표 테스트. 강남역 근방 여러 좌표가 하나의 `grid_id`로 수렴.
- `같은_셀_내_서로_다른_좌표는_동일한_grid_id를_반환한다`
- `서로_다른_셀의_좌표는_다른_grid_id를_반환한다`
- `격자_경계에_걸친_좌표는_반열림구간_규칙으로_매핑된다` — `n·step` 지점은 위쪽 셀(floor).
- `grid_id_포맷은_grid_y_언더바_grid_x_형식이다` — `"41642_110458"` 형식/파싱 검증.
- `음수_경도_또는_적도_부근_좌표도_floor_규칙으로_매핑된다` — 부호 경계 안전성.

### GridEncoder — 역방향 (D5, 본 티켓 포함)

- `grid_id를_중심좌표로_역변환하면_해당_셀_안에_위치한다`
- `좌표를_키로_변환한_뒤_중심좌표로_되돌리면_같은_셀로_매핑된다` — 왕복 변환 불변식.
- `grid_id를_bbox_폴리곤으로_변환하면_100m_정사각형_닫힌링을_반환한다`
- `bbox의_남서점과_북동점은_grid_y_grid_x_스텝_경계와_일치한다`

### UserGrid Entity

- `UserGrid는_user_id와_grid_id_조합에_유니크_제약을_가진다` — 중복 저장 시 제약 위반.
- `UserGrid_저장시_video_count_기본값은_1이다`
- `cover_video_id는_null을_허용한다`

---

## 후속 작업 (본 티켓 결정에서 파생 — 구현 단계에서 처리)

- **infrastructure.md 갱신 (D6)**: "개인 도감 엔티티는 `usergrid.*`(Owner B)" 규정을, `user_grids` 엔티티가
  `grid` 도메인(Owner A) 소유임에 맞게 갱신. (패키지 트리·오너십 표 반영.)
- **glossary.md / grid-system.md 갱신 (D7)**: "저장 키 컬럼명은 `geohash`" 표기와 남아 있는 ⚠️ 불일치 주석을
  "저장 키 컬럼 = `grid_id`, 자체 100×100m 양자화"로 갱신. glossary "격자(Grid)" 항목의 컬럼명 표기 정정.

> 위 문서 갱신은 스키마/엔티티 rename과 정합성을 맞추기 위한 것으로, 본 티켓 PR 범위에 포함한다.

---

## 미해결 질문

**없음.** 초기 Open Questions(Q1~Q6)는 모두 확정되어 위 §확정된 결정(D2·D3·D4·D5·D6·D7)으로 반영됨.
