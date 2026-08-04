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
- MSG-311: 공통 응답 실데이터 키 `body` → `data` rename (필드명 자체 변경 — @JsonProperty 미사용, 테스트 17파일 190곳 치환. **breaking — FE 동시 배포 전제**)

### `global` — ✅ 완성
- `ApiException`, `GlobalExceptionHandler`, `config/SecurityConfig`, `config/S3Config`(S3Presigner 빈)·`config/AwsProperties` (MSG-64)
- MSG-167: `GlobalExceptionHandler`에 `MissingServletRequestParameterException → 400 BAD_REQUEST` 전역 매핑 (필수 파라미터 누락이 catch-all에 삼켜져 500이던 결함 정정)
- MSG-244: `config/ProdRedisPasswordValidator`(prod 프로파일 전용 기동 검증 — 바인더가 미해석 `${REDIS_PASSWORD}`를 리터럴로 통과시키는 결함 보완, 공백/미해석 리터럴 완전 일치 시 기동 실패) + prod Redis 포트 6380·헬스체크 호스트 보간 정합(application-prod.yml·docker-compose.server.yml)
- MSG-260: `config/ProdRequiredEnvValidator`(prod 필수 env 8종 일괄 기동 검증 — 공백/미해석 리터럴 완전 일치 시 누락 변수명 전부 나열하며 기동 실패, `ProdRedisPasswordValidator`는 흡수·삭제)

### `auth` (Owner B) — ✅ 완성
- 기본 골격: `controller`(+`/reissue`), `service`(AuthService·OidcLoginService·RefreshTokenService), `dto`(+Reissue*), `jwt`(TokenProvider·필터·JwtProperties·RefreshTokenProvider/Store·RedisInvalidatedTokenStore), `oidc`(Kakao OIDC), `support/RefreshTokenCookies`, `exception/AuthErrorCode`
- MSG-135: 리프레시 토큰(디바이스별 Redis `refresh:{userId}:{deviceId}`, 2주 슬라이딩, 로테이션+재사용감지)·블랙리스트 Redis 이관·하이브리드 전송(웹 쿠키/앱 body)
- MSG-178: logout에 `LogoutRequestDto`(fcmToken 선택, `@RequestBody(required = false)` — body 없는 기존 호출 하위 호환) — 세션 삭제와 같은 처리에서 `PushTokenService.unregister(userId, fcmToken)` 호출(auth → notification 단방향 주입, 공유 기기 알림 잔존 P1 차단)

### `user` (Owner B) — 🟡 부분
- `entity`(User·AuthProvider·UserRole), `repository/UserRepository`, `exception/UserErrorCode`
- MSG-205: 계정 삭제(`DELETE /api/users/me` — `controller/UserController`·`service/UserService`(+Impl) 첫 신설, V15 reports FK ON DELETE(CASCADE/SET NULL), 삭제 트랜잭션 = S3 키 수집(`findAllS3KeysByUserId`)→`deleteUser` 0행 1404, afterCommit = S3 1000키 청크 best-effort·refresh `deleteAll`·액세스 토큰 블랙리스트 각각 독립 try. CASCADE 보정 로직 없음, D5 경합 수용 — 스펙 §D1~D5)
- MSG-203: 프로필 조회/닉네임 수정 — `GET /api/users/me`·`PUT /api/users/me/nickname`, `dto/{UserProfileResponseDto,NicknameUpdateRequestDto}` (email 은 카카오 유저 null — MSG-310, 색상 수정은 기획 제외)
- MSG-185: `User.friendCode`(V18 — 생성자 자동 부여, 혼동 문자 제외 32종 8자)·`UserRepository.findByFriendCode` — friend 도메인이 소비

### `grid` (Owner A) — 🟡 부분
- MSG-73: `GridEncoder`·`GridConstants`(순수 유틸), `entity/{UserGrid,UserGridId,Grid}`, `repository/GridRepository`, `service/GridQueryService`(+impl, read 계약 A→B), `controller/GridController`, `dto/*`, `exception/GridErrorCode`(4xxx)
- MSG-90: viewport cursor 페이지네이션(`GridCursor` Base64URL 커서, `OccupiedGridPage`, `OccupiedGridPageResponseDto`, keyset 행값비교+lookahead, `?strategy` 파라미터·`ViewportStrategy` 제거 — A 고정, repo B 쿼리는 보존)
- MSG-167: 격자 중심점 행정동 라벨 저장 — V5 `grids.region_code`(nullable FK→regions, 쓰기 시 1회 판정·조회는 equi) + 멱등 백필(`region_code IS NULL`만, regions 미시딩 no-op). 판정 규칙 = 93/155 중심점 축(`ST_Covers … ORDER BY region_code LIMIT 1`). 인덱스 미추가·Grid 엔티티 미매핑(native)
- MSG-238: V7 `idx_grids_region_code`(단순 btree — 167 §D5 예약 발동, region_code 주도 조회 최초 등장의 물리 기반. partial 기각)
- **없는 것**: `GridOccupationService`(write는 MSG-66이 흡수), `HotZoneService`(MSG-233 §D5로 `hotzone` 독립 패키지 배치 확정 — grid 아님)

