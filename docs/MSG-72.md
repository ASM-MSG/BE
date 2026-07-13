# MSG-72: 영상 삭제 API + 격자 점령 롤백

**Owner**: B

> 부모 에픽: MSG-48 "영상 기록" · 연결 스토리: MSG-63 "사용자는 업로드한 영상을 삭제할 수 있다" (relates to)
> 담당: 성민 (Owner B — `com.msg.fillmap.video.*`)
>
> 착수 순서: 66 이후. 여기서 정립하는 user_grids 원자적 롤백 로직을 MSG-71(교체)이 참조.

---

## 개요

영상 삭제 시 해당 격자의 점령 상태를 정리한다 (glossary "점령 롤백": 격자의 내 영상이 모두 삭제되면
도감에서 격자 제거). 파일은 즉시 지우지 않고(soft delete) videos.status 만 DELETED 로 바꾼다.

---

## 확정된 결정 (구현 기준)

| # | 항목 | 확정 내용 |
|---|---|---|
| D1 | 엔드포인트 | `DELETE /api/videos/{videoId}` — 인증 필요, **본인 영상만**(소유권 검증), 타인 403 |
| D2 | 삭제 방식 | **Soft delete**: `videos.status = DELETED`. S3 원본/인코딩 파일은 즉시 삭제하지 않음(정리는 별도 배치 백로그) |
| D3 | 롤백 원자성 | video_count 증감은 **원자적 UPDATE** 로 (동시 삭제/업로드 race 방지). 전 과정 1 트랜잭션 |
| D4 | cover 재선정 | 삭제 영상이 `user_grids.cover_video_id` 면 남은 ACTIVE 영상 중 재선정 — **최초 수집(가장 오래된 created_at) 우선**, 없으면 최신 |
| D5 | grids 유지 | `grids` row 는 삭제하지 않음 (전역 격자 등록은 영구) |
| D6 | 24h 규칙 | 업로드 24시간 이내 삭제는 즉시 롤백. 24시간 이후 정책은 미확정(glossary 🚧) — **MVP는 동일 처리**, 정책 확정 시 분기 |
| D7 | 이미 삭제된 영상 | status 가 이미 DELETED 면 멱등 처리(성공 또는 `VIDEO_NOT_FOUND`) — 중복 삭제 방어 |

---

## 산출물

### 위치 (Owner B, `com.msg.fillmap.video`)

- `video/controller/VideoController` — `DELETE /api/videos/{videoId}`
- `video/service/VideoService` — `deleteVideo(userId, videoId)`
- `video/repository/VideoRepository` — 원자적 UPDATE / cover 재선정 조회 native 쿼리
- (경계면) 점령 롤백은 user_grids 를 직접 갱신. `user_grids` 는 `grid/entity/UserGrid`(Owner A 소유)지만,
  MSG-78 D6 상 도감 write 는 영상 도메인 흐름에서 발생 → **VideoRepository 의 native UPDATE 로 처리**(엔티티 교차 의존 최소화).

### 삭제 트랜잭션 (`@Transactional`)

```
1. video = findByIdAndUserId(videoId, userId)  // 없으면 VIDEO_NOT_FOUND(3404)
   (다른 사용자 영상이면 VIDEO_FORBIDDEN(3403))
2. if video.status == DELETED → 멱등 종료
3. video.status = DELETED (soft delete)
4. 원자적 UPDATE user_grids
     SET video_count = video_count - 1, last_uploaded_at = now
     WHERE user_id=? AND grid_id=?
5. if 갱신 후 video_count == 0:
     DELETE user_grids WHERE user_id=? AND grid_id=?   // 점령 롤백
   else if 삭제 영상이 cover_video_id:
     남은 ACTIVE 영상 중 재선정(D4) → cover_video_id 갱신
```

- video_count 감소·0 판정은 race 방지를 위해 조건부 UPDATE 또는 `RETURNING video_count` 로 원자화.
- `VideoErrorCode` 에 `VIDEO_NOT_FOUND(3404)`, `VIDEO_FORBIDDEN(3403)` 추가(MSG-64에서 생성한 enum 확장).

### API

`DELETE /api/videos/{videoId}` → `SuccessResponse.of(null)` 또는 `{ "rolledBack": true }`(점령 롤백 발생 여부).

---

## 완료 조건

- [ ] 격자의 **마지막 영상 삭제 → user_grids row 삭제**(점령 롤백) 검증
- [ ] 같은 격자 2개 중 1개 삭제 → video_count 감소만, **점령 유지** 검증
- [ ] cover 영상 삭제 → 남은 영상으로 재선정 검증 (없으면 cover null)
- [ ] 타인 영상 삭제 → 403
- [ ] 이미 삭제된 영상 재삭제 → 멱등/404 (crash 안 함)

---

## 후속/의존

- **선행**: MSG-66(Video 엔티티·user_grids UPSERT 패턴).
- **후행**: MSG-71 교체가 cover 재선정·소유권 검증 로직 재사용.
- S3 파일 정리 배치는 별도 백로그.
