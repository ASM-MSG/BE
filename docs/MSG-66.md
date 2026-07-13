# MSG-66: 영상 메타데이터 저장 (Video Entity)

**Owner**: B

> 부모 에픽: MSG-48 "영상 기록" · 연결 스토리: MSG-60(촬영 업로드) · MSG-62(교체) · MSG-63(삭제) (relates to)
> 담당: 성민 (Owner B — 콘텐츠/인증 도메인 `com.msg.fillmap.video.*`)
>
> ⚠️ **이 티켓은 영상 도메인 5개 티켓(64/65/66/71/72)의 토대다.** Video 엔티티·Repository가 나머지 전부의 전제.
> 착수 순서: **66 → 64 → 65 → 72 → 71**.

---

## 개요

업로드가 완료된 영상의 메타데이터를 DB에 저장한다. 원본 파일은 클라이언트가 S3에 직접 올리고(MSG-64),
서버는 그 결과(s3Key)와 촬영 좌표·시각을 받아 **격자 매핑 → grids lazy insert → videos INSERT**를 수행한다.

원래 티켓은 "DB 스키마 v6 반영(1부) + 엔티티/코드(2부)"였으나, **1부와 2부 일부는 이미 완료**됐다.
아래 §완료된 선행 작업 참조. 이 스펙의 실제 범위는 **Video 엔티티 + VideoRepository + 메타저장 API**로 좁혀진다.

---

## 완료된 선행 작업 (재작업 아님 — 확인만)

| 항목 | 상태 | 근거 |
|---|---|---|
| DB 스키마 v6 (`V1__init.sql` 교체) | ✅ 완료 | 커밋 `a9591c2`, `0d9d3ac` — grids.grid_y/grid_x + `uq_grids_yx`, videos.region_code/view_count, `duration_sec` CHECK ≤30, users.grid_color, 복합 PK(user_grids·likes·user_badges·friendships·region_stats), push_tokens.fcm_token PK, ENUM 11개 → VARCHAR+CHECK |
| `User.gridColor` 필드 | ✅ 완료 | `user/entity/User` 에 `@Enumerated GridColor gridColor` 존재 (기본 BLUE) |
| `UserGrid` @EmbeddedId(user_id, grid_id) 전환 | ✅ 완료 | `grid/entity/UserGrid` — `@EmbeddedId UserGridId` 적용됨 |
| `GridEncoder` (grid_y, grid_x) 반환 | ✅ 완료 | `GridEncoder.decode(gridId) → GridIndex(gridY, gridX)` 제공 |

> ⚠️ `V1__init.sql`이 재작성됐으므로 **로컬 DB는 flyway clean 후 재마이그레이션 필요** (이미 적용했다면 skip).

---

## 확정된 결정 (구현 기준)

| # | 항목 | 확정 내용 |
|---|---|---|
| D1 | 저장 흐름 | s3Key + 좌표(lat,lon) + recorded_at + duration_sec 수신 → `GridEncoder.encode` 로 grid_id 산출 → grids lazy insert(멱등) → videos INSERT |
| D2 | grids lazy insert | grid_id 없으면 INSERT, 있으면 no-op. **native UPSERT** (`INSERT ... ON CONFLICT (grid_id) DO NOTHING`). center_geom/bbox_geom은 `GridEncoder.center`/`bbox` 로 채움 |
| D3 | 점령(user_grids) 반영 | 첫 방문이면 user_grids INSERT(video_count=1), 재방문이면 `video_count+1` + last_uploaded_at 갱신 — **원자적 UPSERT** (`ON CONFLICT (user_id, grid_id) DO UPDATE`) |
| D4 | region_code 판정 | **본 티켓 범위 외**. INSERT 시 `region_code = NULL` 로 두고, 행정동 판정(PostGIS ST_Contains)은 별도 티켓(Owner A). videos.region_code 는 nullable 이므로 안전 |
| D5 | 최초 processing_status | `UPLOADED` (기본값). 인코딩 전이는 MSG-65 |
| D6 | 소유권 | 인증 사용자(JWT)의 userId 로만 INSERT |
| D7 | 좌표 검증 (서버) | **정적 plausibility 검증은 서버가 수행**: lat/lon 유효 범위 + 서비스 범위(한국 대략 lat 33~39, lon 124~132) 밖이면 `INVALID_COORDINATE`(4xx). 싸고 확실하므로 서버 책임 |
| D8 | 물리적 현장 여부 | **서버는 클라이언트가 보낸 좌표만 보므로 "실제로 그 격자에 있었는지"는 증명 불가**(GPS 스푸핑·API 직접호출로 우회 가능). 촬영 업로드의 현장 gating은 클라 UX가 담당하고, MVP는 검증된 좌표를 **신뢰**. 강한 검증(Play Integrity / App Attest 등 기기 무결성)은 MVP 범위 밖 백로그 |
| D9 | recorded_at | 미래 시각만 거부(now 이후 불가). **시간창(freshness) 검증은 걸지 않음** — 갤러리 업로드는 과거 영상 허용(glossary)이라 일괄 시간 제한 시 깨짐. 촬영/갤러리 구분이 필요해지면 source 필드 도입 후 분기(백로그) |