### `usergrid` (Owner B) — 🟡 부분
- MSG-152: `repository/{UserGridRepository,CollectionSummaryProjection}`(user_grids·videos 네이티브 집계), `service/UserGridQueryService`(+impl, read 계약 B→A)·`CollectionSummaryView`, `controller/CollectionController`(`GET /api/collections/summary`), `dto/CollectionSummaryResponseDto`
- MSG-246: 도감 요약 `visitedRegionCount` 정정(`getCollectionSummary` 서브쿼리 — dead `videos.region_code` 대신 `JOIN grids` 후 `COUNT(DISTINCT g.region_code)`, MSG-167 by-grid 귀속 정합. 테스트 시딩도 프로덕션 형상(videos.region_code NULL)으로 재작성)
- MSG-153: 갤러리 격자 목록(`GET /api/collections/grids` — `first_collected_at DESC` 30 고정·무커서, `GridEncoder.decode`로 grids 미조인, `ThumbnailUrlPresigner` 소비, `CollectionGridProjection`/`CollectionGridView`/`CollectionGridResponseDto`·`getCollectionGrids` B-내부 read)
- MSG-167: 갤러리 목록에 `regionName` 추가(`grids`·`regions` LEFT JOIN equi, geospatial 0 — 153 "grids 미조인"을 라벨 위해 뒤집음, 정렬·30상한 등 나머지 계약 불변), `CollectionGridProjection`/`View`/`ResponseDto`에 regionName 1필드. 동 단위 내 영상 조회(`GET /api/collections/videos?regionCode=` — `videos⨝grids` 격자 축 귀속·ACTIVE만·`created_at DESC, id DESC`·no-LIMIT·빈 배열 200, `RegionVideoProjection`/`View`/`RegionVideoResponseDto`(gridId 포함)·`getRegionVideos` B-내부 read)
- MSG-186: 친구용 격자 목록 `getCollectionGridsForFriend(ownerUserId)` — `UserGridQueryService`에 **메서드 1개 비파괴 추가**(기존 3종 무수정, friend 도메인만 소비하는 B-내부 read — Owner A 미소비 non-breaking). `getCollectionGrids` 재사용 금지가 이유: 본인용은 cover를 visibility 무필터로 읽어 PRIVATE 썸네일이 친구에게 샌다. 정렬·상한은 본인 갤러리와 동일(`first_collected_at DESC, grid_id DESC` 30)이고 **썸네일 선정 한 곳만 갈린다** — `LEFT JOIN LATERAL`(격자당 1행)로 `status='ACTIVE' AND visibility='PUBLIC' AND processing_status='READY' AND thumbnail_url IS NOT NULL` 4조건 중 cover 우선(`ORDER BY (v.id = ug.cover_video_id) DESC, created_at DESC, id DESC LIMIT 1`) → 없으면 NULL(격자 사실만). visibility 필터가 MSG-285 FRIENDS 확장 지점(쿼리 주석 명시 — `IN ('PUBLIC','FRIENDS')` 한 곳 수정). `FriendCollectionGridProjection`/`FriendCollectionGridView` 신규(cover 2필드 대신 `thumbnailKey` 1개 — 영상 ID 미노출), 매핑은 기존 `GridEncoder.decode`+`ThumbnailUrlPresigner.presign` 재사용. 요약은 `getCollectionSummary(userId)` 시그니처 그대로 재사용(수정 0줄)
- **없는 것**: — (155/156 소비용 프리미티브 구상은 불필요해져 폐기 — 155 자기완결·156 별도 서비스로 종결)

### `mission` (Owner B) — 🟡 부분
- MSG-166: V6 스키마 검증 테스트(`MissionSchemaMigrationTest` — 엔티티 없던 시점)
- MSG-222: 활성 미션 조회(`GET /api/missions/active` — `entity/{Mission,MissionType,MissionGrid,MissionGridId}`(조회 전용), `MissionRepository.findActive`(기간 경계 독립 판정)·`MissionGridRepository`, `MissionQueryService`(+impl — 유형→shape 단일 분기: COURSE→PATH(path 원문+spots)/EVENT→BOX(bbox 합성)/THEME·CONTINUOUS→CELLS/AREA→REGION(코드만), 1h 전역 캐시 단일 volatile CacheEntry+더블체크 락, 단일 인스턴스 전제), `dto/MissionResponseDto`+`sealed MissionShape` 4종. 기본 클럭 `Clock.systemUTC()` — KST JVM 9h 스큐 정정, MSG-223 리뷰 파생)
- MSG-223: 미션 완료 판정·스탬프(`entity/{UserMission,UserMissionId}`(UserBadge 미러·비회수)·`UserMissionRepository`(`insertIgnoreConflict` ON CONFLICT·`countMyStamps`), `MissionRepository.findAwardCandidateIds/findCompleted`(native, `recorded_at` 판정·무기간 IS NULL 생략·`AT TIME ZONE 'UTC'` 정규화), `MissionAwardService`(+impl — 신규 INSERT 성공분만 응답·MISSION_COUNT 뱃지 배선), 업로드 확정 훅(streak 다음·점령 분기 바깥, `VideoUploadResponseDto.completedMissions`), V12 뱃지 시딩 1·5·10)
- MSG-224: 축제 미션 적재(`seed/{FestivalRecord,FestivalJsonlReader,FestivalMissionSeeder}` — 플래그 게이트 `fillmap.mission.festival.seed.enabled` 기본 off·`@Order(30)`, 시드+격주 수동 갱신 단일 러너·`@Transactional` 원자성, 9×9 격자 81행·target_count=1, dedupe=중심격자+기간(min+4 복원), 종료 정리 `deleteEndedFestivalsWithoutStamps`(native, `AT TIME ZONE 'UTC'`·스탬프 잔존). **V13 `missions.source`**(VARCHAR(30) NULL) — EVENT 타입이 팝업(MSG-235)과 공유라 `source='FESTIVAL'`로 소유 식별(Codex 리뷰 파생, 타 소스·NULL 불가침). `Mission` 시드 `@Builder` 6필드·`created_at` insertable=false 전환)
- MSG-225: 코스 미션 시드(`seed/{CourseRecord,CourseSeedReader,CourseMissionSeeder}` — 플래그 게이트 `fillmap.mission.course.seed.enabled` 기본 off·`@Order(40)`, 무기간 INSERT-only(정리 단계·클럭 없음), dedupe=제목×`source='DURUNUBI'`, path=GeoJSON LineString 원문 jsonb. reader는 전량 거부 검증 계약 — LineString·좌표 쌍·스팟 5~8·seq 연속·gridId 포맷/정규형/중복·crsIdx/name 문자열(Codex 3라운드 파생 4건 포함). `Mission` 빌더 path 확장·`MissionGrid` seq 생성자. 리포지토리·조회·판정 경로 무수정 — 무기간 판정은 MSG-223 엔진 `IS NULL OR`로 자연 성립(계약 라운드트립 테스트 실증). 산출 파이프라인은 레포 밖 `~/fillmap-data/durunubi/spot_pipeline.py`)
- MSG-235: 팝업 미션 적재(`seed/{PopupRecord,PopupJsonlReader,PopupMissionSeeder}` — 플래그 게이트 `fillmap.mission.popup.seed.enabled` 기본 off·`@Order(50)`, 주 1회 수동 갱신 단일 러너·**정리→적재 순**(id 단독 키라 연장 팝업 공백을 같은 실행에서 흡수 — 축제와 반대), 멱등=**V14 `missions.source_key`**(팝가 id, `(source,source_key)` 부분 유니크 백스톱)·INSERT-only, 9×9·target_count=1·**type=POPUP 신설**(V14 CHECK 확장, 조회 `case EVENT, POPUP → BOX`, 판정 무수정). reader는 코스 전량 거부 계약 승계(id 정수·중복, 좌표 33~39/124~132, 날짜 순서 — periodType 미사용·날짜 직접 판정). 정리 쿼리 `deleteEndedBySourceWithoutStamps(:source)` 파라미터화(축제 메서드 흡수). 산출은 레포 밖 `~/fillmap-data/popups/crawl_popga.py`)
- **없는 것**: — (미션 3종 시더 완결). 축제·코스·팝업 실적재는 운영 절차(각 스펙 §D6/§D9/§D8 — 산출물 복사 + 플래그 on 1회 기동, 코스는 TourAPI 전량 수집 완료 후)

