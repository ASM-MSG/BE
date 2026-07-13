# MSG-65: FFmpeg 720p H.264 변환 워커

**Owner**: B

> 부모 에픽: MSG-48 "영상 기록" · 연결 스토리: MSG-60 (relates to)
> 담당: 성민 (Owner B — `com.msg.fillmap.video.*`)
>
> 착수 순서: 66(엔티티·processing_status) 이후.

---

## 개요

원본 영상은 기기·화질이 제각각이라 재생 표준화가 필요하다. 원본 S3 업로드·메타저장(MSG-66) 완료 후
**비동기로 720p H.264 변환 + 썸네일 추출** 을 수행하고, 결과를 S3에 올린 뒤 videos 레코드를 갱신한다.

---

## 확정된 결정 (구현 기준)

| # | 항목 | 확정 내용 |
|---|---|---|
| D1 | 실행 방식 | **앱 내부 `@Async`** (별도 큐/워커/Lambda 없음). 메타저장(MSG-66) 트랜잭션 커밋 직후 비동기 호출. MVP 트래픽에 충분 |
| D2 | 천장(스케일) | 동시 업로드가 스레드풀을 압박하면 그때 SQS/Redis 큐 + 별도 워커로 분리. `// ponytail: 앱 내부 @Async, 처리량 늘면 큐로 분리` |
| D3 | FFmpeg 실행 | 런타임 이미지에 `ffmpeg` 바이너리 포함(Dockerfile `apt-get install ffmpeg`). `ProcessBuilder` 로 호출 |
| D4 | 변환 스펙 | 720p(scale=-2:720), H.264(libx264) + AAC 오디오. 썸네일 1장(첫 프레임 또는 1초 지점 jpg) |
| D5 | 상태 전이 | `UPLOADED → ENCODING → READY`, 실패 시 `FAILED`. `@Async` 진입 시 ENCODING, 완료 시 READY, 예외 시 FAILED(+ERROR 로그). AI Highlight-Blur의 `BLURRING`은 **범위 외**(architecture.md) |
| D6 | 결과 저장 | `videos.encoded_url`(720p mp4 URL), `videos.thumbnail_url`(jpg URL) 갱신. 결과 S3 key 예: `videos/encoded/{userId}/{videoId}.mp4`, `videos/thumb/{userId}/{videoId}.jpg` |
| D7 | duration 검증 | 변환 전 ffprobe 로 실제 길이 확인 → 30초 초과면 FAILED 처리(스키마 CHECK와 일관) |
| D8 | 재시도 | MVP 최소: FAILED 기록 + 로그. 자동 재시도 없음(수동 재트리거 API는 백로그) |

---

## 산출물

### 위치 (Owner B, `com.msg.fillmap.video`)

- `video/service/VideoEncodingService`(+Impl) — `@Async` 변환 파이프라인
- `video/config/AsyncConfig` — `@EnableAsync` + 전용 `ThreadPoolTaskExecutor`(제한된 pool size)
- (재사용) `PresignedUrlService`/S3 클라이언트 — 원본 다운로드·결과 업로드
- FFmpeg 호출 유틸 (`ProcessBuilder` 래퍼) — `video/support/FfmpegRunner` 등

### 파이프라인 (`@Async void encode(Long videoId)`)

```
1. video = videoRepository.findById(videoId)  // status UPLOADED 확인
2. video.markEncoding()                        // UPLOADED → ENCODING, save
3. tmpIn  = S3 원본(original_s3_key) 임시 다운로드
4. ffprobe 로 duration 확인 → 30초 초과 시 markFailed 후 종료 (D7)
5. ffmpeg: 720p H.264+AAC 인코딩 → tmpOut.mp4
6. ffmpeg: 썸네일 1장 추출 → tmpThumb.jpg
7. S3 업로드: encoded/{userId}/{videoId}.mp4, thumb/{userId}/{videoId}.jpg
8. video.markReady(encodedUrl, thumbnailUrl)   // ENCODING → READY, save
9. finally: 임시파일 정리
예외 시: video.markFailed() + log.error, 임시파일 정리
```

- 상태 전이 메서드는 Video 엔티티에 도메인 메서드로 (`markEncoding`/`markReady`/`markFailed`) — setter 지양 원칙.
- 트리거: MSG-66 `VideoServiceImpl` 저장 성공 후 `encodingService.encode(videoId)` 호출(같은 트랜잭션 커밋 이후 실행되도록 주의 — `@Async`는 별도 스레드라 커밋 후 조회 안전, 필요 시 `TransactionSynchronization`).

### 인프라

- Dockerfile 에 ffmpeg 설치. 로컬 개발자도 `ffmpeg`/`ffprobe` PATH 필요(README/deploy.md 안내 추가).
- 임시 작업 디렉터리(`java.io.tmpdir`) 사용, 처리 후 삭제.

---

## 완료 조건

- [ ] 샘플 영상 업로드 → 변환 → `processing_status=READY` + 재생 가능한 720p 산출물 확인
- [ ] `encoded_url`, `thumbnail_url` 갱신 확인
- [ ] 손상 영상/변환 실패 시 `FAILED` 기록 + 로그 확인
- [ ] 30초 초과 영상 → FAILED (D7)
- [ ] 상태 전이(UPLOADED→ENCODING→READY / →FAILED) 단위 테스트 통과

---

## 후속/의존

- **선행**: MSG-66(Video 엔티티·processing_status·저장 트리거 지점), MSG-64(S3 클라이언트·버킷).
- **범위 외**: AI Highlight-Blur(BLURRING 단계) — 별도 에픽(architecture.md).
- **후행**: MSG-71 교체가 이 파이프라인 재사용(재인코딩).
