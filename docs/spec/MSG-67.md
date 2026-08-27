# MSG-67: 영상 전송망 연동과 실제 재생 성능 검증

**Owner**: B

> PRD 면제: 재생 권한, API 필드와 사용자 동작을 바꾸지 않는 성능 및 인프라 개선이다.
> 요구사항 입력은 Jira MSG-67 설명과 2026-08-27 대화에서 확정한 MP4 재생 경로다.

## 개요

현재 재생 API는 비공개 S3 객체를 가리키는 사전서명 URL을 발급한다. 이를 CloudFront 서명 URL[^1]로
바꾸고 실제 MP4 파일의 전체 다운로드와 Range 요청[^2]을 측정한다. 업로드는 지금처럼 S3 사전서명
URL을 사용한다.

Jira의 기존 AI 컨텍스트에는 인코딩 산출물이 HLS라고 적혀 있으나 현재 구현은 MP4 한 파일이다.
이 티켓은 HLS 재생 목록이나 세그먼트를 만들지 않는다.

## 배경과 목표

영상 재생 요청은 서울 리전 S3로 바로 간다. 국내 접속에서는 첫 요청의 지연 차이가 작을 수 있지만,
같은 영상을 반복해서 보는 격자 대표 영상과 핫구역에서는 엣지 캐시가 원본 요청과 전송량을 줄일 수
있다. 성능 향상을 미리 단정하지 않고 직접 다운로드와 CloudFront 콜드 및 웜 요청을 같은 파일로
비교한다.

2026-08-27 AWS 실사 결과는 다음과 같다.

- 영상 버킷은 `fillmap-video-dev`다. 영상용 CloudFront 배포는 없고 웹 정적 파일 배포 1개만 있다.
- 검증 영상은 `videos/encoded/524/240323.mp4`, `240324.mp4`, `240325.mp4` 세 개다. 크기는 각각
  2,613,071바이트, 1,101,704바이트, 3,005,503바이트다.
- S3 직접 요청 9회의 중앙값은 전체 다운로드 TTFB 105.9ms, 전체 시간 250.8ms다.
- 첫 1MiB Range 요청 9회의 중앙값은 TTFB 94.4ms, 전체 시간 181.6ms다.

목표는 재생 API가 접근 권한을 판정한 뒤 CloudFront 서명 URL을 발급하고, 캐시 적중 여부와 MP4 시킹
응답을 숫자로 남기는 것이다.

## 성공 기준

1. `GET /api/videos/{videoId}`의 `playbackUrl`과 영상 목록의 `thumbnailUrl`이 CloudFront 도메인을
   사용한다. READY 이전이나 접근 거부 시 기존 null 및 오류 동작은 바뀌지 않는다.
2. 서명 없는 CloudFront URL과 만료된 URL은 403이고, 유효한 서명 URL은 200이다.
3. `Range: bytes=0-1048575` 요청은 206과 올바른 `Content-Range`를 반환한다.
4. 같은 객체의 반복 요청에서 `X-Cache: Hit from cloudfront`가 확인된다.
5. 세 영상에 전체 다운로드와 1MiB Range 요청을 각각 콜드 1회, 웜 10회 실행한다. S3 직접 요청과
   CloudFront 요청의 TTFB 및 전체 시간 중앙값을 작업 로그에 남긴다. 개선 폭은 합격 조건으로 미리
   고정하지 않으며 느려진 축도 그대로 기록한다.
6. 영상 교체 후 새 API 요청은 이전 파일이 아니라 새 파일을 가리킨다. 이전 시도에서 발급한 URL은
   최대 10분 동안 유효할 수 있다.
7. `cloudfront.enabled=false`인 로컬과 CI에서는 기존 S3 사전서명 URL을 발급한다.

## 설계 결정

### D1. MP4 한 파일을 그대로 배포한다

현재 FFmpeg 산출물은 `+faststart`가 적용된 720p H.264와 AAC MP4다. CloudFront는 전체 파일과 Range
요청을 모두 캐시할 수 있으므로 HLS 변환을 추가하지 않는다. 영상 길이가 최대 30초인 현재 서비스에서
HLS는 재생 목록, 세그먼트 저장과 정리, 서명 쿠키 계약을 새로 만든다. MSG-67의 성능 검증에는 필요하지
않다.

### D2. CloudFront 서명 URL과 기존 10분 TTL을 쓴다