---

## 산출물

### 위치 (Owner B, `com.msg.fillmap.video`)

- `video/entity/Video` — `videos` 테이블 매핑 JPA 엔티티
- `video/repository/VideoRepository` — `JpaRepository<Video, Long>` + grids/user_grids UPSERT native 쿼리
- `video/service/VideoService`(+`VideoServiceImpl`) — 메타저장 트랜잭션
- `video/controller/VideoController` — `POST /api/videos`
- `video/dto/VideoUploadRequestDto`, `VideoUploadResponseDto`
- `video/exception/VideoErrorCode` — MSG-64에서 최초 생성, 본 티켓은 저장 실패 코드 사용

### Video 엔티티 컬럼 매핑 (videos DDL 기준)

| 컬럼 | 필드 | 비고 |
|---|---|---|
| id | Long id | `@GeneratedValue(IDENTITY)` |
| user_id | Long userId | NOT NULL |
| grid_id | String gridId | NOT NULL, FK grids |
| region_code | String regionCode | nullable (D4) |
| original_s3_key | String originalS3Key | MSG-64 발급 key |
| encoded_url / thumbnail_url | String | MSG-65가 채움, 초기 null |
| geom | Point (좌표) | NOT NULL. GEOGRAPHY(POINT,4326) — hibernate-spatial `org.locationtech.jts.geom.Point` |
| duration_sec | Short durationSec | CHECK 1~30 |
| processing_status | ProcessingStatus (enum→String) | 기본 UPLOADED |
| visibility | Visibility | 기본 PRIVATE |
| status | VideoStatus | 기본 ACTIVE |
| view_count | Long viewCount | 기본 0 |
| recorded_at | LocalDateTime recordedAt | NOT NULL |
| created_at | LocalDateTime createdAt | `@CreationTimestamp` |

- Enum: `ProcessingStatus(UPLOADED, ENCODING, BLURRING, READY, FAILED)`, `Visibility(PUBLIC, PRIVATE)`, `VideoStatus(ACTIVE, BLINDED, DELETED)` — `@Enumerated(STRING)`, DDL CHECK와 일치.
- `geom`: JPA 매핑에 hibernate-spatial 필요. 이미 grids/regions가 GEOGRAPHY를 쓰므로 의존성 확인 후 재사용. 좌표는 `(lon, lat)` 순서 주의(PostGIS X=경도).

### API — `POST /api/videos`

인증 필요. 업로드 완료 후 호출.

요청 `VideoUploadRequestDto`:
```
{
  "s3Key": "videos/original/{userId}/{uuid}.mp4",  // MSG-64 응답값, @NotBlank
  "lat": 37.5012,        // @NotNull, 위도
  "lon": 127.0396,       // @NotNull, 경도
  "durationSec": 12,     // @NotNull, 1~30 (@Min(1) @Max(30))
  "recordedAt": "2026-07-13T14:20:00"  // @NotNull
}
```

응답 `SuccessResponse.of(VideoUploadResponseDto)`:
```
{ "videoId": 123, "gridId": "41642_110458", "processingStatus": "UPLOADED", "occupied": true }
```
- `occupied`: 이 업로드로 **첫 점령**(user_grids 신규 생성)이면 true, 재방문이면 false.
- 실패는 `throw new ApiException(VideoErrorCode.XXX)` (예: 저장 실패 → `VIDEO_SAVE_FAILED`).

### 트랜잭션 (VideoServiceImpl, `@Transactional`)

```
1. gridId = GridEncoder.encode(lat, lon)
2. grids UPSERT (ON CONFLICT DO NOTHING) — center/bbox geom 포함
3. videos INSERT (region_code=null, processing_status=UPLOADED)
4. user_grids UPSERT:
     신규 → INSERT(video_count=1, cover_video_id=새 videoId)
     기존 → video_count+1, last_uploaded_at=now
5. (MSG-65 연동 시) 인코딩 @Async 트리거 — 본 티켓에서는 자리만
```

---

## 완료 조건

- [ ] flyway 재마이그레이션 성공, `./gradlew test` 전체 통과
- [ ] `POST /api/videos` 통합 테스트: 저장 성공 → videos/user_grids row 생성
- [ ] **같은 좌표 2회 업로드 시 grids row 1개 유지** (lazy insert 멱등성) + user_grids.video_count=2
- [ ] 미인증 요청 401, duration 31 이상 400
- [ ] 서비스 범위 밖 좌표(예: lat 0, lon 0) → `INVALID_COORDINATE`(400) (D7)
- [ ] `.claude/docs/status.md` 의 `video` 패키지 행 갱신 (❌ → 🟡/✅)

---

## 후속/의존

- **선행**: 없음 (스키마·엔티티 기반 완료). 바로 착수 가능.
- **후행**: MSG-64(s3Key 규칙 공급) — 병행 가능하나 응답 계약(s3Key 포맷) 합의 필요. MSG-65(인코딩 트리거 지점). MSG-72(삭제 롤백)·MSG-71(교체)가 이 엔티티/UPSERT 패턴 재사용.
- region_code 판정 티켓(Owner A, PostGIS ST_Contains) 별도 백로그.