### `region` (Owner A) — 🟡 부분
- MSG-154: `entity/Region`(boundary_geom 미매핑), `repository/RegionRepository`(native UPSERT + ST_Area 기반 total_grid_count), `seed/{RegionGeoJsonReader,RegionFeature,RegionSeeder}`(플래그 게이트 `fillmap.region.seed.enabled` 기본 off, 전국 3,558 행정동)
- MSG-93: 역지오코딩(`GET /api/regions/reverse-geocode`, ST_Covers/GIST, `service/RegionQueryService`(+impl)·`controller/RegionController`·`dto/RegionResponseDto`·`exception/RegionErrorCode`(6400))
- MSG-155: region_stats 동기 recompute(`repository/RegionRepository.refreshRegionStats` — 계산-시 격자 중심점 ST_Covers 판정·0-UPSERT 유지, `service/RegionStatsCommandService`(+impl) 명령 계약 A→B)
- MSG-156: 수집률 조회(`GET /api/regions/stats` — collectedOnly 기본 true·무LIMIT(구조적 상한 3,558 — Codex 리뷰로 LIMIT 1000 제거)·LEAST 100 clamp·`6404 REGION_NOT_FOUND`, `service/RegionStatsQueryService`(+impl)·`dto/RegionStatResponseDto`)
- MSG-153: 단건 탐험률(`GET /api/regions/stats/by-point`·`/by-grid` — 격자 중심점 축 `resolveByPoint` 재사용, `findStatByRegion` LEFT JOIN 0% 합성·`LEAST(COALESCE)` clamp, no-match 200+null)
- MSG-167: 시딩 직후 `grids.region_code` 멱등 보정 백필(`RegionRepository.backfillGridRegionCodes`, `RegionSeeder.run`에서 호출 — regions 후착 환경의 영구 NULL 라벨 방지, `EXISTS` 가드로 무귀속 격자 NULL→NULL 재기록 차단, Codex 리뷰 P1·2차)
- **없는 것**: 시/도 상위 레벨 집계 (MVP 이후 별도 티켓)

### `zone` (Owner A) — 🟡 부분
- MSG-234: 격자 표시명 구역 — `entity/Zone`(V8 `zones` 전 컬럼 매핑)·`repository/ZoneRepository`, `GET /api/zones` 전체 목록(`service/ZoneQueryService`(+impl)·`controller/ZoneController`·`dto/ZoneResponseDto`), `seed/{ZoneSeed,ZoneSeeder}`(플래그 게이트 `fillmap.zone.seed.enabled` 기본 off, `resources/seed/zones.json` `zone_key` UPSERT 멱등). 표시명("서면 A-14") 계산은 FE-local(§D3) — 서버는 데이터만. 장소 검색은 카카오 프록시 MSG-251 이관(2단 폴백 구현분 제거, §D6)
- MSG-259: zones 실데이터 48건 주입(`seed/zones.json` — 공공 상권 17 + 수동 작도 31, 전국·검증기 PASS)·prod/dev 시더 상시 on·명명 계약 언어 중립 픽스처(`src/test/resources/fixtures/zone-naming.json`, `ZoneNamingContractTest` 픽스처 로드 리팩터)·glossary 구역/표시명 등재
- **없는 것**: 장소 검색(MSG-251 카카오 프록시 — 구현 완료로 이관)

