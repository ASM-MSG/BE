# MSG-71: 영상 교체 API

**Owner**: B

> 부모 에픽: MSG-48 "영상 기록" · 연결 스토리: MSG-62 "사용자는 업로드한 영상을 교체할 수 있다" (relates to)
> 담당: 성민 (Owner B — `com.msg.fillmap.video.*`)
>
> 착수 순서: 마지막(66/64/65/72 이후). presigned(64)+인코딩(65) 파이프라인과 72의 소유권/cover 로직 재사용.

---

## 개요

사용자는 업로드한 영상을 교체(수정)할 수 있다 (glossary: 업로드 후 수정·삭제 자유).
**교체는 점령 상태에 영향을 주지 않는다** — video_count·점령 여부 불변.

---

## 확정된 결정 (구현 기준)

| # | 항목 | 확정 내용 |
|---|---|---|
| D1 | 엔드포인트 | `PUT /api/videos/{videoId}` — 인증 필요, **본인 영상만**, 타인 403 |
| D2 | 교체 정책 | **정책 B (row 유지 갱신)**. 기존 videos row 의 파일 참조(original_s3_key/encoded_url/thumbnail_url)만 갱신 + `processing_status = UPLOADED` 리셋. 새 row 생성 안 함 → 점령/video_count 자연 불변, 이력 미보존(MVP엔 불필요) |
| D3 | 격자 변경 | **같은 격자 내 교체만 허용**. 새 좌표의 grid_id 가 기존과 다르면 400(`GRID_MISMATCH`). 좌표 미변경(파일만 교체)이 기본 |
| D4 | 새 파일 흐름 | presigned URL(MSG-64) 재발급 → 클라이언트 S3 업로드 → 이 API 로 새 s3Key 전달 → 재인코딩(MSG-65) 파이프라인 재사용 |
| D5 | cover 유지 | `user_grids.cover_video_id` 가 이 videoId 면 그대로 유지(같은 row라 id 불변). 썸네일은 재인코딩 후 thumbnail_url 갱신으로 자동 반영 |
| D6 | 상태 리셋 | 교체 시 encoded_url/thumbnail_url 은 재인코딩 전까지 stale — processing_status=UPLOADED 로 되돌려 재인코딩 트리거 |

---

## 산출물

### 위치 (Owner B, `com.msg.fillmap.video`)

- `video/controller/VideoController` — `PUT /api/videos/{videoId}`
- `video/service/VideoService` — `replaceVideo(userId, videoId, request)`
- `video/dto/VideoReplaceRequestDto`
- (재사용) `VideoEncodingService`(MSG-65), `PresignedUrlService`(MSG-64), 소유권 검증(MSG-72와 공통 헬퍼)

### 교체 트랜잭션 (`@Transactional`)

```
1. video = findByIdAndUserId(videoId, userId)  // 없으면 3404, 타인 3403
2. 새 좌표 있으면 gridId' = GridEncoder.encode(lat,lon)
     if gridId' != video.gridId → ApiException(GRID_MISMATCH 3422)   // 격자 변경 불허
3. video 파일 참조 갱신:
     original_s3_key = 새 s3Key
     encoded_url = null, thumbnail_url = null
     duration_sec = 새 값
     processing_status = UPLOADED   // 재인코딩 대상
4. (커밋 후) encodingService.encode(videoId)  // MSG-65 재사용
```

- user_grids 는 **건드리지 않는다**(점령 불변). last_uploaded_at 갱신 여부는 선택(교체를 재방문으로 볼지 정책 — MVP는 미갱신 권장, 방문 이벤트 수 불변).

### 요청 `VideoReplaceRequestDto`

```
{
  "s3Key": "videos/original/42/newuuid.mp4",  // @NotBlank, MSG-64 재발급 값
  "durationSec": 15,                          // @NotNull 1~30
  "lat": 37.5012, "lon": 127.0396,            // 선택 — 있으면 D3 격자 일치 검증
  "recordedAt": "2026-07-13T15:00:00"
}
```

응답 `SuccessResponse.of({ "videoId": 123, "processingStatus": "UPLOADED" })`.

- `VideoErrorCode` 에 `GRID_MISMATCH(3422, BAD_REQUEST, "교체는 같은 격자에서만 가능합니다")` 추가.

---

## 완료 조건

- [ ] 본인 영상 교체 성공, 타인 영상 교체 403
- [ ] 교체 후 도감 상태(video_count, 점령 여부) **불변** 검증
- [ ] cover 영상 교체 → 재인코딩 후 도감 썸네일 갱신 확인
- [ ] 다른 격자 좌표로 교체 시도 → 400(GRID_MISMATCH)
- [ ] 교체 후 processing_status=UPLOADED → 재인코딩 → READY 확인

---

## 후속/의존

- **선행**: MSG-66(엔티티), MSG-64(presigned 재발급), MSG-65(재인코딩), MSG-72(소유권/cover 헬퍼).
- 정책 A(이력 보존)로의 전환은 필요 시 별도 티켓 — MVP는 B 고정.
