# Implementation Status — 지금 코드에 실제로 있는 것

> **이 문서가 구현 현황의 단일 진실 원천이다.** `infrastructure.md`·`architecture.md`는
> **목표 설계**를 present tense로 서술한다 — 거기 나온 패키지·서비스·인터페이스가 코드에
> 존재한다고 가정하지 말 것. 여기서 ✅로 표시된 것만 실제로 import·호출할 수 있다.
>
> 상태 기준: `develop` 최신 (머지되는 PR이 자기 티켓 줄을 함께 갱신하므로 별도 날짜 관리 없음).

## 도메인 패키지

패키지별 섹션 + **티켓당 한 줄 불릿**. 새 티켓은 해당 패키지 끝에 자기 줄 하나만 append한다
(한 줄에 여러 티켓 조각을 잇지 말 것 — 병렬 PR의 같은 줄 충돌 방지, MSG-169).

### `response` — ✅ 완성
- `ApiResponseDto`, `SuccessResponse`, `ErrorCode`, `ErrorCodeIfs`

### `global` — ✅ 완성
- `ApiException`, `GlobalExceptionHandler`, `config/SecurityConfig`, `config/S3Config`(S3Presigner 빈)·`config/AwsProperties` (MSG-64)

### `auth` (Owner B) — ✅ 완성
- 기본 골격: `controller`(+`/reissue`), `service`(AuthService·OidcLoginService·RefreshTokenService), `dto`(+Reissue*), `jwt`(TokenProvider·필터·JwtProperties·RefreshTokenProvider/Store·RedisInvalidatedTokenStore), `oidc`(Kakao OIDC), `support/RefreshTokenCookies`, `exception/AuthErrorCode`
- MSG-135: 리프레시 토큰(디바이스별 Redis `refresh:{userId}:{deviceId}`, 2주 슬라이딩, 로테이션+재사용감지)·블랙리스트 Redis 이관·하이브리드 전송(웹 쿠키/앱 body)

### `user` (Owner B) — 🟡 부분
- `entity`(User·AuthProvider·UserRole), `repository/UserRepository`, `exception/UserErrorCode`
- **없는 것**: `service`, `controller`, `dto`

### `grid` (Owner A) — 🟡 부분
- MSG-73: `GridEncoder`·`GridConstants`(순수 유틸), `entity/{UserGrid,UserGridId,Grid}`, `repository/GridRepository`, `service/GridQueryService`(+impl, read 계약 A→B), `controller/GridController`, `dto/*`, `exception/GridErrorCode`(4xxx)
- MSG-90: viewport cursor 페이지네이션(`GridCursor` Base64URL 커서, `OccupiedGridPage`, `OccupiedGridPageResponseDto`, keyset 행값비교+lookahead, `?strategy` 파라미터·`ViewportStrategy` 제거 — A 고정, repo B 쿼리는 보존)
- **없는 것**: `GridOccupationService`(write는 MSG-66이 흡수), `HotZoneService`

### `usergrid` (Owner B) — 🟡 부분
- MSG-152: `repository/{UserGridRepository,CollectionSummaryProjection}`(user_grids·videos 네이티브 집계), `service/UserGridQueryService`(+impl, read 계약 B→A)·`CollectionSummaryView`, `controller/CollectionController`(`GET /api/collections/summary`), `dto/CollectionSummaryResponseDto`
- MSG-153: 갤러리 격자 목록(`GET /api/collections/grids` — `first_collected_at DESC` 30 고정·무커서, `GridEncoder.decode`로 grids 미조인, `ThumbnailUrlPresigner` 소비, `CollectionGridProjection`/`CollectionGridView`/`CollectionGridResponseDto`·`getCollectionGrids` B-내부 read)
- **없는 것**: — (155/156 소비용 프리미티브 구상은 불필요해져 폐기 — 155 자기완결·156 별도 서비스로 종결)

