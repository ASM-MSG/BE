# Grid System — 100×100m 격자 (EPSG:5179 미터 평면)

FillMap의 핵심 공간 모델. 용어 정의는 `.claude/rules/glossary.md`가 단일 진실 원천이며,
이 문서는 그 용어를 전제로 격자의 **설계·좌표 규약·저장/조회 구조**를 설명한다.

> 구현 상태: `com.msg.fillmap.grid.*` / `com.msg.fillmap.region.*` (Owner A). 인코딩 수식의
> 최종 진실 원천은 코드의 `GridEncoder` · `GridConstants`다. 이 문서와 코드가 다르면 **코드가 맞다**.

## 격자란

국토 평면 좌표계 EPSG:5179 위에서 100m 간격으로 나눈 셀 (**전국 어디서나 100m × 100m**, 투영 오차
수준 제외 — 상세는 glossary "격자 계산 규칙"). 각 셀은 물리적으로 **항상 존재하는 논리 개념**이며,
누군가 영상을 올리기 전까지는 DB(`grids` 테이블)에 row가 없다 (lazy insert).

- 식별: `(grid_y, grid_x)` 정수 인덱스 쌍으로 유일
- `grid_id` 문자열 포맷: `"{grid_y}_{grid_x}"` (예: 강남역 `"19443_9582"`)
- **grids 테이블에 row 존재 = 전역 격자 등록됨 = "누군가 영상을 올린 격자"**

## 좌표 → 격자 인코딩

위경도(lat, lon)를 EPSG:5179 미터 좌표로 변환한 뒤 100m 셀 인덱스로 내린다
(`gridX = floor(x / 100)`, `gridY = floor(y / 100)`, 경계는 반열림 구간).

- 입력: WGS84 위경도
- 출력: `(grid_y, grid_x)` → `grid_id`
- 성질: **같은 셀 안의 모든 좌표는 같은 `grid_id`로 매핑**된다
  (테스트 예: `강남역_좌표는_같은_격자로_매핑된다`)

정확한 변환 상수(좌표계 정의 문자열·셀 크기)는 `GridEncoder`/`GridConstants`에 정의한다.
이 문서에서 수식을 중복 기재하지 않는다 — 드리프트를 막기 위해 코드를 참조할 것.
서버(Proj4J)·프론트(proj4js)·이행 SQL(ST_Transform)은 `GridConstants.CRS_DEF_EPSG5179` 문자열
하나를 글자 단위로 공유한다. 정합성 검증 자료는 `src/test/resources/fixtures/grid-epsg5179-samples.json`
(전국 200건). 2026-08-08 MSG-347에서 위경도 등간격 근사(0.0009°/0.00115°)를 대체했다.

## 저장 구조

| 테이블 | 역할 | 관련 개념 |
|---|---|---|
| `grids` | 전역 격자 등록된 격자 (세상에 존재하는 셀) | 전역 격자 등록 · lazy insert |
| `user_grids` | 사용자별 개인 점령 격자 = 도감 | 개인 점령 · 도감 · 수집 |
| `videos` | 격자에 올린 영상 1건 = 방문 1회 | 방문 · 재방문 |

- **전역 격자 등록**: 첫 영상 업로드 시 `grids` row 생성 (없으면). 한 번 생기면 사라지지 않음.
- **개인 점령**: 그 사용자의 첫 업로드 시 `user_grids` (user_id, grid_id) row 생성 → 도감에 색칠.
  (DB 저장 키 컬럼명도 `grid_id` — 논리 식별자와 동일, 자체 100×100m 양자화 키. MSG-78 확정)
- **재방문**: 이미 개인 점령한 격자 재업로드 → `user_grids.video_count++`, `last_uploaded_at` 갱신.
- **점령 롤백**: 해당 격자의 사용자 영상이 모두 삭제되어 `video_count == 0`이면 `user_grids` row 삭제
  (개인 점령만 롤백, 전역 격자 등록/`grids` row는 유지). 세부 규칙은 glossary 참조.

## 공간 쿼리 (PostGIS)

- PostGIS + Hibernate Spatial 사용.
- 지도 뷰포트(bbox) 안의 격자 조회, 격자별 집계 등은 공간 인덱스(GIST) 기반.
- 격자 관련 native/공간 쿼리는 Owner A(`grid`, `region`) 소유.

## 행정동 통계 (region)

- `region` 도메인이 행정동 단위 집계를 담당.
- **수집률** = 특정 지역에서 개인 점령한 격자 수 / 전체 격자 수 (`region_stats.progress_rate`)
  (예: "강남구 25% 수집").

## 도메인 경계 (Owner A ↔ B)

격자 데이터는 Owner A가 소유하고, 콘텐츠/인증(Owner B)은 **Repository 직접 접근이 아니라 인터페이스로만** 소비한다.

| 인터페이스 | 제공 | 용도 |
|---|---|---|
| `GridQueryService` | Owner A | 격자 조회/인코딩 결과 제공 |
| `HotZoneService` | Owner A | 핫구역 조회 |
| `UserGridQueryService` | Owner B | 개인 점령/도감 조회 (Owner A가 소비) |

패키지 구조·계약 원칙 상세: `.claude/docs/infrastructure.md`

## 파생 개념 (glossary 참조)

- **핫구역(Hot Zone)**: 최근 업로드/좋아요 급증 격자 (Redis Sorted Set, 1h/24h/1w 윈도우)
- **스폰서 격자(Sponsor Grid)**: 광고 입찰로 최상단 노출되는 격자 (`sponsor_ads`)
- **스트릭(Streak)**: 매일 영상을 업로드한(재방문 포함) 연속 일수 — 신규 점령 불요 (2026-07-29 확정, MSG-200·239)