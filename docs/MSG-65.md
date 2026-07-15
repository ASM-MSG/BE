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
| D3 | FFmpeg 실행 | ~~Dockerfile~~ → **EC2 에 직접 설치** (`sudo apt-get install -y ffmpeg`). `ProcessBuilder` 로 PATH 의 바이너리 호출. ⚠️ 아래 "D3 정정" 참조 — 이 프로젝트에는 Dockerfile 이 없다 |
| D4 | 변환 스펙 | 720p(scale=-2:720), H.264(libx264) + AAC 오디오. 썸네일 1장(첫 프레임 또는 1초 지점 jpg) |
| D5 | 상태 전이 | `UPLOADED → ENCODING → READY`, 실패 시 `FAILED`. `@Async` 진입 시 ENCODING, 완료 시 READY, 예외 시 FAILED(+ERROR 로그). AI Highlight-Blur의 `BLURRING`은 **범위 외**(architecture.md) |
| D6 | 결과 저장 | `videos.encoded_url` / `videos.thumbnail_url` 갱신. ⚠️ 아래 "D6 정정" 참조 — **URL 이 아니라 S3 key 를 저장한다**. key 규칙: `videos/encoded/{userId}/{videoId}.mp4`, `videos/thumb/{userId}/{videoId}.jpg` |

---

## 구현 중 확정된 정정 (2026-07-15, MSG-65 구현)

### D3 정정 — Dockerfile 이 아니라 EC2 직접 설치

**이 프로젝트에는 Dockerfile 이 없다.** 배포는 CD 가 jar 를 scp 하고 systemd(`fillmap-dev`)가
`java -jar` 로 직접 실행하는 구조다(`.github/workflows/cd-dev.yml`). 따라서 "런타임 이미지에 포함"이
성립하지 않는다.

- **dev/prod EC2**: `sudo apt-get install -y ffmpeg` (인프라 담당이 1회 실행)
- **로컬**: `brew install ffmpeg`
- 앱은 PATH 의 `ffmpeg`/`ffprobe` 를 호출한다. 경로를 설정으로 빼지 않는다(YAGNI).
- **CI 에는 ffmpeg 를 설치하지 않는다.** 실 ffmpeg 테스트(`FfmpegRunnerTest`)는 바이너리가 없으면
  `assumeTrue` 로 전부 skip 되므로 CI 가 깨지지 않는다(검증 완료: 5건 skip, BUILD SUCCESSFUL).

### D6 정정 — `encoded_url` 에는 URL 이 아니라 **S3 key** 를 저장한다

버킷이 Block Public Access 라 **영구 URL 이 존재하지 않는다.** presigned GET 은 TTL 이 있어 DB 에
박아둘 수 없고, CloudFront(MSG-67)는 아직 없다. 그래서 key 를 저장하고 재생 조회 시점에 presigned GET
을 발급한다. MSG-67 도입 시 key 앞에 CDN 도메인만 붙이면 되므로 이식성도 이 편이 낫다.

컬럼명(`encoded_url`)과 내용(key)이 어긋나지만 **컬럼명은 바꾸지 않는다** — V 파일 재작성은
체크섬 불일치로 dev 를 32시간 죽인 원인(MSG-130)이다. 엔티티 필드 주석에 명시했다.
재생 조회 API(presigned GET 발급)는 **본 티켓 범위 밖**이다.

### IAM 정책 정정 — `videos/original/*` → `videos/*`

MSG-64 에서 만든 `FillMapVideoUploadDev` 정책이 `videos/original/*` 만 허용해서 인코딩 결과 업로드가
403 `AccessDenied` 로 실패했다(로컬 실측에서 발견). 결과물은 `videos/encoded/`·`videos/thumb/` 로
올라간다. Resource 를 `arn:aws:s3:::fillmap-video-dev/videos/*` 로 넓힌다 — MSG-71/72 에서 경로가
늘어도 정책을 다시 고치지 않아도 된다.

### 산출물 정정 — `VideoStatusWriter` 추가

상태 전이를 `REQUIRES_NEW` 로 커밋해야 하는데, `@Transactional` 은 프록시 기반이라 **같은 클래스 안에서
호출하면(self-invocation) 적용되지 않는다.** `VideoEncodingServiceImpl` 안에 두면 ENCODING 이 DB 에
보이지 않고 실패 시 markFailed 까지 롤백된다. 그래서 전이 전용 빈으로 분리했다.
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
