# 영상 업로드 흐름

영상 파일은 서버를 거치지 않고 클라이언트가 S3에 직접 올린다. 서버는 업로드 권한만 발급하고,
파일이 올라온 뒤에 메타데이터를 받아 격자를 매핑한다.

```text
1. POST /api/videos/presigned-url   업로드 URL과 s3Key 발급
2. PUT  {uploadUrl}                 S3에 파일 직접 업로드
3. POST /api/videos                 메타데이터 저장(업로드 확정), 이 시점에 점령 발생
4. (서버 내부)                       인코딩과 AI 처리
5. GET  /api/videos/{videoId}       재생 URL 조회
```

3번을 부르기 전까지 영상은 세상에 없는 것과 같다. 2번에서 끊긴 파일은 확정되지 않은 채 남는다.

## 1. presigned URL 발급

```json
POST /api/videos/presigned-url
{
  "extension": "mp4",
  "contentType": "video/mp4",
  "contentLength": 10485760
}
```

```json
{
  "uploadUrl": "https://bucket.s3.amazonaws.com/videos/pending/42/...",
  "s3Key": "videos/pending/42/550e8400-e29b-41d4-a716-446655440000.mp4",
  "expiresInSec": 600
}
```

위는 응답 봉투의 `data`만 보인 것이다. 확장자는 `mp4`(`video/mp4`)와 `mov`(`video/quicktime`)만 받고, 둘이 짝이 맞지 않으면 400 +
`developCode` 3415다. 크기 상한은 100MB이고 넘으면 3413이다. URL 유효 시간은 10분이다.
`purpose`는 생략하면 일반 업로드이고, 하이라이트 선분석용 원본만 `HIGHLIGHT_PREVIEW`로 발급받아
2GiB 상한을 적용받는다.

## 2. S3 PUT

`contentLength`와 `contentType`이 서명에 포함되므로, 선언한 값과 다른 크기나 타입으로 올리면
S3가 403으로 거절한다.

```bash
curl -X PUT "{uploadUrl}" \
  -H "Content-Type: video/mp4" \
  --upload-file ./clip.mp4
```

## 3. 업로드 확정

```json
POST /api/videos
{
  "s3Key": "videos/pending/42/550e8400-e29b-41d4-a716-446655440000.mp4",
  "lat": 37.5665,
  "lng": 126.9780,
  "durationSec": 15,
  "recordedAt": "2026-07-17T14:30:00Z",
  "visibility": "PUBLIC"
}
```

```json
{
  "developCode": 200,
  "message": "성공",
  "data": {
    "videoId": 1001,
    "gridId": "19422_9582",
    "processingStatus": "UPLOADED",
    "occupied": true,
    "newBadges": [
      { "badgeId": 1, "code": "EXPLORER_1", "name": "첫 발자국", "description": "첫 격자를 수집했어요", "iconUrl": null }
    ],
    "completedMissions": [
      { "missionId": 3, "title": "성수 골목 코스", "type": "COURSE" }
    ],
    "zoneName": null,
    "zoneCell": null,
    "regionName": "서울특별시 중구 명동"
  }
}
```

`lat`과 `lng`로 격자를 매핑하고, 그 격자에 내 첫 영상이면 점령이 일어나 `occupied`가 true다.
이미 점령한 격자면 false이고 재방문으로 집계된다. 표시명 세 필드의 뜻과 조립 규칙은
[격자와 표시명 계약](grid.md)에 있다. `durationSec`은 1초에서 30초이고 범위 밖은 검증에서 걸린다.

`visibility`를 생략하면 PUBLIC이다.

| 값 | 볼 수 있는 사람 |
|---|---|
| `PUBLIC` | 전원. 격자 대표 영상, 전역 목록, 탐색 집계에 잡힌다 |
| `FRIENDS` | 본인과 수락된 친구. 전역 노출 경로에는 잡히지 않는다 |
| `PRIVATE` | 본인만 |

비친구가 FRIENDS 영상 재생을 요청하면 PRIVATE 비소유자와 완전히 같은 403이 온다. 판정은 요청
시점 실시간이라 친구를 끊으면 다음 요청부터 막힌다.

### 확정 단계에서 서버가 보는 것

| 검사 | 실패 코드 |
|---|---|
| `s3Key`가 `videos/pending/{내 userId}/` 아래인가 | 3401 |
| 그 키가 S3에 실제로 있는가 | 3402 |
| 이미 확정에 쓴 키가 아닌가 | 3401 |
| 실제 파일 크기가 100MB 이하인가 | 3413 |
| 앞부분이 영상 컨테이너 구조인가 | 3428 |
| 좌표가 서비스 지역 안인가 | 3400 |
| `recordedAt`이 미래가 아닌가 | 3424 |
| `visibility`가 세 값 중 하나인가 | 3420 |

키 존재 확인이 없으면 파일을 올리지 않고 좌표만 찍어 격자를 점령할 수 있다. 확정 경로는
fail-closed라 여기서 거부되면 점령, 뱃지, 스트릭, 미션 스탬프가 하나도 남지 않는다. 성공하면
서버가 pending 키의 객체를 `videos/original/` 아래로 복사해 확정본으로 삼는다.