재생과 썸네일은 개별 객체 하나를 여는 요청이라 canned policy[^3] 기반 서명 URL을 쓴다. 기존 응답의
`expiresInSec=600` 계약을 유지한다. 애플리케이션은 AWS SDK for Java 2.x의 `CloudFrontUtilities`로
서명하고, 공개 키 ID와 PKCS#8 개인 키 파일을 설정으로 받는다.

업로드용 PUT 주소는 계속 `S3Presigner`가 만든다. CDN 설정이 꺼진 로컬과 CI에서도 같은 코드가 뜰 수
있도록 조회 URL만 기존 S3 서명으로 폴백한다.

### D3. S3 원본은 OAC로 닫는다

CloudFront는 Origin Access Control[^4]로 `fillmap-video-dev`의 영상 객체를 읽는다. 배포의 캐시 동작에는
신뢰 키 그룹을 연결해 모든 조회에 서명을 요구한다. 버킷 정책은 해당 배포 ARN의 `s3:GetObject`만
추가로 허용한다. 기존 `profiles/original/*`과 `missions/*` 공개 정책은 이 티켓에서 바꾸지 않는다.

### D4. 파생 파일 키를 인코딩 시도마다 다르게 만든다

현재 인코딩본, 블러본과 썸네일 키는 `videoId`만 포함해서 영상 교체 시 같은 키를 덮어쓴다. CDN이 이전
바이트를 캐시한 상태에서 같은 경로를 다시 요청하면 새 URL도 이전 영상을 받을 수 있다.

새 키에는 `originalS3Key`에서 결정적으로 만든 시도 ID를 붙인다.

```text
videos/encoded/{userId}/{videoId}/{attemptId}.mp4
videos/blurred/{userId}/{videoId}/{attemptId}.mp4
videos/thumb/{userId}/{videoId}/{attemptId}.jpg
```

`attemptId`는 JDK `UUID.nameUUIDFromBytes(originalS3Key UTF-8)`로 만든다. 새 원본이면 새 경로가 되고 같은
시도의 재실행이면 같은 경로라 재시도 업로드가 멱등[^5]이다. 교체 트랜잭션은 기존 원본과 함께 이전
인코딩본, 블러본, 썸네일 키를 잡아두고 커밋 후 삭제한다. DB에 남아 있는 구형 키는 그대로 읽을 수 있어
마이그레이션이 필요 없다.

### D5. dev 배포는 서울 엣지를 포함한다

dev 배포는 사용자 지정 도메인 없이 CloudFront 기본 도메인을 사용한다. 가격 등급은 한국 엣지를 포함하는
`PriceClass_200`으로 둔다. 캐시 정책은 쿼리 문자열, 쿠키와 요청 헤더를 캐시 키에서 제외하고 기본 및 최대
TTL을 600초로 둔다. 서명 파라미터가 사용자마다 달라도 객체 경로가 같으면 같은 캐시를 쓴다.

### D6. 이번에 넣지 않는 것

- HLS 변환과 적응형 화질
- `media.fillmap.kr` 사용자 지정 도메인과 ACM 인증서
- Origin Shield, WAF, 실시간 로그
- prod 영상 버킷과 prod CloudFront 배포
- CloudFront Functions 또는 Lambda@Edge

dev 실측에서 효과와 운영 형태를 확인한 뒤 prod 자원이 필요할 때 같은 구성을 복제한다.

## API 명세

신규 API와 필드는 없다.

### `GET /api/videos/{videoId}`

- 접근 판정 순서, 조회수 증가 조건과 응답 필드는 MSG-206 계약을 유지한다.
- `playbackUrl`은 블러본이 있으면 블러본, 없으면 인코딩본의 CloudFront 서명 URL이다.
- `thumbnailUrl`도 같은 서명기로 발급한다.
- `expiresInSec`은 URL이 있을 때 600, 없으면 null이다.
- CDN 비활성 환경에서는 두 URL 모두 기존 S3 사전서명 URL이다.

격자 영상, 도감, 행사 영상, 관리자 신고 확인 등 `ThumbnailUrlPresigner`를 공유하는 조회도 같은 규칙을
따른다. DTO의 문자열 필드와 FE 계약은 바뀌지 않는다.

## 설정과 인프라

### 애플리케이션 설정

```yaml
cloudfront:
  enabled: ${CLOUDFRONT_ENABLED:false}
  domain: ${CLOUDFRONT_DOMAIN:}
  key-pair-id: ${CLOUDFRONT_KEY_PAIR_ID:}
  private-key-path: ${CLOUDFRONT_PRIVATE_KEY_PATH:}
```

