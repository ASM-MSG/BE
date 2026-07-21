# MSG-87: 격자별 대표 영상 선정 로직 (조회수·최신)

**Owner**: B (`com.msg.fillmap.video.*`) — videos 테이블 = Owner B 도메인. grid 패키지 무수정.

> 에픽: **MSG-79**(지도 탐색). 형제 경계: MSG-73(색칠 read·완료) / MSG-90(viewport 커서·완료) / **MSG-127**(격자별 **내** 영상 리스트·완료, 현 브랜치 포함) / 영상 재생·`view_count` 증가(별도 미구현 티켓).
> 이 티켓은 **순수 Owner B**다. 계약 인터페이스(`GridQueryService`/`UserGridQueryService`/`HotZoneService`/`UserOidcCommandService`) 시그니처 변경 없음 → 상대 팀 확인 불필요(§계약 변경).
> **선행 설계 반영**: 컨플루언스 "Grid 확장 API (예정)"(성민, 2026-07-17, pages/17891437)가 격자 전역 영상 API를 이미 설계했다. 본 스펙은 그 설계를 정본으로 삼아 "대표 영상 1건" 조회로 좁힌 것이다 — 정렬·필터·Owner 판단·필드·선행 과제 모두 그 페이지와 정합.
> MSG-127과 같은 read 성격이므로 구현 자산(`GridVideoController`·`VideoServiceImpl.presignThumbnailGet`)을 재사용한다(§재사용).

---

## 개요

지도에서 격자를 **미리보기(썸네일)로 대표할 영상 1건**을 격자당 골라 내려주는 read 경로를 만든다.
MSG-127이 "그 격자에 **내가** 올린 영상 리스트"였다면, 본 티켓은 "그 격자를 **전역에서** 대표하는 영상 1건"이다 — 시청 대상이 다르다.

```
[MSG-127] grid_id → 내 영상 리스트         (user_id = 나, ACTIVE, N건, 최근순)
[MSG-87]  grid_id → 전역 대표 영상 1건      (모든 사용자, 공개·READY, view_count DESC LIMIT 1)
```

**전역 선정(본인 포함)**. 대표는 저장·배치하지 않고 **조회 시점에 쿼리 한 방**으로 뽑는다.
후보가 없으면 200 + `body: null`(격자는 항상 존재하는 논리 개념 — glossary, MSG-73/127과 일관).

---

## ⚠️ 혼동 방지 (필수)

### 1. 개인 도감 커버 vs 전역 대표

두 개념은 이름이 비슷하지만 **완전히 다르다**. 절대 섞지 않는다.

| | `user_grids.cover_video_id` (개인 도감 커버) | **이 티켓 — 전역 대표 영상** |
|---|---|---|
| 범위 | 사용자 1명 (개인 도감) | **전역** (모든 사용자 영상 풀) |
| 저장 | DB 컬럼에 **저장**, 삭제 시 재선정(MSG-72 `reselectCover`) | **저장 안 함** — 조회 시점 쿼리로 계산 |
| 선정 규칙 | 남은 ACTIVE 중 **가장 오래된 것**(`ORDER BY created_at, id`) | **조회수 → 최신**(`view_count DESC, created_at DESC`) |
| 필터 | `status='ACTIVE'`만 (내 도감이라 PRIVATE·인코딩 중도 포함) | `ACTIVE AND PUBLIC AND READY` (공개 노출 대상만) |
| 소관 | MSG-72 (완료) | **MSG-87 (본 티켓)** |

본 티켓은 `user_grids.cover_video_id`를 **읽지도 쓰지도 않는다.** 경로/응답에 "cover"를 쓰더라도 개인 도감 커버 컬럼과 무관하다.

### 2. DTO 이름 충돌 (컨플루언스 설계 vs 코드 현실)

컨플루언스 설계는 전역 목록 DTO를 `GridVideoResponseDto`로 명명했으나, **MSG-127이 이미 그 이름을 "내 영상 리스트" DTO로 소비**했다(`video/dto/GridVideoResponseDto`). 따라서 본 티켓의 대표 영상 DTO는 **별도 이름 `GridCoverVideoResponseDto`**를 쓴다 — 같은 이름 재사용은 의미 충돌(내 리스트 항목 ≠ 전역 대표)이라 금지.

