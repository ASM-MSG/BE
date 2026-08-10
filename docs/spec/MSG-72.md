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
| D2 | 삭제 방식 | **Soft delete**: `videos.status = DELETED`. ~~S3 원본/인코딩 파일은 즉시 삭제하지 않음(정리는 별도 배치 백로그)~~ → **MSG-133에서 정정: 커밋 후 즉시 삭제** (아래 참조) |
| D3 | 롤백 원자성 | video_count 증감은 **원자적 UPDATE** 로 (동시 삭제/업로드 race 방지). 전 과정 1 트랜잭션 |
| D4 | cover 재선정 | 삭제 영상이 `user_grids.cover_video_id` 면 남은 ACTIVE 영상 중 재선정 — **최초 수집(가장 오래된 created_at) 우선**, 없으면 최신 |
| D5 | grids 유지 | `grids` row 는 삭제하지 않음 (전역 격자 등록은 영구) |
| D6 | ~~24h 규칙~~ → **시간 제한 없음** | 언제 삭제하든 즉시 롤백. 코드에 시간 분기 없음. ⚠️ 아래 "D6 정정" 참조 |
| D7 | 이미 삭제된 영상 | status 가 이미 DELETED 면 멱등 처리(성공 또는 `VIDEO_NOT_FOUND`) — 중복 삭제 방어 |

---

## 구현 중 확정된 정정 (2026-07-16)

### D6 정정 — 24시간 규칙 폐기

**교체·삭제 모두 시간 제한이 없다** (2026-07-16 확정). 언제 삭제하든 즉시 롤백된다.
원래 D6은 "24시간 이내 즉시 롤백, 이후는 미확정 🚧"이었으나, 어차피 "MVP는 동일 처리"라 코드에는
분기가 없었고 **문서만 낡아 있었다.** `glossary.md`의 "삭제 24시간 규칙"·"24시간 이후 미확정 🚧"과
`auth-developer.md`의 "24시간 교체" 표현도 함께 정리했다.

> Jira MSG-71/72 본문에는 아직 24h 서술이 남아 있다(7/13 이후 미수정). 리포 문서가 정본이다.

### 알려진 갭 — 인코딩 중 삭제하면 DELETED 영상이 READY 가 된다

업로드 직후 인코딩이 도는 중에 삭제하면, 워커가 나중에 `markReady`를 호출해
`status=DELETED` + `processing_status=READY` 조합이 만들어진다. `VideoStatusWriter.markReady` 에
상태 검사가 없기 때문이다.

**사용자 영향은 없다** — `status=DELETED` 라 조회에서 걸러지고, 점령 롤백은 이미 끝난 뒤다.
남는 건 쓰지 않을 encoded/thumb S3 객체인데, **D2가 이미 "S3 파일은 즉시 삭제하지 않음(정리는 별도
배치 백로그)"으로 수용한 범주**다. MSG-65 영역이기도 해서 이 티켓에서는 건드리지 않았다.

> **MSG-133에서 해결됨 (2026-07-16)**: 인코딩 워커가 결과 업로드 직전에 삭제 여부를 재확인해 생략한다.

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
- ~~S3 파일 정리 배치는 별도 백로그.~~ → MSG-133에서 **배치 없이 시점 삭제**로 해결.

---

## D2 정정 — 배치 백로그 → 시점 삭제 (MSG-133, 2026-07-16)

**D2의 "즉시 삭제하지 않음"은 보존 원칙이 아니라 범위 유예였다.** 괄호의 "정리는 별도 배치 백로그"가
그 뜻이다 — "지우면 안 된다"가 아니라 "지우긴 할 건데 이 티켓 밖에서".

MSG-133에서 재확인한 결과 **배치를 만들 이유가 없었다**:

- **undelete 기능이 없다** — glossary·티켓 어디에도 없다. 없는 기능을 위해 파일을 남기는 건 과하다.
- **오히려 프라이버시 문제다** — 사용자가 지운 영상이 S3에 영원히 남는다.
- **시점 삭제가 훨씬 싸다** — `afterCommit`에서 `deleteObjects` 한 번이면 스케줄러·잡·실패 처리·모니터링이
  전부 필요 없다.

→ `deleteVideo`가 커밋 후 원본·인코딩본·썸네일을 지운다. 실패해도 로그만 남긴다(fail-open) — 이미 커밋된
삭제를 500으로 되돌릴 수 없고, 남은 객체는 비용 문제일 뿐이다.