`enabled=true`이면 나머지 세 값이 비어 있을 때 기동을 실패시킨다. 개인 키 내용은 레포와 환경변수에
넣지 않고 EC2의 권한 600 파일로 둔다. dev systemd 환경에는 파일 경로만 넣는다.

### AWS 자원

- S3 오리진 `fillmap-video-dev`
- OAC 1개, 서명 동작 `always`
- RSA 2048 공개 키 1개와 신뢰 키 그룹 1개
- 표준 CloudFront 배포 1개, HTTPS 강제, GET과 HEAD 허용, IPv6 사용
- 사용자 지정 캐시 정책 1개, TTL 0/600/600초
- 버킷 정책에 배포 ARN 조건이 붙은 `s3:GetObject` 허용문 1개

CloudFront 배포와 버킷 정책을 먼저 반영한 뒤 애플리케이션 플래그를 켠다. 순서를 거꾸로 하면 아직
읽을 수 없는 CDN URL이 API에서 발급된다.

## 도메인 로직

1. 접근 권한과 처리 상태를 기존 순서로 판정한다.
2. 재생할 S3 키를 고른다.
3. CDN이 켜졌으면 `https://{domain}/{key}`를 10분 canned policy로 서명한다.
4. CDN이 꺼졌으면 기존 S3 GET 사전서명 URL을 만든다.
5. 키가 null이면 URL도 null이다.

서명 실패를 S3 URL로 조용히 우회하지 않는다. CDN을 켠 환경의 키 파일 손상이나 설정 오류는 배포
문제이므로 기동 단계에서 잡는다.

## 데이터 모델

엔티티와 Flyway 마이그레이션은 없다. `videos.encoded_url`, `blurred_s3_key`, `thumbnail_url`에는 계속 S3
키를 저장한다. 신규 인코딩부터 시도 ID가 포함된 경로를 쓰고 기존 행의 구형 경로도 읽는다.

## 계약 변경

Owner A와 맞닿는 서비스 인터페이스 변경은 없다. DTO 필드 이름과 타입도 그대로다. URL의 호스트와 서명
파라미터만 바뀐다.

## 테스트 시나리오

### 단위 테스트

- `null_key는_null_URL을_반환한다`
- `CDN이_꺼지면_S3_사전서명_URL을_반환한다`
- `CDN이_켜지면_CloudFront_도메인과_서명_파라미터를_반환한다`
- `같은_originalKey는_같은_파생키를_만든다`
- `다른_originalKey는_다른_파생키를_만든다`
- `교체_커밋_후_이전_파생파일을_모두_삭제한다`
- 기존 재생 접근 제어와 `expiresInSec` 테스트는 URL 문자열 생성 방식만 바꾸고 그대로 통과한다.

### dev 실측

1. 서명 없는 URL이 403인지 확인한다.
2. API가 발급한 URL로 전체 GET 200과 Range GET 206을 확인한다.
3. `X-Cache`가 첫 요청 Miss, 반복 요청 Hit로 바뀌는지 확인한다.
4. 세 파일마다 전체 GET과 1MiB Range GET을 콜드 1회, 웜 10회 실행한다.
5. S3 직접 요청과 CloudFront 요청의 TTFB 및 전체 시간 중앙값을 비교한다.
6. 영상 교체 후 신규 API URL의 경로가 바뀌고 새 바이트를 반환하는지 확인한다.

## 미해결 질문

없음.

[^1]: CloudFront 서명 URL은 애플리케이션이 만료 시각과 객체 경로를 개인 키로 서명한 주소다. CloudFront는 신뢰 키 그룹의 공개 키로 서명을 확인한 뒤 캐시나 S3 오리진에서 파일을 반환한다.
[^2]: Range 요청은 파일 전체가 아니라 지정한 바이트 구간만 받는 HTTP 요청이다. MP4 플레이어가 재생 시작이나 시킹 때 필요한 부분만 읽는 데 쓴다.
[^3]: canned policy는 객체 하나와 만료 시각만 담는 CloudFront의 짧은 서명 정책이다. 시작 시각이나 접속 IP 제한이 필요한 custom policy보다 URL이 짧고 현재 재생 계약에 맞다.
[^4]: Origin Access Control은 CloudFront가 S3에 보내는 요청을 AWS 서명으로 인증하는 설정이다. 버킷을 공개하지 않고 지정한 CloudFront 배포만 객체를 읽게 한다.
[^5]: 멱등은 같은 작업을 다시 실행해도 최종 결과가 한 번 실행한 것과 같은 성질이다. 여기서는 같은 인코딩 시도가 같은 S3 키를 써서 중복 실행이 파일을 늘리지 않는다.