### `video` (Owner B) — 🟡 부분
- MSG-66: `entity`(Video·ProcessingStatus·Visibility·VideoStatus + 상태전이 도메인 메서드), `repository/VideoRepository`(grids·user_grids native UPSERT/롤백), 메타저장 `service`·`controller`(`POST /api/videos`)·`dto`, `support/GeoSupport`, `exception/VideoErrorCode`(3xxx)
- MSG-64: presigned URL 발급(`POST /api/videos/presigned-url`)
- MSG-65: 인코딩 워커(`VideoEncodingService`+`VideoStatusWriter`+`support/FfmpegRunner`+`config/AsyncConfig`, 커밋 후 `@Async` 트리거)
- MSG-72: 삭제+점령 롤백(`DELETE /api/videos/{videoId}`, cover 재선정)
- MSG-243: 삭제 동시성 정합(`deleteVideo` 도입부 `findWithLockById` 행 잠금 — 동시 삭제 이중 감소·점령 오롤백 차단, 패자 멱등 200 유지, `VideoDeleteConcurrencyTest` pg_blocking_pids 결정적 재현)
- MSG-241: 인코딩 stale completion 차단(`encode(videoId, originalKey)` 시그니처 + 인코딩 라이터 4종 `findWithLockById`·`isCurrentEncodingAttempt`(ACTIVE·originalS3Key 일치) 가드 — 교체 후 옛 태스크의 READY/BLURRING/FAILED 오염·ai_job_id 잔존 차단. 폴러 라이터 3종 무변경, 마이그레이션 불요)
- MSG-247: 확정 롤백 S3 보상(`copyToOriginal` 복사 직후 `deleteOnRollback` — STATUS_ROLLED_BACK만·비활성 no-op·`deleteQuietly` 재사용) + 시도별 유니크 original 키(`{pendingStem}-{attemptUuid}`, `pg_advisory_xact_lock` 확정 직렬화·prefix 중복 검사 — 동시 확정 레이스 근절, 패자 4xx 수렴. prefix 인덱스는 MSG-262)
- MSG-262: 확정 경로 prefix 조회 인덱스(V11 `idx_videos_original_s3_key_pattern` — `original_s3_key varchar_pattern_ops`, 비-C 콜레이션 풀 스캔 방지. EXPLAIN 실증: 인덱스 有 Index Only Scan / 無 Seq Scan)
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
- MSG-204: 업로드 시 공개범위 지정(`VideoUploadRequestDto.visibility` 선택 필드 — null=**PUBLIC 기본**(§M3 PRIVATE 안전값 대체), `parseVisibility` 재사용·confirmUpload 전 파싱, `Video.create` Visibility 파라미터화(하드코딩 제거), 마이그레이션·계약 인터페이스 무변경. FRIENDS는 MSG-285에서 추가)
- MSG-167: `upsertGrid`(lazy insert)에 `region_code` 중심점 판정 인라인 — 격자 생애 1회(`SELECT … WHERE NOT EXISTS`, 무귀속 NULL). 판정 규칙은 Owner A 자산(93/155 동일), B 레포 호스팅(신설 공유 컬럼 `grids.region_code`)
- MSG-206: 영상 재생 조회(`GET /api/videos/{videoId}` — `VideoPlaybackResponseDto`, 재생 소스 blurred ?? encoded presign, 접근 제어 DELETED→BLINDED→visibility→READY first-match, `incrementViewCount` 원자적 +1 타인·발급 시만, 명시 HEAD no-op 핸들러)
- MSG-242: 교체 시 `recordedAt` 엔티티 반영(`Video.replaceFile` 3-arg — MSG-71의 반영 누락 정정, 미션 기간 판정(MSG-223) 선행)
- MSG-237: 격자 전역 영상 목록(`GET /api/grids/{gridId}/videos` — `idx_videos_grid_popular` 일치 ACTIVE·PUBLIC·READY 필터, 조회수 인기순 keyset opaque 커서(gridId 바인딩·UTC epoch micros), `GridGlobalVideoResponseDto`/`GridVideoPageResponseDto`, `INVALID_CURSOR` 3423)
- MSG-238: 전역 탐색 API 2종(`GET /api/regions/{regionCode}/grids` 카드+헤더 카운트·`GET /api/regions/explore` — `RegionExploreController`/`Service`, 게이트=ACTIVE·PUBLIC·READY 단일 정의, 커버 87 규칙 3키 정합(`findGlobalCover` id DESC 추가), DTO 3종·프로젝션 3종, sort 대문자 enum·limit null=전부, 신규 에러코드 0)
- MSG-239: 업로드 뱃지 훅(`saveVideo` 2지점 — 항상 UPLOAD_COUNT(생애 카운트·status 무관), 첫 점령 시 TOTAL_GRIDS+refresh 직후 REGION_PERCENT 물질화 값 소비, 같은 트랜잭션)·`VideoUploadResponseDto.newBadges` 동봉(FR-9). 삭제·교체 경로 무변경(비회수 FR-5)
- MSG-200: 업로드 스트릭 훅(`saveVideo` — `!alreadyOccupied` 분기 바깥 1줄, `StreakCommandService.recordUpload` 획득분 `newBadges` 합류. 삭제·교체 무변경 — 소급 차감 없음 §D4)
- MSG-183: 업로드 핫스코어 훅(`saveVideo` 훅 체인 뒤 `afterCommit` 1줄 — `HotScoreCommandService.recordUpload(gridId)` 소비, 유령 증분 차단·응답 계약 무변. 교체·삭제 무배선 — 차감 없음 §D4·D6)
- MSG-283: AI 폴링 404 미제출 복귀(`AiBlurPoller` 결합 분기 분리 — 404=`clearAiJob`(aiJobId null·startedAt 유지) 후 기존 미제출 경로가 다음 주기 재제출, 명시 FAILED만 즉시 실패. `VideoStatusWriter.clearAiJob` 신규(행 잠금+`isCurrentBlurJob` 가드), 배포發 잡 유실이 PT30M 내 READY 수렴. `AiClient`·마이그레이션 무변경)
- MSG-286: 프리체크 탈락 즉시 실패(`AiJobResult`에 `Precheck(passed, reason)` 4번째 컴포넌트 — missing/null/malformed→null 판정 안 함, 폴러 탈락 독립 if FAILED 뒤·DONE 앞이라 `downloadBlurred` 409 미진입, PT30M→다음 주기. **V17 `videos.fail_reason`**(VARCHAR(64) NULL — NULL=시스템 오류, 콜론 앞 코드만·64자 절단·null reason은 `precheck_failed` 폴백), `markBlurFailed` 사유 파라미터 확장. FE 노출 API 비범위)
- MSG-278: recordedAt 미래 시각 거부(`validateRecordedAt` 공유 검증 — 업로드·교체 양 경로 confirmUpload 전, `Clock.systemUTC()` 기준 now+5분 초과만 `RECORDED_AT_IN_FUTURE` 3424, `isAfter`라 정각 통과. 갤러리 과거 시각 인정 유지(PRD §8 확정), 판정 쿼리·마이그레이션 무수정. 테스트 픽스처 recordedAt UTC 정규화 15파일 — zone-less now()는 9h 스큐 오거부)
- MSG-285: 공개범위 FRIENDS(친구만 보기) 확장 — `entity/Visibility` 3값째 `FRIENDS`(V20 CHECK 확장, 컬럼·인덱스 무변경), `getVideoPlayback` 재생 판정을 **enum switch 식**으로 재구성(PUBLIC만 명시 통과 — 기존 "PRIVATE만 차단" 부정형이면 새 값이 조용히 전원 공개된다. 식이라 4값째 추가 시 컴파일 에러로 재검토 강제, 문(statement)은 javac 이 exhaustiveness 를 안 잡아 무효), 비친구는 **기존 `VIDEO_FORBIDDEN` 3403 + "비공개 영상입니다"** 단일 throw 재사용(PRIVATE 실패와 응답 동일 — 신규 에러코드 0), 친구 판정은 `FriendService.isFriend` B-내부 소비(FRIENDS && 비소유자 경로에서만 1회 — PUBLIC·PRIVATE·소유자 쿼리 0회). 지정 경로는 `parseVisibility` 무수정(enum 상수 추가로 업로드·전환 자동 허용), `INVALID_VISIBILITY` 3420 메시지·Swagger 6곳 3값화. 전역 노출 쿼리(`visibility = 'PUBLIC'` 등식 9곳) 무변경 — FRIENDS 는 대표 영상·전역 목록·탐색 집계에서 자동 제외(목록 노출은 MSG-187 이월)
- **없는 것**: —

