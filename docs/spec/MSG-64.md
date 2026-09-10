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
| D3 | 파일 크기 상한 | **서버측 상한 검증 + 정확 길이 서명** 2중 방어 (100MB, `S3_MAX_UPLOAD_BYTES`로 조정). ⚠️ 아래 "D3 정정" 참조 — `content-length-range`는 PUT presign에서 불가 |
| D4 | URL TTL | 10분 (`Duration.ofMinutes(10)`) — 서비스 상수. IAM role 임시 자격증명 만료(~6h)보다 짧게 유지해야 함 |
| D5 | 응답 | presigned PUT URL + s3Key. s3Key는 MSG-66 메타저장에서 그대로 재사용 |
| D6 | 에러 코드 대역 | `VideoErrorCode`의 `3xxx` 대역에 상수 추가. ⚠️ 아래 "D6 정정" 참조 — enum은 MSG-66이 이미 생성했고 3400은 선점됨 |
| D7 | AWS SDK | `software.amazon.awssdk:bom` + `s3` **신규 추가** (presigner는 `s3` 아티팩트에 포함). `s3-transfer-manager`는 **불필요** — 서버가 바이트를 다루지 않음 |

---

## 구현 중 확정된 정정 (2026-07-15, MSG-64 구현)

### D6 정정 — `UNSUPPORTED_EXTENSION`은 3400이 아니라 **3415**

이 티켓 작성 시점엔 `VideoErrorCode`가 없었으나, **MSG-66이 먼저 머지되며 enum을 생성하고
`INVALID_COORDINATE(3400)`으로 3400을 선점**했다. 머지된 API 계약을 깨지 않도록 재배정한다.

| 상수 | developCode | HttpStatus | 비고 |
|---|---|---|---|
| `INVALID_COORDINATE` | 3400 | BAD_REQUEST | MSG-66이 생성 — 변경 없음 |
| `FILE_TOO_LARGE` | 3413 | BAD_REQUEST | 413 니모닉 |
| `UNSUPPORTED_EXTENSION` | **3415** | BAD_REQUEST | 415 Unsupported Media Type 니모닉 |

`PRESIGN_FAILED(3500)`은 **추가하지 않는다.** presign은 네트워크 호출이 아닌 순수 로컬 서명 연산이라
실패 경로가 사실상 없고, 만약 실패해도 `GlobalExceptionHandler`가 `Exception → INTERNAL_SERVER_ERROR`로
이미 처리한다 (coding-principles.md §2).

### D3 정정 — `content-length-range`는 PUT presign에서 **기술적으로 불가능**

`content-length-range`는 **POST policy 전용 조건**이다. PUT presign은 policy 문서가 아니라 canonical
request에 서명하므로 조건식을 표현할 자리가 없다. (AWS SDK for Java v2에는 `createPresignedPost` 상당
API도 없다 — JS/Python SDK 전용)

대신 **정확 길이 서명**이 가능하다. `PutObjectRequest.contentLength(...)`는 `Content-Length` 헤더로
마셜되고, SigV4 서명 제외 목록에 `content-length`가 없어 `X-Amz-SignedHeaders`에 포함된다
→ 클라이언트가 선언과 다른 크기를 보내면 S3가 **403 SignatureDoesNotMatch**.
(`VideoPresignTest.presigned_URL_은_content_length_와_content_type_을_서명한다`가 이를 검증하며,
SDK 업그레이드 시 회귀 가드 역할을 한다.)

**채택: 2중 방어**
1. 서버측 상한 — `contentLength > aws.s3.max-upload-bytes` → `FILE_TOO_LARGE` (400)
2. 서명측 정확 일치 — 위조 시 S3가 403

합치면 "100MB 이하이면서 선언한 크기와 정확히 일치하는 업로드만 통과" = D3의 의도 달성.

### 산출물 정정 — `PresignedUrlService` 대신 `VideoService`에 메서드 추가

`VideoServiceImpl`은 이미 video 도메인의 유일한 서비스이고, presign은 같은 도메인·같은 컨트롤러·같은
`s3Key` 개념을 공유한다. 파일 2개를 아끼려고 합쳤다. MSG-71에서 S3 관심사가 2개째 붙으면 분리한다
(코드에 `ponytail:` 주석으로 표시).

### 프론트 계약 (중요)

`contentType`을 서명에 포함하므로, 클라이언트는 **발급 요청에 보낸 `contentType`을 PUT 헤더에 그대로
명시**해야 한다. blob 자동 추론 타입과 다르면 403이 난다 (특히 `.mov`).
```js
fetch(uploadUrl, { method: 'PUT', headers: { 'Content-Type': contentType }, body: file })
```

---

## 산출물

### 위치 (Owner B, `com.msg.fillmap.video`)

- `video/controller/VideoController` — `POST /api/videos/presigned-url` (MSG-66 컨트롤러에 메서드 추가)
- `video/service/VideoService`(+Impl) — `issuePresignedUrl` 메서드 추가 (위 "산출물 정정" 참조)
- `video/dto/PresignedUrlRequestDto`, `PresignedUrlResponseDto`
- `video/exception/VideoErrorCode` — 상수 2개 추가 (enum 자체는 MSG-66이 생성)
- `global/config/S3Config` — `S3Presigner` 빈 등록
- `global/config/AwsProperties` — `aws.region` / `aws.s3.*` 바인딩

### 신규 의존성 / 설정

- `build.gradle`: `implementation platform('software.amazon.awssdk:bom:<ver>')` + `s3`.
- 환경변수(deploy.md 추가): `AWS_REGION`, `S3_BUCKET_VIDEO`(원본 버킷), 자격증명은 EC2 IAM Role 우선(로컬은 `AWS_ACCESS_KEY_ID`/`SECRET`). `application.yml`에 `aws.s3.bucket`, `aws.region` 바인딩.

### VideoErrorCode (MSG-66이 생성한 enum에 상수 2개 추가)

```java
@Getter
@AllArgsConstructor
public enum VideoErrorCode implements ErrorCodeIfs {

	INVALID_COORDINATE(3400, HttpStatus.BAD_REQUEST, "서비스 지역 범위를 벗어난 좌표입니다"),   // MSG-66
	FILE_TOO_LARGE(3413, HttpStatus.BAD_REQUEST, "허용 크기를 초과한 영상입니다"),             // MSG-64
	UNSUPPORTED_EXTENSION(3415, HttpStatus.BAD_REQUEST, "지원하지 않는 영상 확장자입니다 (mp4, mov)"), // MSG-64
	// MSG-71/72에서 확장: VIDEO_NOT_FOUND(3404), VIDEO_FORBIDDEN(3403), GRID_MISMATCH(3422) 등
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
- `extension` @NotBlank + D2 화이트리스트 검증. **(확장자, contentType) 쌍**으로 검증해
  `mp4`+`video/quicktime` 같은 엇갈린 조합도 거부 → 위반 시 `UNSUPPORTED_EXTENSION`(3415).
- `contentLength` @NotNull @Positive + D3 상한 검증 → 초과 시 `FILE_TOO_LARGE`(3413).

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