---

## 배경 · 목표

- **사용자/제품 관점**: MVP UI는 미점령 격자를 표시하지 않고(glossary), 영상이 올라온 격자만 지도에 드러난다. 그 격자를 대표하는 썸네일 하나가 있어야 "여기 뭔가 찍혔다"를 시각적으로 알린다. Jira 원문: "격자별로 대표 영상은 조회수를 기반으로 선정한다 … 아무 영상이 없다면 업로드된 첫 영상을 대표로 임시 설정."
- **목표**: `gridId` 하나로 그 격자의 전역 대표 영상(썸네일·조회수·길이)을 조회하는 read API 1종을 Owner B(video)에 제공한다. 저장 없이 쿼리로 계산한다.

---

## 선행 상태 (현재 코드 — 현 브랜치 `feature/MSG-87-grid-cover-video`)

현 브랜치는 **MSG-127 구현을 이미 포함**한다. 본 티켓은 그 자산을 확장·재사용한다.

| 있음 (MSG-127이 만든 것) | 본 티켓에서 |
|---|---|
| `video/controller/GridVideoController` (`/api/grids/{gridId}/...` 네임스페이스, `@AuthenticationPrincipal AuthPrincipal`) | **재사용** — 메서드 1개 추가(§재사용) |
| `video/service/VideoServiceImpl.presignThumbnailGet(String)` (private, 썸네일 key → presigned GET URL) | **재사용** — 그대로 호출 |
| `video/dto/GridVideoResponseDto` ("내 영상 리스트" 항목) | **재사용 안 함** — 의미·이름 충돌(§혼동 방지 2). 별도 `GridCoverVideoResponseDto` 신설 |
| `videos.idx_videos_grid_popular` 부분 인덱스 (`V1__init.sql:110`) | **정본** — 마이그레이션·신규 인덱스 없음 |

- `idx_videos_grid_popular ON videos (grid_id, view_count DESC, created_at DESC) WHERE status='ACTIVE' AND visibility='PUBLIC' AND processing_status='READY'` — **이 부분 인덱스가 대표 선정 정책의 단일 진실 원천**이다(컨플루언스 페이지도 동일 명시). 본 티켓의 WHERE·ORDER BY를 이 인덱스와 **정확히 일치**시킨다.

---

## 성공 기준

1. `GET /api/grids/{gridId}/cover`가 그 격자의 **전역 대표 영상 1건**을 반환한다 — 필터 `status=ACTIVE AND visibility=PUBLIC AND processing_status=READY`, 정렬 `view_count DESC, created_at DESC`, `LIMIT 1`.
2. 응답 `GridCoverVideoResponseDto`는 `videoId`, `thumbnailUrl`(presigned GET URL), `durationSec`, `viewCount`, `recordedAt`를 담는다(§API).
3. 조회수가 가장 높은 공개·READY 영상이 선정된다. 동률이면 최신(`created_at DESC`)이 이긴다.
4. `view_count`가 전부 0인 MVP 기간엔 정렬이 자연히 **최신순 폴백**이 된다(§도메인 3). 티켓의 "첫 영상 임시 대표"는 이 폴백으로 갈음 — **별도 로직 없음**.
5. 후보가 없으면(비공개만·인코딩 중만·타인 없음·존재하지 않는 gridId 포함) **404가 아니라 200 + `body: null`**(격자는 논리 개념 — MSG-73/127과 일관).
6. 다른 사용자의 공개 영상도 대표가 될 수 있다(**전역**, 본인 포함). 비공개(PRIVATE)·삭제·블라인드·인코딩 미완 영상은 절대 대표가 되지 않는다.
7. 응답은 `SuccessResponse.of(...)`로 감싸 HTTP 200 + `developCode 200`.
8. 인증 없이 호출하면 SecurityConfig 기존 정책대로 401(§도메인 4).
9. Flyway 마이그레이션 없이(v6 `V1__init.sql`) 전체 테스트 green.