### `search` (Owner A) — ✅ 완성 (MVP 범위)
- MSG-251: 장소 검색 카카오 프록시(`GET /api/search/places?q=` — keyword.json 실시간 패스스루(약관: 캐시·저장 금지), `PlaceSearchController`/`Service`(+impl)/`KakaoLocalClient`/`SearchConfig`(완성 RestClient 빈, connect 1s/read 3s)/`KakaoLocalProperties`, gridId=`GridEncoder.encode` 즉석 합성, `SearchErrorCode` 5xxx 신설 `SEARCH_UPSTREAM_ERROR(5502)` 단일 수렴, 키=`${oauth.kakao.client-id:}` 재사용)

### `badge` (Owner B) — 🟡 부분
- MSG-239: 뱃지 시스템 MVP — V9(`chk_badges_condition`에 MISSION_COUNT 확장·`user_badges.notified_at/featured_rank`+partial UNIQUE·활성 3축 11종 시딩·set-based 소급), `entity/{Badge(conditionValue 미매핑),BadgeConditionType,UserBadge,UserBadgeId}`, `repository/{BadgeRepository.findEligible,UserBadgeRepository(지급 ON CONFLICT·metric 3종·featured lock/clear/set)}`, `service/BadgeAwardService`(+impl, 후보 SELECT+INSERT 2단 — B 내부)·`BadgeFeaturedService`(+impl), `PUT /api/badges/featured`(`BadgeController`·집합 교체 멱등), `dto/{EarnedBadge,FeaturedBadgeRequest,FeaturedBadgeResponse}ResponseDto`, `exception/BadgeErrorCode`(7xxx — 7400·7403)
- MSG-200: V10 꾸준함 뱃지 시딩(STREAK_3/7/30 — DDL 0·소급 블록 없음, §D6 예외 주석. 판정 훅은 streak 도메인이 `award(STREAK_DAYS)` 호출)
- MSG-201: 내 뱃지 조회 — `GET /api/badges`(획득+미획득 전체, badges.id ASC·featuredRank 동봉), `repository/{BadgeRepository.findAllWithMyStatus,MyBadgeProjection,UserBadgeRepository.markMyBadgesNotified}`, `service/BadgeQueryService`(+impl — 조회 시 노출된 미확인분만 IN 리스트 자동 스탬프·isNew 는 SELECT 시점 값), `dto/MyBadgeResponseDto`(마이그레이션·신규 에러 코드 0)
- **없는 것**: MISSION_COUNT 훅·시딩(미션 엔진 티켓), SPECIAL 시딩(오픈 준비 티켓)

### `streak` (Owner B) — ✅ 완성 (MVP 범위)
- MSG-200: 스트릭 집계 — `entity/Streak`(전 컬럼 매핑·Setter 없음, 쓰기는 native 전용), `repository/StreakRepository`(`upsertOnUpload` — 3분기 CASE 한 문장 UPSERT·KST 자정 경계·ON CONFLICT 행 잠금 직렬화 + `findCurrentCount`), `service/StreakCommandService`(+impl — 갱신 직후 `BadgeAwardService.award(STREAK_DAYS)` 배선·획득분 반환, B 내부). 조회 API 없음(currentStreak·maxStreak 노출은 도감 summary 티켓 소관 §D8), freeze 미도입·소급 차감 없음 확정