### `region` (Owner A) — 🟡 부분
- MSG-154: `entity/Region`(boundary_geom 미매핑), `repository/RegionRepository`(native UPSERT + ST_Area 기반 total_grid_count), `seed/{RegionGeoJsonReader,RegionFeature,RegionSeeder}`(플래그 게이트 `fillmap.region.seed.enabled` 기본 off, 전국 3,558 행정동)
- MSG-93: 역지오코딩(`GET /api/regions/reverse-geocode`, ST_Covers/GIST, `service/RegionQueryService`(+impl)·`controller/RegionController`·`dto/RegionResponseDto`·`exception/RegionErrorCode`(6400))
- MSG-155: region_stats 동기 recompute(`repository/RegionRepository.refreshRegionStats` — 계산-시 격자 중심점 ST_Covers 판정·0-UPSERT 유지, `service/RegionStatsCommandService`(+impl) 명령 계약 A→B)
- MSG-156: 수집률 조회(`GET /api/regions/stats` — collectedOnly 기본 true·무LIMIT(구조적 상한 3,558 — Codex 리뷰로 LIMIT 1000 제거)·LEAST 100 clamp·`6404 REGION_NOT_FOUND`, `service/RegionStatsQueryService`(+impl)·`dto/RegionStatResponseDto`)
- MSG-153: 단건 탐험률(`GET /api/regions/stats/by-point`·`/by-grid` — 격자 중심점 축 `resolveByPoint` 재사용, `findStatByRegion` LEFT JOIN 0% 합성·`LEAST(COALESCE)` clamp, no-match 200+null)
- **없는 것**: 시/도 상위 레벨 집계 (MVP 이후 별도 티켓)

### `video` (Owner B) — 🟡 부분
- MSG-66: `entity`(Video·ProcessingStatus·Visibility·VideoStatus + 상태전이 도메인 메서드), `repository/VideoRepository`(grids·user_grids native UPSERT/롤백), 메타저장 `service`·`controller`(`POST /api/videos`)·`dto`, `support/GeoSupport`, `exception/VideoErrorCode`(3xxx)
- MSG-64: presigned URL 발급(`POST /api/videos/presigned-url`)
- MSG-65: 인코딩 워커(`VideoEncodingService`+`VideoStatusWriter`+`support/FfmpegRunner`+`config/AsyncConfig`, 커밋 후 `@Async` 트리거)
- MSG-72: 삭제+점령 롤백(`DELETE /api/videos/{videoId}`, cover 재선정)
- MSG-132: s3Key 검증(소유권·headObject 실존·UNIQUE 중복)
- MSG-71: 교체(`PUT /api/videos/{videoId}`, 같은 격자만, 도감 불변)
- MSG-133: S3 정리(presign은 `videos/pending/` 발급 → 확정 시 `videos/original/` 복사, 라이프사이클 1일 만료 / 삭제·교체 시 커밋 후 객체 제거)
- MSG-127: 격자별 내 영상 리스트(`GET /api/grids/{gridId}/my-videos`, `GridVideoController`+`GridVideoResponseDto`, 썸네일 presigned GET — 코드베이스 최초 GET presign)
- MSG-87: 격자 전역 대표 영상(`GET /api/grids/{gridId}/cover`, `findGlobalCover` — `idx_videos_grid_popular` 일치, `GridCoverVideoResponseDto`)
- MSG-155: region_stats 갱신 트리거 배선(첫 점령/롤백 시 `RegionStatsCommandService.refresh` 호출, 같은 트랜잭션 참여)
- MSG-145: AI 결과 저장 스키마(V3: `blurred_s3_key`·`highlights` JSONB 최대3·`ai_job_id`, `applyBlurResult` — READY 전이는 MSG-150)
- MSG-149/150: AI 서버 연동(`AiProperties`/`AiConfig`(RestClient.Builder 빈+타임아웃)/`AiClient`/`AiBlurPoller` — `ai.enabled` 기본 off 게이트, 활성 시 ENCODING→BLURRING→READY, 30s reconcile 폴러·타임아웃/가드(시도 넌스·행 잠금)→FAILED 수렴, 블러본 `videos/blurred/`+블러 썸네일 재추출 업로드, 단일 인스턴스 전제)
- MSG-153: 썸네일 GET presign 공용화(`support/ThumbnailUrlPresigner` 추출 — MSG-127 로직 이동, usergrid 갤러리와 공유)
- MSG-162: 공개 범위 전환(`PATCH /api/videos/{videoId}/visibility`, `INVALID_VISIBILITY` 3420, `@DynamicUpdate`로 교차 컬럼 lost-update 차단, 노출 게이트는 read 경계 READY 강제 원칙)
- MSG-206: 영상 재생 조회(`GET /api/videos/{videoId}` — `VideoPlaybackResponseDto`, 재생 소스 blurred ?? encoded presign, 접근 제어 DELETED→BLINDED→visibility→READY first-match, `incrementViewCount` 원자적 +1 타인·발급 시만, 명시 HEAD no-op 핸들러)
- **없는 것**: 전역 영상 목록 API(READY 필터 MUST — MSG-162 스펙 §도메인 3)