---

## 스코프

**하는 것**
- `VideoRepository`에 전역 대표 조회 메서드 추가(부분 인덱스 조건과 일치, `LIMIT 1`).
- `VideoService`에 대표 조회 메서드 추가 + `VideoServiceImpl` 구현(썸네일 presign은 **기존 `presignThumbnailGet` 재사용**).
- `GridVideoController`에 `GET /api/grids/{gridId}/cover` 메서드 추가(§재사용 — 신규 컨트롤러 안 판다).
- 신규 응답 DTO `GridCoverVideoResponseDto`.
- 조회 통합 테스트(`@SpringBootTest`+local) · 컨트롤러 테스트(`+@AutoConfigureMockMvc`).

**스코프 밖** (출처: 컨플루언스 "Grid 확장 API (예정)"에서도 별도 범위로 분리)

| 항목 | 소관 |
|---|---|
| `view_count` 증가(영상 재생 API) | 별도 재생 조회 티켓 — 본 티켓은 view_count를 **읽기만** 한다 |
| 재생 URL(원본/인코딩본 스트리밍) | 별도 재생 조회 API — 컨플루언스 방침 동일(대표 조회는 재생 URL 안 줌) |
| 좋아요(`likeCount`·`liked`) | **likes 기능 자체 미구현** — 별도 티켓. 컨플루언스 목록 필드에 있으나 대표 DTO에서 제외 |
| 핫구역(Redis Sorted Set) · "핫한 뭔가" 실시간 랭킹 | Phase 2+ (glossary 🚧 핫구역) — MVP는 view_count 정렬로 갈음. 컨플루언스도 별도 범위 |
| 태그 필터 조회 | 컨플루언스 별도 범위 — 미구현 |
| 대표 영상 캐싱·배치 저장 | **불채택** — 조회 시점 계산(§도메인 2) |
| 격자별 **내** 영상 리스트 | MSG-127 (완료) |
| 개인 도감 커버(`user_grids.cover_video_id`) | MSG-72 (완료) — 무관(§혼동 방지 1) |

---

## API 명세

인증: 로그인 필수. `userId`는 조회 결과에 영향을 주지 않지만(전역 선정), 엔드포인트 자체는 인증 뒤에 둔다(§도메인 4).

### `GET /api/grids/{gridId}/cover` — 격자 전역 대표 영상

> **경로·컨트롤러 배치 (컨플루언스 선택지 (a))**: 기존 `GridVideoController`(video 패키지)에 **메서드 하나를 추가**한다 — 신규 컨트롤러를 파지 않는다. 이는 컨플루언스 페이지가 제시한 선택지 **(a) "격자 상세를 Owner B의 video에 두고 경로만 `/api/grids/...`로 낸다"**이며, MSG-127이 이미 택해 검증한 방식이다. 대표 영상은 "격자의 대표 하위 리소스"라 `/cover` 서브패스가 REST 의미·FE 멘탈모델("격자 → 그 대표 썸네일")과 맞는다. grid 패키지 무수정(경로 접두사가 소유권을 강제하지 않음 — MSG-127에서 확립).

- Path: `gridId` (예: `"41642_110458"`)
- 성공 200 `body` = `GridCoverVideoResponseDto` (후보 없으면 `null`)

**`GridCoverVideoResponseDto` 필드** (컨플루언스 전역 목록 필드에서 대표 1건에 맞게 취사)

| 필드 | 타입 | 의미 |
|---|---|---|
| `videoId` | Long | 대표 영상 id. 개별 재생 진입 키 |
| `thumbnailUrl` | String | 썸네일 presigned GET URL. 대표는 항상 READY라 **null이 되지 않는다**(필터가 READY 강제) |
| `durationSec` | Short | 영상 길이(초, 최대 30) |
| `viewCount` | Long | 조회수 — **선정 정렬 키라 포함**(정렬 근거를 FE가 보여줄 수 있음) |
| `recordedAt` | LocalDateTime | 촬영 시각 — 컨플루언스 목록 필드와 정합(표시용) |