### `friend` (Owner B) — ✅ 완성 (MVP 범위)
- MSG-185: 친구 코드·관계 수명주기 — V18(users.friend_code 추가·기존 행 백필·UNIQUE)·V19(대칭 쌍 유니크 — findPair Optional 계약 보호), `entity/{Friendship,FriendshipId,FriendshipStatus}`(복합 PK @EmbeddedId — UserGrid 미러. status 는 PENDING·ACCEPTED 만 영속 — 거절·삭제는 행 DELETE, §D3 "행 존재 = 활성 관계"), `repository/FriendshipRepository`(`findPair` 양방향·`findReceivedRequests` 생성자 프로젝션 — User FK 매핑 없이 Long 조인), `service/FriendService`(+Impl — 요청 시 역방향 PENDING 자동 수락 = 기존 행 ACCEPTED 승격), `controller/FriendController` 7종(`/api/friends` — code·preview·requests·requests/received·accept·reject·`DELETE /{userId}`), `exception/FriendErrorCode`(9xxx — 9400·9404·9409·9410·9414·9424)
- MSG-186: 친구 목록·친구 프로필 조회 — `FriendshipRepository` 3종 추가(`findFriendsOrderByAcceptedAt`·`findFriendsOrderByNickname` — 양방향 OR 2분기 세타 조인 생성자 프로젝션 1방, SQL UNION 아님. 정렬만 다른 정적 2본(`responded_at DESC NULLS LAST, u.id` / `u.nickname, u.id` — 닉네임 중복 허용 타이브레이크). `existsAcceptedPair` 무잠금 존재 확인 — `findPair`는 PESSIMISTIC_WRITE라 readOnly 트랜잭션에서 PG가 거부해 조회 전용 분리. MSG-285(FRIENDS 공개범위)도 같은 메서드를 소비 — 선언은 이 한 곳(병렬 합의분 중복은 285 리베이스에서 제거·javadoc 병합, 2026-08-04)), `FriendService.getFriends(userId, sort)`(sort null·recent=수락 시각↓ 기본·nickname, 그 외 9420)·`getFriendProfile(userId, targetUserId)`(`existsAcceptedPair` → 프로필 → `UserGridQueryService` 요약·격자, friend→usergrid 단방향 B-내부 read), `FriendController` +2(`GET /api/friends?sort=`·`GET /{userId}/profile` — 기존 7종과 경로 무충돌), `dto/{FriendListItemResponseDto,FriendProfileResponseDto,FriendCollectionGridResponseDto}`(프로필은 단일 응답 — 프로필+요약+최근 격자 30, `summary`는 `CollectionSummaryResponseDto` 중첩 재사용으로 본인·친구 수치 동일 보장. 격자 항목은 본인 갤러리에서 영상 ID 2필드를 뺀 형상 — 비공개 영상 존재 누설 차단), `FriendErrorCode` +1(9420 INVALID_FRIEND_SORT). 프로필 실패는 **전건 기존 9424 단일 응답**(비친구·본인 ID·PENDING 상대·미존재 userId — 관계·계정 존재 은닉), 판정은 요청 시점 실시간(캐시 없음). 마이그레이션 0(조회 전용)
- MSG-285: 친구 여부 read 판정 추가 — `FriendService.isFriend`(+Impl `@Transactional(readOnly = true)` — 요청 시점 실시간 판정이라 친구 삭제가 다음 요청부터 즉시 반영, 캐시·비정규화 없음). 내부는 MSG-186과 공유하는 무잠금 `existsAcceptedPair` 소비(`findPair`는 `PESSIMISTIC_WRITE` 쓰기용이라 재생 판정마다 행 `FOR UPDATE` 가 걸려 재사용 금지). video 도메인이 FRIENDS 재생 판정에서 소비(B-내부, 계약 인터페이스 아님)
- MSG-187: 친구 도감 레이어 — `FriendController` +2(`GET /{userId}/grids` 뷰포트 커서 · `GET /{userId}/grids/{gridId}/videos` 무페이징 최신순), `FriendService.getFriendGrids`·`getFriendGridVideos`(+Impl — `requireFriend` 가드로 9424 은닉 판정 단일화, getFriendProfile 도 수렴). 뷰포트는 `GridQueryService` 4-인자 **무변경 재사용**(friend 가 신규 크로스 오너 소비자 — userId 에 친구 ID 주입, 검증·에러 4401~4404·`OccupiedGridPageResponseDto` 전부 grid 계약 그대로, grid 패키지 diff 0). 영상 목록 `VideoRepository.findFriendGridVideos`(ACTIVE·READY·visibility IN(PUBLIC,FRIENDS)·`created_at DESC, id DESC`) + `VideoService.getFriendGridVideos`(presign+매핑, 친구 판정은 호출자 책임 명시) + `dto/FriendGridVideoResponseDto`(4필드). D6: `getCollectionGridsForFriend` LATERAL 썸네일 게이트 PUBLIC→IN('PUBLIC','FRIENDS') — MSG-186 예약 TODO 이행(FRIENDS 재생·썸네일 정합). **빈 순환 주의**: MSG-285(video→friend)와 이번(friend→video)으로 상호 의존 — `FriendServiceImpl`이 `ObjectProvider<VideoService>` 지연 조회로 생성 순환만 회피(구조 해소 leaf 빈 분리 = MSG-312 백로그). 마이그레이션·인덱스·전역 PUBLIC 쿼리·신규 에러코드 전부 0
- **없는 것**: 도감 공개 범위 설정(Phase 2+)·차단(후속)·코드 재발급(Phase 2+ — MSG-188 종결로 유예 확정)·친구 목록 페이지네이션(수십 명 규모 전제)·video↔friend 상호 의존 구조 해소(MSG-312)

### `hotzone` (Owner A) — ✅ 완성 (MVP 범위)
- MSG-183: 핫스코어 집계 — `service/HotScoreCommandService`(+impl, 평면 service 패키지). 업로드 신호 +1을 UTC 6h 버킷(`hotzone:{bucketId}` Sorted Set, `bucketId=epochSeconds/21600`)에 Lua 원자 스크립트(ZINCRBY+EXPIRE 54h)로 증분. 버킷 키는 호출(커밋) 스레드에서 확정, 실행은 자체 데몬 1스레드 executor(큐 10k, 종료 시 5s 드레인) — 요청 스레드 무블록. 전 실패 삼킴+warn(FR-6, 에러코드 불요). DDL·yml 없음, Redis 전용(D4)
- MSG-184: 핫구역 조회(`GET /api/hotzones` 뷰포트 4파라미터 필수 — `service/HotZoneService`(+impl)·`HotZoneView`, `hotzone:top` 캐시(최근 8버킷 ZUNIONSTORE 균등 합산, TTL 30s, 캐시 보장 Lua 원자 — EXISTS→ZUNIONSTORE→EXPIRE), 상위 K(50)·임계(3)·뷰포트 필터(encode→decode 정수 인덱스, queryByRange 동형), `config/HotZoneProperties`(record, `fillmap.hotzone.top-k/min-score` — topK 양수 기동 검증), `controller/HotZoneController`·DTO 2종, `exception/HotZoneErrorCode`(8400 INVALID_VIEWPORT — 비유한 좌표 NaN 우회 차단 포함), 파라미터 누락은 전역 400(MSG-167 매핑, GridController 구 관행 미답습). 48h 판정은 룩백 몫·TTL은 청소 전용(D4 역할 분리))