## 계약 인터페이스 (Owner A ↔ B 경계면)

`infrastructure.md`가 계약 인터페이스로 명시하지만 **아직 코드에 하나도 없다.** 새로 만들기 전엔
소비하는 쪽에서 import 불가.

| 인터페이스 | 제공자 | 상태 |
|---|---|---|
| `GridQueryService` | Owner A | ✅ built (MSG-73 — 격자 색칠 조회 read · MSG-90 — 4-arg cursor 페이지 시그니처 추가, 2-arg 유지·strategy 오버로드 제거) |
| `HotZoneService` | Owner A | ❌ 미생성 |
| `UserGridQueryService` | Owner B | 🟡 partial (MSG-152 — `getCollectionSummary` 도감 요약 read 계약 신설 B→A · MSG-153 — `getCollectionGrids` B-내부 read 추가, A 미소비·크로스오너 시그니처 불변) |
| `RegionQueryService` | Owner A | ✅ built (MSG-93 — `resolveByPoint(lat, lon)` 역지오코딩 read. stats 조회는 156에서 별도 서비스로 분리 확정) |
| `RegionStatsCommandService` | Owner A | ✅ built (MSG-155 — `refresh(userId, gridId)` 동기 recompute 명령. B의 첫 점령/롤백 훅이 소비, 호출자 트랜잭션 참여) |
| `UserOidcCommandService` | Owner B | ❌ 미생성 |

## 스키마 vs JPA 엔티티

`V1__init.sql`은 14개 테이블을 정의하지만, JPA 엔티티는 2개만 존재한다.

| 테이블 | 엔티티 | 상태 |
|---|---|---|
| `users` | `user/entity/User` | ✅ (단, 스키마의 `grid_color` 컬럼이 엔티티에 아직 없음) |
| `user_grids` | `grid/entity/UserGrid` | ✅ |
| `videos` | `video/entity/Video` | ✅ (MSG-66) |
| `grids` | `grid/entity/Grid` | ✅ (MSG-73 — 조회 전용 최소 매핑: grid_id/grid_y/grid_x, geom 미매핑) |
| `regions` | `region/entity/Region` | ✅ (MSG-154 — region_code/region_name/parent_code/total_grid_count 매핑, boundary_geom 미매핑 — native write 전용) |
| `region_stats` | — | ❌ 엔티티 없음 (native 쿼리로만 접근 — MSG-155/156) |
| `badges` | — | ❌ 엔티티 없음 |
| `user_badges` | — | ❌ 엔티티 없음 |
| `friendships` | — | ❌ 엔티티 없음 |
| `likes` | — | ❌ 엔티티 없음 |
| `push_tokens` | — | ❌ 엔티티 없음 |
| `reports` | — | ❌ 엔티티 없음 |
| `sponsor_ads` | — | ❌ 엔티티 없음 |
| `streaks` | — | ❌ 엔티티 없음 |

## 로드맵 / 백로그

티켓 시퀀싱·의존성·백로그는 **Jira MSG 프로젝트**가 단일 진실 원천이다. 이 문서는 *무엇이 빌드됐는지*만
기록하고 *무엇을 언제 할지*는 다루지 않는다.

## 유지 규칙

패키지나 계약 인터페이스가 planned → partial → built로 바뀌면 해당 행을 즉시 갱신한다.
(spec-driven-dev Phase 5 wrap-up에서 갱신 — 자세히는 해당 스킬 참조.)

- **편집 규칙 (MSG-169)**: 도메인 패키지 섹션은 **티켓당 한 줄 불릿을 append**한다. 기존 줄에
  조각을 이어 붙이지 말 것 — 병렬 PR이 같은 줄을 고치면 병합 충돌이 보장된다. "없는 것" 줄만
  예외적으로 제자리 수정.