- **`processingStatus` 미포함**: 필터가 READY만 통과시켜 항상 `"READY"`라 무의미(MSG-127 리스트와 달리 대표는 상태 분기가 없다). 넣지 않는다(coding-principles §2).
- **작성자 정보(`authorNickname`·`authorGridColor`) 미포함 — 프라이버시**: 컨플루언스 목록엔 있으나, 전역 대표는 타인 영상일 수 있어 **누가 어디 갔는지 노출**된다(glossary Phase 2+ "프라이버시" — 노출 방지 원칙). MVP는 최소 노출로 작성자 식별 정보를 담지 않는다. 지도 미리보기 썸네일에 작성자 표기가 필요해지면 Phase 2에서 별도 결정.

**응답 예시 (대표 있음)**
```json
{
  "developCode": 200,
  "httpStatus": "OK",
  "message": "성공",
  "body": {
    "videoId": 1042,
    "thumbnailUrl": "https://bucket.s3.ap-northeast-2.amazonaws.com/videos/thumb/1042.jpg?X-Amz-...",
    "durationSec": 12,
    "viewCount": 37,
    "recordedAt": "2026-07-20T18:03:11"
  }
}
```

**응답 예시 (대표 없음)**
```json
{ "developCode": 200, "httpStatus": "OK", "message": "성공", "body": null }
```

**에러**: 없음(신규 도메인 에러코드 추가 없음 — §도메인 3). 인증 실패는 SecurityConfig가 401 처리.

---

## 도메인 로직

### 1. 전역 대표 선정 (인덱스가 정본)

1. `videos`에서 `grid_id = :gridId AND status='ACTIVE' AND visibility='PUBLIC' AND processing_status='READY'` 필터.
2. `ORDER BY view_count DESC, created_at DESC LIMIT 1`로 1건 선정.
3. 이 필터·정렬은 `idx_videos_grid_popular` 부분 인덱스(`V1__init.sql:110`)와 **바이트 단위로 일치**시킨다.
   > **컨플루언스 경고 (그대로 인용)**: "이 조건을 벗어나는 쿼리는 인덱스를 못 타므로, 정렬 옵션을 추가하려면 인덱스부터 다시 봐야 한다." → 정렬/필터 변경 요청이 오면 쿼리만 고치지 말고 **부분 인덱스를 먼저** 재설계한다.
4. 선정된 영상의 썸네일 key → `presignThumbnailGet`로 presigned GET URL 발급(§재사용) → `GridCoverVideoResponseDto`로 매핑.
5. `user_id` 조건 **없음** — 전역 선정(본인 포함). MSG-127의 개인 격리(`user_id = :userId`)와 정반대 축이다.

### 2. 저장하지 않는다 (조회 시점 계산)

- 대표 영상을 컬럼에 저장하거나 배치로 갱신하지 않는다. 매 요청마다 위 쿼리 1회로 뽑는다.
- 근거: `LIMIT 1` + 부분 인덱스라 조회 비용이 낮고, 저장하면 업로드·삭제·view_count 변동마다 재계산·정합성 관리가 붙는다(과설계 — coding-principles §2). 캐싱은 실제 부하가 보일 때 후속 티켓에서(YAGNI).

### 3. "첫 영상 임시 대표" = 최신순 폴백 (별도 로직 없음)

- 티켓 원문 "아무 영상이 없다면 업로드된 첫 영상을 임시 대표"는 **별도 분기가 필요 없다.**
- MVP 기간엔 `view_count`가 전부 0(재생 API 미구현 — 스코프 밖)이라, `ORDER BY view_count DESC, created_at DESC`는 자연히 **`created_at DESC` 단일 정렬**로 축약된다.
- ⚠️ **엄밀히는 "첫 영상"이 아니라 "가장 최신 공개 영상"이 뽑힌다**(created_at DESC). 티켓 문구와 방향이 반대지만, 사용자와 이 폴백으로 갈음하기로 확정했다. 인덱스(`created_at DESC`)가 정본이므로 이를 따르며 별도 분기를 만들지 않는다.
- 후보가 아예 없으면(공개·READY 영상 0건) `null` 반환(성공 기준 5).