### `notification` (Owner B) — 🟡 부분
- MSG-178: FCM 푸시 토큰 등록/해제 — `entity/PushToken`(String 자연키 PK, 조회 매핑 앵커)·`PushPlatform`(IOS/ANDROID/WEB), `repository/PushTokenRepository`(native UPSERT `ON CONFLICT (fcm_token) DO UPDATE` user_id 포함 — 계정 전환 이관 시맨틱 · 해제는 `WHERE fcm_token AND user_id` 소유 검증+멱등), `service/PushTokenService`(+Impl — platform `toUpperCase(Locale.ROOT)` 파싱 → 10400), `controller/PushTokenController`(`/api/notifications/tokens` POST·DELETE), `exception/NotificationErrorCode`(**10xxx 대역** — 9xxx 선확정했으나 MSG-185 병렬 경합(9400 충돌)으로 이동, 대역 정본 = response-pattern.md 표). 마이그레이션 없음(V1 push_tokens 그대로), FCM 호출 없음(발송은 MSG-179)
- MSG-179: FCM 발송 파이프라인 — outbox `notifications`(V21 — V20은 MSG-285 선점으로 리네임, 발송 기록 겸용) → `relay/NotificationRelay`(@Scheduled 5s 폴링, send().get() 동기 확인 후 PUBLISHED — 발행 실패 시 배치 중단, stale PUBLISHED(published_at 30분 초과) 자동 PENDING 복구) → Kafka(단일 브로커 KRaft·토픽 prefix dev./prod.·힙 256M/리밋 512M·thin payload=outbox id) → `consumer/NotificationConsumer`(@KafkaListener earliest·max.poll 600s/10건 — 멱등 2차(종결 상태 검사)→retry_count→설정 필터→전송률 제한(KST 자정 앱 UTC 변환)→토큰 조회→발송, TransactionTemplate 개별 트랜잭션) → `sender/FcmNotificationSender`(sendEachForMulticast 500 청크·UNREGISTERED/INVALID_ARGUMENT 자동 삭제·connect 10s/read 30s). 재시도 = DefaultErrorHandler 백오프(1s×2^n, 8회, 합 255s) → DEAD(DB DLQ — DLT 토픽 없음), 말폼드 비재시도. `relay/StaleTokenCleaner`(일 04시 KST, last_used_at 60일 무갱신 삭제). 접점 2종: `NotificationCommandService.record`(181 소비 — 호출자 트랜잭션 참여 규약)·`NotificationPreferenceService`(MSG-180이 실구현으로 교체 완료). 전부 `fillmap.notification.enabled` 게이트(기본 off — 로컬·CI는 Kafka 없이 green, @EmbeddedKafka 테스트). Codex 5라운드 7건 반영
- MSG-180: 알림 설정 API — `notification_opt_outs`(V22, 복합 PK(user_id, category)·CHECK·FK CASCADE) **행 존재 = off, 행 부재 = on**(기본 전부 on이 스키마 내장 — 시딩·백필 없음). `NotificationPreferenceService`에 `getPreferences`·`update` 추가(isEnabled 시그니처·`NotificationConsumer` 호출부 무변경 — 179 D7 규약 이행), Impl 스텁 → 실구현(`existsById` 부정 1문장 · off 행 조회 후 3종 합성 · 멱등 토글 = off INSERT ON CONFLICT DO NOTHING / on DELETE, 캐시 없음 — 발송량이 전송률 상한으로 눌려 PK 단건 조회로 충분), `controller/NotificationPreferenceController`(GET `/api/notifications/preferences` · PATCH `/{category}` — visibility PATCH 동형 멱등 토글), `INVALID_CATEGORY(10420)`. SecurityConfig·yml·compose 무변경
- **없는 것**: 트리거 연동(MSG-181 — record 호출부 배선), 알림 이력/읽음 API(후속 티켓 — PRD 비목표)

## 계약 인터페이스 (Owner A ↔ B 경계면)

`infrastructure.md`가 계약 인터페이스로 명시하지만 **아직 코드에 하나도 없다.** 새로 만들기 전엔
소비하는 쪽에서 import 불가.

| 인터페이스 | 제공자 | 상태 |
|---|---|---|
| `GridQueryService` | Owner A | ✅ built (MSG-73 — 격자 색칠 조회 read · MSG-90 — 4-arg cursor 페이지 시그니처 추가, 2-arg 유지·strategy 오버로드 제거) |
| `HotZoneService` | Owner A | ✅ built (MSG-184 — `getHotZones(ViewportBounds): List<HotZoneView>` read, D5 예약 이행. `ViewportBounds`는 grid.dto 재사용 — 둘 다 A 소유라 크로스오너 아님, 현 소비자는 `HotZoneController`뿐) |
| `HotScoreCommandService` | Owner A | ✅ built (MSG-183 — `recordUpload(gridId)` write 계약, B(video)의 업로드 확정 `afterCommit` 훅이 소비. 실패 비전파가 계약의 일부 — 호출자는 try-catch 불요) |
| `UserGridQueryService` | Owner B | 🟡 partial (MSG-152 — `getCollectionSummary` 도감 요약 read 계약 신설 B→A · MSG-153 — `getCollectionGrids` B-내부 read 추가, A 미소비·크로스오너 시그니처 불변 · MSG-167 — `CollectionGridView`에 regionName 필드 확장(비파괴) + 신설 공유 컬럼 `grids.region_code` A(쓰기 규칙 권위)↔B(호스팅·소비)) |
| `RegionQueryService` | Owner A | ✅ built (MSG-93 — `resolveByPoint(lat, lon)` 역지오코딩 read. stats 조회는 156에서 별도 서비스로 분리 확정) |
| `RegionStatsCommandService` | Owner A | ✅ built (MSG-155 — `refresh(userId, gridId)` 동기 recompute 명령. B의 첫 점령/롤백 훅이 소비, 호출자 트랜잭션 참여) |
| `UserOidcCommandService` | Owner B | ❌ 미생성 |

## 스키마 vs JPA 엔티티