### 갤러리 선택과 카메라 촬영

서버 요청에는 촬영 소스를 구분하는 필드가 없다. 두 경우 모두 `lat`, `lng`, `recordedAt`을 같은
모양으로 보낸다. `recordedAt`이 과거인 것은 전부 통과하므로 갤러리에서 고른 예전 영상도 올라가고,
현재보다 미래인 값만 3424로 거절한다(5분 오차는 허용). 촬영 시점에 사용자가 실제로 그 격자에
있었는지 확인하는 일은 앱이 카메라 촬영 경로에서 GPS로 판정한다.

## 4. 인코딩과 AI 처리

확정 직후 상태는 `UPLOADED`다. 이후 서버 내부에서 상태가 전이한다.

```text
UPLOADED → ENCODING → BLURRING → READY
                 └──────────────→ READY   (AI 비활성 환경)
     어느 단계에서든 실패하면 FAILED
```

`BLURRING`은 얼굴과 번호판 블러 처리, 하이라이트 구간 추출을 맡는 AI 단계다. 비동기라 완료까지
시간이 걸리고, 실패나 타임아웃은 `FAILED`로 수렴한다. `READY`가 아닌 영상은 전역 대표 조회와
공개 목록에 노출되지 않는다. 클라이언트는 재생 조회 응답의 `processingStatus`로 상태를 본다.

## 5. 재생 조회

`GET /api/videos/{videoId}`가 표시용 메타와 재생본 presigned GET URL을 준다.

```json
{
  "videoId": 1042,
  "playbackUrl": "https://bucket.s3.amazonaws.com/...",
  "thumbnailUrl": "https://bucket.s3.amazonaws.com/...",
  "gridId": "19422_9582",
  "durationSec": 12,
  "processingStatus": "READY",
  "visibility": "PUBLIC",
  "status": "ACTIVE",
  "viewCount": 37,
  "recordedAt": "2026-07-20T18:03:11Z",
  "expiresInSec": 600,
  "zoneName": "서면",
  "zoneCell": "I-6",
  "regionName": "부산광역시 부산진구 부전1동",
  "highlights": [[0.0, 4.25], [12.0, 18.5]],
  "nickname": "busan.vlog"
}
```

`READY`가 아니면 `playbackUrl`과 `expiresInSec`이 null이고, 썸네일도 READY 이전에는 null이다.
`highlights`는 AI 추천 구간 `[[시작초, 끝초], ...]`로 최대 세 개이고 배열 순서가 추천 우선순위다.
추천이 없으면 빈 배열이 아니라 null이 온다. 비로그인도 부를 수 있으나 전체 공개 영상만 통과한다.
삭제되거나 타인에게 블라인드된 영상은 404, 비공개와 친구 공개는 403이다.

## 교체, 삭제, 공개범위 전환

`PUT /api/videos/{videoId}`는 기존 영상을 새 파일로 바꾼다. 좌표를 생략하면 격자를 유지한 채
파일만 갈고, 좌표를 보내면 기존과 같은 격자여야 한다. 다른 격자면 400 + `developCode` 3422다.
교체 직후 상태는 다시 `UPLOADED`가 되어 처리 파이프라인을 다시 탄다.

`DELETE /api/videos/{videoId}`로 삭제한다. 그 격자에 내 영상이 하나도 남지 않으면 점령이
롤백되어 색칠이 풀린다. 시간 제한은 없어 얼마가 지난 영상이든 지울 수 있다.
`PATCH /api/videos/{videoId}/visibility`는 본인 영상의 공개범위를 세 값 사이에서 바꾸고, 같은
값으로 다시 보내도 멱등하게 성공한다. 행사 영상은 공개 전제라 이 API가 3427로 거절한다.

## 하이라이트 선분석

`POST /api/videos/highlight-preview`는 업로드 확정 전에 원본의 추천 구간을 동기로 계산해 준다.
`purpose=HIGHLIGHT_PREVIEW`로 발급받아 올린 pending 키를 보낸다.

```json
{ "s3Key": "videos/pending/42/550e8400-e29b-41d4-a716-446655440000.mp4" }
```

응답의 `highlights`는 최대 세 구간이고 각 구간은 5초 이상, 시작점끼리 5초 이상 벌어진다. 빈
배열이면 추천할 구간이 없다는 뜻이라 추천 단계를 건너뛴다. 원본 길이에 따라 응답까지 수 초에서
수십 초가 걸린다(30초 1080p 기준 5초 내외).

!!! note "실패 시 폴백"
    3502(분석 서버 문제, 재시도 가능), 3429(선분석이 붐빔, 재시도 가능), 3426(원본 파일 불량,
    재시도 무의미), 3425(3분 초과), 3413(허용 크기 초과) 어느 쪽이든 사용자가 구간을 직접
    지정하는 경로로 넘긴다. 결과는 저장되지 않는 임시 값이고, 같은 키로 이어서 업로드 확정을
    부를 수 있다.

전체 필드 목록은 [API 레퍼런스](reference.md)를 본다.