### 4. 인증·에러 정책

- **인증 필수.** 전역 공개 데이터지만 `SecurityConfig`가 `anyRequest().authenticated()`라 이 엔드포인트도 인증 뒤에 둔다. 공개 허용하려면 SecurityConfig를 고쳐야 하는데, 무변경 원칙(coding-principles §3)상 기존 정책을 그대로 따른다. 미인증은 401.
- **gridId 포맷 검증 안 함.** 잘못된/존재하지 않는 gridId는 매치 0건 → `null`. "미점령 격자 = 대표 없음"과 같은 안전한 응답이라 신규 에러코드 불필요(MSG-127 §도메인 3과 동일).

---

## 데이터 모델

**Flyway 마이그레이션 불필요.** v6 `V1__init.sql`이 필요한 컬럼·인덱스를 모두 보유한다.
- `videos`: `grid_id`, `view_count`, `created_at`, `recorded_at`, `visibility`, `processing_status`, `status`, `thumbnail_url`, `duration_sec` — 전부 존재·`Video` 매핑 완료.
- 인덱스: `idx_videos_grid_popular`(부분 인덱스)가 이미 이 쿼리를 위해 존재(`V1__init.sql:110`, 주석 "격자 대표 영상 조회 (조회수 → 최신순)"). **신규 인덱스 없음.**

**신규 엔티티 없음.** `Video`를 그대로 조회한다.

**리포지토리 메서드(제안)** — 부분 인덱스 조건이 4개라 derived query 이름이 길어진다. **native `@Query` + `LIMIT 1`**로 인덱스 조건을 명시적으로 드러내는 쪽을 권장(인덱스와 1:1 대조 가능):
```java
@Query(value = """
	SELECT * FROM videos
	WHERE grid_id = :gridId
	  AND status = 'ACTIVE' AND visibility = 'PUBLIC' AND processing_status = 'READY'
	ORDER BY view_count DESC, created_at DESC
	LIMIT 1
	""", nativeQuery = true)
Optional<Video> findGlobalCover(@Param("gridId") String gridId);
```
대안 derived query `findFirstByGridIdAndStatusAndVisibilityAndProcessingStatusOrderByViewCountDescCreatedAtDesc(...)`도 같은 SQL이지만 이름이 길고 인덱스와 눈으로 대조되지 않는다. **최종 선택은 리뷰**(둘 다 같은 실행계획).

---

## 계약 변경

**없음.** 본 티켓은 `com.msg.fillmap.video.*` 내부에서 완결된다.
- `GridQueryService`(A) / `HotZoneService`(A) / `UserGridQueryService`(B) / `UserOidcCommandService`(B) 시그니처 **모두 불변**.
- `VideoService`에 조회 메서드가 추가되지만, 이 인터페이스는 Owner A↔B 계약 경계면이 **아니다**(video 도메인 내부 서비스) → 상대 팀 확인 불필요.
- grid 패키지 파일 무수정(`GridEncoder`조차 import하지 않음 — §도메인 4).

---

## 선행 과제 · 미해결 질문

### ⚠️ 치명적 선행 과제 — 공개 범위 설정 API (별도 티켓 필요)

- `videos.visibility` 기본값이 **`PRIVATE`**이고(`V1__init.sql:94`), **공개(PUBLIC) 전환 API가 아직 없다.**
- 따라서 **현재 데이터로는 이 API가 항상 `null`을 반환한다** — 필터의 `visibility='PUBLIC'`을 만족하는 행이 하나도 없기 때문(컨플루언스 페이지도 같은 경고).
- **본 티켓 구현은 그대로 진행 가능**하다: 필터가 옳으므로 **공개 영상이 생기는 순간 자동으로 작동**한다. 구현·테스트(픽스처에 `visibility=PUBLIC` 지정)는 막히지 않는다.
- **선행/후속 티켓 필요**: "영상 공개 범위 설정(PRIVATE↔PUBLIC 전환) API" — 이게 배포돼야 실사용자 화면에서 대표 영상이 실제로 노출된다. 이 티켓을 **별도로 발행**할 것(본 티켓 스코프 밖).