`V1__init.sql`은 14개 테이블을 정의하고, `V6__mission_schema.sql`(MSG-166)이 미션 3테이블을, `V8__zones.sql`(MSG-234)이 `zones`를 추가했다(V7은 MSG-238 grids.region_code 인덱스가 선점). `V9__badges_seed.sql`(MSG-239)은 badges CHECK 확장(MISSION_COUNT)·`user_badges` 컬럼 2개(notified_at·featured_rank+partial UNIQUE)·활성 3축 11종 시딩·소급 지급을 담는다. `V10__streak_badges_seed.sql`(MSG-200)은 STREAK_3/7/30 시딩만 담는다(DDL 0·소급 없음). `V12__mission_badges_seed.sql`(MSG-223)은 MISSION_1/5/10 시딩만, `V13__mission_source.sql`(MSG-224)은 `missions.source` 적재 출처 컬럼 1개를 추가한다(NULL=수동·불가침). `V14__missions_popup_type_and_source_key.sql`(MSG-235)은 type CHECK에 'POPUP' 추가·`source_key` 컬럼·`(source, source_key)` 부분 유니크 인덱스를 담는다. (V15=MSG-205 reports FK ON DELETE·V16=MSG-310 email nullable·V17=MSG-283 videos fail_reason.) `V18__users_friend_code.sql`(MSG-185)은 `users.friend_code`(혼동 문자 제외 32종 8자) 추가·기존 행 상관 서브쿼리 백필·NOT NULL·UNIQUE 를, `V19__friendships_pair_unique.sql`(MSG-185)은 friendships 대칭 쌍 유니크 인덱스(LEAST/GREATEST — 상호 요청 레이스 DB 백스톱)를 담는다. `V20__videos_visibility_friends.sql`(MSG-285)은 `chk_videos_visibility` 를 DROP 후 3값('PUBLIC','PRIVATE','FRIENDS')으로 재생성한다(CHECK 은 조건 ALTER 불가 — 기존 행 데이터·컬럼 폭·인덱스 전부 무변경, ADD 시 전체 행 검증 스캔 1회. FRIENDS 행이 생긴 뒤 구버전 롤백은 CHECK 위반이라 되돌릴 수 없다). `V21__notifications.sql`(MSG-179)은 알림 outbox 겸 발송 기록 `notifications`(UNIQUE(user_id, event_key) 멱등·PENDING/PUBLISHED partial index 2개·FK CASCADE — V20은 MSG-285가 선점해 리네임)를 담는다. `V22__notification_opt_outs.sql`(MSG-180)은 알림 수신 거부 `notification_opt_outs`(복합 PK(user_id, category)·행 존재 = off — 행 부재가 기본 on이라 시딩·백필 없음, 추가 인덱스 없음)를 담는다.

| 테이블 | 엔티티 | 상태 |
|---|---|---|
| `users` | `user/entity/User` | ✅ (grid_color 매핑됨 — 구 표기 정정. V16 email nullable·V18 friend_code 반영) |
| `user_grids` | `grid/entity/UserGrid` | ✅ |
| `videos` | `video/entity/Video` | ✅ (MSG-66) |
| `grids` | `grid/entity/Grid` | ✅ (MSG-73 — 조회 전용 최소 매핑: grid_id/grid_y/grid_x, geom 미매핑; MSG-167 — `region_code`(V5) 추가·미매핑, native 접근) |
| `regions` | `region/entity/Region` | ✅ (MSG-154 — region_code/region_name/parent_code/total_grid_count 매핑, boundary_geom 미매핑 — native write 전용) |
| `region_stats` | — | ❌ 엔티티 없음 (native 쿼리로만 접근 — MSG-155/156) |
| `zones` | `zone/entity/Zone` | ✅ (MSG-234 — 전 컬럼 매핑, 정수 사각형·PostGIS 컬럼 없음, V8) |
| `badges` | `badge/entity/Badge` | ✅ (MSG-239 — condition_value JSONB 미매핑, 판정은 native. V9 시딩 11종) |
| `user_badges` | `badge/entity/UserBadge` | ✅ (MSG-239 — 복합 PK `UserBadgeId`, V9 notified_at·featured_rank 추가. 지급은 native ON CONFLICT) |
| `friendships` | `friend/entity/Friendship` | ✅ (MSG-185 — 복합 PK `FriendshipId`, status 는 PENDING·ACCEPTED 만 영속(§D3). V1 테이블 무수정) |
| `likes` | — | ❌ 엔티티 없음 |
| `push_tokens` | `notification/entity/PushToken` | ✅ (MSG-178 — 전 컬럼 매핑, 쓰기는 native UPSERT/DELETE 전용) |
| `notifications` | `notification/entity/Notification` | ✅ (MSG-179 — V21 신설, 전 컬럼 매핑. 쓰기는 native 전용 — 기록 ON CONFLICT·상태 전이·UTC published_at/sent_at) |
| `notification_opt_outs` | `notification/entity/NotificationOptOut` | ✅ (MSG-180 — V22 신설, `@EmbeddedId` 복합 키. 쓰기는 native 전용 — off INSERT ON CONFLICT·on DELETE) |
| `reports` | — | ❌ 엔티티 없음 |
| `sponsor_ads` | — | ❌ 엔티티 없음 |
| `streaks` | `streak/entity/Streak` | ✅ (MSG-200 — 전 컬럼 매핑, 쓰기는 native UPSERT 전용) |
| `missions` | `mission/entity/Mission` | ✅ (MSG-222 조회 매핑 → MSG-224 쓰기 경로: 시드 `@Builder`·`source`(V13)·created_at DB DEFAULT 위임. path JSONB 미매핑 — COURSE 시드는 MSG-225. MSG-235 `sourceKey`(V14) 매핑·빌더 8필드) |
| `mission_grids` | `mission/entity/MissionGrid` | ✅ (MSG-222 — 복합 PK `MissionGridId`, grids FK 없는 논리 참조(lazy insert, MSG-166 §D2). MSG-224 시드 생성자) |
| `user_missions` | `mission/entity/UserMission` | ✅ (MSG-223 — 복합 PK `UserMissionId`, native 전용 최소 매핑. 스탬프 영속 — 비회수) |

## 로드맵 / 백로그

티켓 시퀀싱·의존성·백로그는 **Jira MSG 프로젝트**가 단일 진실 원천이다. 이 문서는 *무엇이 빌드됐는지*만
기록하고 *무엇을 언제 할지*는 다루지 않는다.

## 유지 규칙

패키지나 계약 인터페이스가 planned → partial → built로 바뀌면 해당 행을 즉시 갱신한다.
(spec-driven-dev Phase 5 wrap-up에서 갱신 — 자세히는 해당 스킬 참조.)

- **편집 규칙 (MSG-169)**: 도메인 패키지 섹션은 **티켓당 한 줄 불릿을 append**한다. 기존 줄에
  조각을 이어 붙이지 말 것 — 병렬 PR이 같은 줄을 고치면 병합 충돌이 보장된다. "없는 것" 줄만
  예외적으로 제자리 수정.
