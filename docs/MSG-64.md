# MSG-64: S3 Presigned URL 발급 API

**Owner**: B

> 부모 에픽: MSG-48 "영상 기록" · 연결 스토리: MSG-60 "사용자는 영상을 촬영해서 업로드할 수 있다" (relates to)
> 담당: 성민 (Owner B — `com.msg.fillmap.video.*`)
>
> 착수 순서: 66(엔티티) 직후. 66의 메타저장 API가 이 티켓의 s3Key를 소비한다.

---

## 개요

영상 파일은 서버를 경유하지 않고 **클라이언트가 S3에 직접 PUT** 한다 (서버 부하·타임아웃 방지).
서버는 업로드 권한을 담은 **presigned PUT URL** 과 s3Key 만 발급한다. 클라이언트는 그 URL로 업로드한 뒤
s3Key 를 메타저장 API(MSG-66)에 전달한다.

---

## 확정된 결정 (구현 기준)

| # | 항목 | 확정 내용 |
|---|---|---|
| D1 | S3 key 규칙 | `videos/original/{userId}/{uuid}.{ext}` — userId는 JWT 인증 주체, uuid는 서버 생성(`UUID.randomUUID()`), ext는 요청 확장자 |
| D2 | 허용 확장자 / Content-Type | `mp4`(video/mp4), `mov`(video/quicktime) 만. 그 외 400 |
| D3 | 파일 크기 상한 | presigned 조건에 content-length-range 걸어 상한 강제 (예: 100MB — deploy 환경변수로 조정 가능) |
| D4 | URL TTL | 10분 (`Duration.ofMinutes(10)`) |
| D5 | 응답 | presigned PUT URL + s3Key. s3Key는 MSG-66 메타저장에서 그대로 재사용 |
| D6 | 에러 코드 대역 | **`VideoErrorCode` 신규 생성, developCode `3xxx` 대역** (auth=2xxx 회피). 이 티켓이 enum 최초 생성 지점 |
| D7 | AWS SDK | `software.amazon.awssdk:s3` + `s3-transfer-manager`/presigner 의존성 **신규 추가 필요** (현재 build.gradle에 AWS SDK 없음) |

---

## 산출물

### 위치 (Owner B, `com.msg.fillmap.video`)

- `video/controller/VideoController` — `POST /api/videos/presigned-url` (MSG-66 컨트롤러에 메서드 추가)
- `video/service/PresignedUrlService`(+Impl) — presigner 호출
- `video/dto/PresignedUrlRequestDto`, `PresignedUrlResponseDto`
- `video/exception/VideoErrorCode` — **신규 enum** (3xxx)
- `global/config/S3Config` 또는 `video/config` — `S3Presigner` 빈 등록

### 신규 의존성 / 설정

- `build.gradle`: `implementation platform('software.amazon.awssdk:bom:<ver>')` + `s3`.
- 환경변수(deploy.md 추가): `AWS_REGION`, `S3_BUCKET_VIDEO`(원본 버킷), 자격증명은 EC2 IAM Role 우선(로컬은 `AWS_ACCESS_KEY_ID`/`SECRET`). `application.yml`에 `aws.s3.bucket`, `aws.region` 바인딩.

### VideoErrorCode (신규, 3xxx 대역)

```java
@Getter
@AllArgsConstructor
public enum VideoErrorCode implements ErrorCodeIfs {

	UNSUPPORTED_EXTENSION(3400, HttpStatus.BAD_REQUEST, "지원하지 않는 영상 확장자입니다 (mp4, mov)"),
	FILE_TOO_LARGE(3413, HttpStatus.BAD_REQUEST, "허용 크기를 초과한 영상입니다"),
	PRESIGN_FAILED(3500, HttpStatus.INTERNAL_SERVER_ERROR, "업로드 URL 발급에 실패했습니다"),
	// MSG-66/71/72에서 확장: VIDEO_NOT_FOUND(3404), VIDEO_FORBIDDEN(3403), VIDEO_SAVE_FAILED(3501),
	//                        GRID_MISMATCH(3422) 등
	;
	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
```

### API — `POST /api/videos/presigned-url`

인증 필요.

요청 `PresignedUrlRequestDto`:
```
{ "extension": "mp4", "contentType": "video/mp4", "contentLength": 8388608 }
```
- `extension` @NotBlank + D2 화이트리스트 검증 → 위반 시 `UNSUPPORTED_EXTENSION`.
- `contentLength` @NotNull + D3 상한 검증 → 초과 시 `FILE_TOO_LARGE`.

응답 `SuccessResponse.of(PresignedUrlResponseDto)`:
```
{
  "uploadUrl": "https://{bucket}.s3.{region}.amazonaws.com/videos/original/42/uuid.mp4?X-Amz-...",
  "s3Key": "videos/original/42/uuid.mp4",
  "expiresInSec": 600
}
```

---

## 완료 조건

- [ ] 발급된 presigned URL 로 실제 dev 버킷 PUT 업로드 성공
- [ ] 미인증 요청 401
- [ ] 허용 외 확장자(예: `avi`) → `UNSUPPORTED_EXTENSION`(400), 크기 초과 → `FILE_TOO_LARGE`(400)
- [ ] 발급 API 단위 테스트 통과 (presigner mock 또는 LocalStack)

---

## 후속/의존

- **선행**: MSG-66(VideoController 골격). AWS SDK 의존성·버킷 인프라.
- **후행**: MSG-66 메타저장(s3Key 소비), MSG-71 교체(새 파일 presigned 재발급).