### 리뷰 확인 포인트 (착수 차단 아님)

1. **리포지토리 쿼리 스타일**: native `@Query`+`LIMIT 1`(권장, 인덱스와 1:1 대조) vs derived query. 실행계획 동일. → 리뷰 확정.
2. **`recordedAt` vs `createdAt` 표시**: DTO는 컨플루언스 목록과 정합하게 `recordedAt`(촬영 시각)을 노출한다. 단 **정렬 tie-break 키는 `created_at`**(인덱스 정본)이다 — 표시값과 정렬키가 다름을 FE에 명시. 필요 시 둘 다 노출 검토. → 리뷰 확정.

---

## 테스트 시나리오 (JUnit5 + AssertJ · 한국어 백틱 메서드명)

테스트 인프라는 MSG-127과 동일: `@SpringBootTest` + `local` 프로파일(실 PostGIS, Flyway V1), 컨트롤러는 `+@AutoConfigureMockMvc`. presign은 서명만 하므로 실 버킷 없이 URL 문자열이 검증된다. 픽스처는 `visibility=PUBLIC`·`processing_status=READY`로 세팅해야 대표가 잡힌다(선행 과제 반영). `original_s3_key`는 `uq_videos_original_s3_key` 제약 때문에 UUID로 유니크화(MSG-127 선례).

### `VideoRepository` (전역 대표 쿼리)
- `조회수가_가장_높은_공개_READY_영상이_대표로_선정된다`
- `조회수_동률이면_최신_영상이_대표가_된다` (view_count 같을 때 created_at DESC)
- `비공개_PRIVATE_영상은_대표가_되지_않는다`
- `인코딩중_영상은_대표가_되지_않는다` (processing_status != READY 제외)
- `삭제된_영상은_대표가_되지_않는다` (status != ACTIVE 제외)
- `다른_사용자의_공개_영상도_대표가_될_수_있다` (전역 선정 — 본인 아님)
- `공개_READY_영상이_없는_격자는_빈_Optional을_반환한다`

### `VideoServiceImpl` (대표 조회 + 썸네일 presign)
- `대표_영상의_썸네일은_presigned_GET_URL로_발급된다`
- `발급된_썸네일_URL은_서명파라미터를_포함한다` (presign 회귀 — X-Amz-Signature 등)
- `대표가_없으면_null을_반환한다` (예외 아님)
- `view_count가_모두_0이면_최신_영상이_대표가_된다` (최신순 폴백 — §도메인 3)
- `대표_응답에_작성자_정보가_포함되지_않는다` (프라이버시 회귀 — authorNickname 등 필드 부재)

### `GridVideoController` (MockMvc)
- `격자_대표영상_조회는_200과_영상을_반환한다`
- `대표가_없으면_200과_body_null을_반환한다`
- `인증없이_호출하면_401이다`

---

## 작업 로그

### 2026-07-21 — 초기 구현 (spec-driven-dev)

- 스펙 작성(spec-writer, 컨플루언스 "Grid 확장 API (예정)" pages/17891437 정합 반영) → auth-dev 구현 → convention-reviewer 통과 → 전체 빌드 green
- DTO는 재사용이 아니라 `GridCoverVideoResponseDto` 신설 — 컨플루언스 설계의 `GridVideoResponseDto`(전역 목록용)와 MSG-127(내 리스트용) 이름 충돌 회피
- 작성자 정보 미노출(프라이버시) + 회귀 테스트로 강제, processingStatus 미포함(항상 READY)
- 컨트롤러 메서드에서 `@AuthenticationPrincipal` 파라미터 제거 — 전역 선정이라 userId 불필요, 인증은 SecurityConfig가 강제
- 선행 과제 재확인: visibility 기본값 PRIVATE + 공개 전환 API 부재 → 실데이터로는 항상 null. 별도 티켓 필요
- status.md video 행 갱신 (MSG-87 built, 남은 것에 "공개 범위 설정 API" 추가)
- 제안 커밋: `MSG-87 feat: 격자 전역 대표 영상 조회 API 및 view_count 정렬 선정`
