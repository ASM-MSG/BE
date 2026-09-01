# Deploy — 환경 · 설정

FillMap 백엔드의 실행 환경과 프로파일 구성. 현재 저장소에 **실제로 존재하는 것만** 기술한다.
(전용 CI/CD 파이프라인·Dockerfile은 아직 없음 — 로컬 실행과 프로파일 설정만 구성돼 있다.)

## 프로파일

`src/main/resources`에 존재하는 프로파일:

| 프로파일 | 파일 | 용도 |
|---|---|---|
| (공통) | `application.yml` | 공통 설정, `spring.profiles.active=local` |
| local | `application-local.yml` | 로컬 개발 (Docker DB 연결) |
| prod | `application-prod.yml` | 운영 (RDS + 환경변수 주입) |

- 기본 활성 프로파일: `local`.
- 별도 `dev` 프로파일 파일은 없음.

## 로컬 실행

```bash
docker compose up -d          # PostGIS 컨테이너(fillmap DB) 기동
./gradlew bootRun             # http://localhost:8080
```

- DB: `docker-compose.yml`의 `postgis/postgis:16-3.4-alpine`
  (DB `fillmap` / user `user` / pw `user1234`, port `5432`)
- 시크릿은 `application-local.yml`에 직접 기입돼 있음 (`jwt.secret`, 카카오 `client-id`).
- 리셋: `docker compose down -v && docker compose up -d` (볼륨 삭제 후 재기동).

## 운영(prod) 설정

`application-prod.yml`은 값을 하드코딩하지 않고 **전부 환경변수로 주입**한다.

| 환경변수 | 용도 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | RDS 접속 |
| `JWT_SECRET` | JWT 서명 키 (`application.yml` 주석 기준 운영 주입) |
| `JWT_REFRESH_SECRET` | JWT 리프레시 토큰 서명 키 — 기본값 없음 (MSG-135) |
| `KAKAO_CLIENT_ID` | 카카오 OIDC client-id |
| `REDIS_HOST` / `REDIS_PORT` | EC2 redis-prod 접속 (포트 기본 `6380`) |
| `REDIS_PASSWORD` | redis-prod requirepass — 기본값 없음, 미설정 시 기동 실패 (`ProdRequiredEnvValidator`, MSG-244 → MSG-260) |
| `S3_BUCKET_VIDEO` | prod 영상 S3 버킷 — 기본값 없음, 미설정 시 `AwsProperties @Pattern` 이 기동 실패시킴 |
| `CLOUDFRONT_ENABLED` | 영상 CDN 사용 여부. 운영에서는 `true` |
| `CLOUDFRONT_DOMAIN` | 영상 전용 도메인 `media.fillmap.kr` |
| `CLOUDFRONT_KEY_PAIR_ID` | 운영 CloudFront 공개 키 ID `K16XTNUYLRC55E` |
| `CLOUDFRONT_PRIVATE_KEY_PATH` | 운영 서명 개인 키 경로 `/home/ubuntu/fillmap-prod-cloudfront-private-key.pem` |
| `SERVER_PORT` | 서버 포트 (기본 `8080`) |

- 카카오 엔드포인트 두 개(`oauth.kakao.token-uri` 인가 코드 교환, `oauth.kakao.authorize-uri` 로그인 진입점의
  302 목적지 — MSG-345)는 issuer·jwk-set-uri 와 같은 공개 고정값이라 공통 `application.yml`에 있다.
  프로파일별 값도 환경변수도 없다.
- `oauth.kakao.nonce-cookie-secure`(같은 티켓)는 공통 `true` — nonce 쿠키를 `Secure; SameSite=None`으로 심는다.
  로컬만 `false`(`SameSite=Lax`, Secure 없음)로 덮는다. http://localhost 는 Secure 쿠키를 저장하지 않아
  켜두면 로컬 웹 로그인이 전부 401(2423)로 죽는다. 환경변수는 없다.

운영 프로파일로 실행:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/msgbe-0.0.1-SNAPSHOT.jar
```

- `jpa.show-sql=false`, 로깅 `root=INFO`, `hibernate.SQL=WARN`.
- HikariCP 풀: max 20 / idle 5.

### 운영 영상 CDN (MSG-495)

운영 영상은 `fillmap-video-prod` 버킷에 저장하고 `media.fillmap.kr`에서 CloudFront 서명 URL[^cdn-1]로
전송한다. CloudFront 배포 ID는 `E3RTGBXCIBKF2M`이며, S3 원본은 OAC[^cdn-2]를 통해
`videos/encoded/*`, `videos/blurred/*`, `videos/thumb/*`만 읽을 수 있다. 서명하지 않았거나 만료된
요청은 403이다.

`FillMapVideoUploadProd` IAM 정책은 `videos/*`의 PutObject, GetObject, DeleteObject만 허용한다. 아직
prod EC2 역할이 없으므로 정책은 어떤 역할에도 연결하지 않았다. 운영 인스턴스를 만들 때 해당 역할에만
연결하고, dev 역할인 `FillMapEc2DevRole`에는 연결하지 않는다.

운영 기동 시 위 CloudFront 변수와 `S3_BUCKET_VIDEO=fillmap-video-prod`를 함께 주입한다. 장애 시
`CLOUDFRONT_ENABLED=false`로 바꾸면 기존 S3 사전서명 URL로 돌아간다. DNS만 되돌릴 때는 Route 53의
`media.fillmap.kr` A와 AAAA 별칭 레코드[^cdn-3]를 제거한다. HLS, WAF, Origin Shield와 엣지 함수는
MSG-495 범위에 포함하지 않았다.

[^cdn-1]: CloudFront 서명 URL은 백엔드가 객체 경로와 만료 시각을 개인 키로 서명한 주소다. CloudFront는 운영 공개 키로 요청을 검증한다.
[^cdn-2]: Origin Access Control은 CloudFront의 S3 원본 요청을 AWS 서명으로 인증해 버킷을 공개하지 않게 한다.
[^cdn-3]: Route 53 별칭 레코드는 서비스 도메인을 CloudFront 배포에 연결하는 AWS 전용 DNS 레코드다.

## 관리자 계정 승격 (MSG-195)

관리자 계정을 만드는 코드·시드는 없다 (2026-08-06 확정). 관리자 API(`/api/admin/**`, ADMIN role
필수)를 쓸 계정은 기존 사용자를 DB에서 직접 승격한다:

```sql
UPDATE users SET role = 'ADMIN' WHERE id = {대상 id};
```

- **승격 후 재로그인(토큰 재발급)해야 반영된다** — role은 로그인 시 액세스 토큰 클레임에 실리므로,
  승격 전에 발급받은 토큰으로는 여전히 403이다.
- **강등은 역방향으로 지연된다**: `role = 'USER'`로 되돌려도 이미 발급된 액세스 토큰은 만료까지
  ADMIN으로 동작한다. 즉시 차단이 필요하면 토큰 만료를 기다리거나 리프레시 토큰을 무효화한다.

## S3 presign — AWS 측 전제 (콘솔 설정, 저장소 밖)

앱 설정(`AwsProperties`·`S3Config`)은 자격증명·버킷명만 안다. 아래는 **AWS 콘솔에만 존재하는 전제**라 여기 기록한다.

- **IAM (서명자 자격증명)**: presigned URL은 만든 자격증명의 권한으로 동작한다 — 권한 검사는 URL 사용 시점.
  필요 권한: `s3:PutObject`(업로드 presign, MSG-64) · `s3:GetObject`(썸네일 GET presign MSG-127/153 + headObject 실존 검증 MSG-132) · `s3:CopyObject`/`s3:DeleteObject`(pending→original 확정 복사·삭제 정리, MSG-133).
- **버킷 CORS**: 브라우저 직접 PUT(64)은 CORS 필수(이미 설정됨). 썸네일 GET은 `<img src>` 로드라 **CORS 불요** —
  FE가 fetch()/canvas로 다루게 되면 그때 GET 메서드 허용을 추가해야 한다.
- **Block Public Access**: ~~켜둔 채로 무관~~ → MSG-373부터 **버킷 수준 "공개 정책 차단"만 해제 필요**
  (아래 프로필 이미지 절). presigned URL 경로(영상 업로드·썸네일)는 서명된 인증 요청이라 여전히 무관.

### 프로필 이미지 (MSG-373) — 배포 전 콘솔 작업 2건

프로필 이미지는 `users.profile_image_url`에 **완성 공개 URL**을 저장한다(스펙 §D-1, 2026-08-11 정민 승인).
확정본 프리픽스가 익명 읽기로 열려 있어야 저장된 URL이 브라우저에서 그대로 열린다. 두 작업 모두
코드 밖(콘솔 전용)이라 여기 기록한다. **적용 전까지 이미지 등록은 되지만 표시가 안 된다**(403).

1. **`profiles/original/*` 공개 읽기 버킷 정책** — 대상은 이 프리픽스 하나뿐이다.
   `videos/*`·`profiles/pending/*`는 지금처럼 비공개 유지.

   ```json
   {
     "Sid": "PublicReadProfileOriginal",
     "Effect": "Allow",
     "Principal": "*",
     "Action": "s3:GetObject",
     "Resource": "arn:aws:s3:::{버킷명}/profiles/original/*"
   }
   ```

   전제: 버킷의 Block Public Access 4항목 중 **"새 퍼블릭 버킷 정책 차단(BlockPublicPolicy)"과
   "퍼블릭 정책이 있는 버킷 접근 차단(RestrictPublicBuckets)"을 버킷 수준에서 해제**해야 위 정책이
   저장·동작한다. ACL 관련 2항목은 켜둔 채 무관(정책 기반 공개라 ACL을 안 쓴다).
2. **`profiles/pending/` 라이프사이클 만료 규칙** — 확정되지 않은 업로드 자동 청소.
   `videos/pending/` 규칙과 같은 방식으로 프리픽스 필터만 다르게 추가한다(만료 기간도 동일하게).
3. **`missions/*` 공개 읽기 버킷 정책** (MSG-384) — 미션 대표 이미지도 `missions.image_url`에
   완성 공개 URL을 저장한다. 열어 두지 않으면 축제 461건에 **열리지 않는 주소를 채워 넣게 된다**(403).

   ```json
   {
     "Sid": "PublicReadMissionImages",
     "Effect": "Allow",
     "Principal": "*",
     "Action": "s3:GetObject",
     "Resource": "arn:aws:s3:::{버킷명}/missions/*"
   }
   ```

   전제는 1번과 같고 **MSG-373에서 이미 해제**돼 있다. dev와 prod가 서로 다른 버킷을 쓰므로
   **환경마다 따로 적용한다.** 적재 전에 객체 하나를 올려 실제로 열리는지 확인한다:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' "https://{버킷명}.s3.{리전}.amazonaws.com/missions/festival/{테스트키}"
   ```

   200이 아니면 시더를 돌리지 않는다. 403인 채로 적재하면 전량을 나중에 다시 손봐야 한다.
   dev 버킷은 2026-08-14 적용을 마쳤다(`PublicReadProfileOriginal`과 나란히 두 번째 Statement).
   prod 버킷은 아직이다.
4. **`event-locations/org-submission/*` 공개 읽기 버킷 정책** (MSG-500) — 이벤트 참여형 승인이
   커버 이미지를 이 프리픽스로 복사해 위치 목록 응답에 완성 공개 URL로 싣는다. 승인 미션
   이미지가 쓰는 `missions/org-submission/`은 위 3번의 `missions/*` 정책이 이미 덮으므로 별도
   조치가 없지만, 이 프리픽스는 **새 Statement가 필요하다**(3번과 같은 형태, Resource만
   `event-locations/org-submission/*`). 열어 두지 않으면 참여형 승인 위치의 imageUrl이 403이다.
   dev·prod 모두 미적용(2026-08-30 기준) — MSG-500 배포 전에 dev부터 적용하고 3번과 같은
   curl 검증을 거친다.

5. **`event-locations/seed/*` 공개 읽기 버킷 정책** (MSG-538) — 시드로 들어오는 이벤트 회차
   대표 이미지와 행사 위치 커버가 이 프리픽스에 올라가고, 회차 상세·위치 목록 응답이 조회 시점에
   공개 URL로 조립해 내보낸다. 4번과 프리픽스만 다른 형제라 **Resource를
   `event-locations/*` 하나로 잡으면 4번과 5번이 한 Statement로 함께 닫힌다** — 회차와 위치가
   프리픽스를 나누지 않고 같은 `event-locations/seed/`를 쓰는 이유도 정책을 쪼개지 않기 위해서다.
   **dev 적용 완료 (2026-09-01 — 객체 13개 전부 200 확인). prod 미적용.** 이 Statement 가
   `org-submission/*` 를 함께 열므로 **4번의 dev 몫도 이때 닫혔다**.

   ⛔ **prod 는 앱 배포보다 이 절차가 먼저다.** `application-prod.yml` 이 행사 시딩을
   (`fillmap.event.seed.enabled: true`) 켜 두고 있어 **기동하는 순간 시더가 이미지 키를 적재하고,
   그때부터 회차 상세·위치 목록 응답이 열리지 않는 주소를 담아 내보낸다**(화면에는 깨진 이미지로
   보인다). 정책과 객체를 먼저 올려 두면 첫 기동부터 정상이다. 순서가 뒤집혀도 **데이터를 다시
   손볼 필요는 없다** — 저장값이 완성 주소가 아니라 키라서, 나중에 정책·객체를 채우면 기존 행이
   그대로 살아난다(미션 이미지가 완성 URL 을 저장해 전량 재작업이 필요했던 3번과 다른 점이다).
   여기에 더해 아래 저작자 표시 경고를 prod 전에 반드시 닫는다.

   ⚠️ **폴더는 버킷 최상위에서 만든다.** 2026-09-01 dev 작업에서 `missions/` 안에 들어간 채로
   폴더를 만들어 13개가 `missions/event-locations/seed/` 로 올라갔다. 하필 `missions/*` 가 공개라
   그 경로는 200이 나오고 정작 필요한 경로는 403이었다. **익명 요청은 없는 객체에도 403을 주므로
   (ListBucket 권한이 없어 존재를 숨긴다) 403만으로는 "키가 틀렸다"와 "정책이 안 먹었다"를 구분할
   수 없다** — 콘솔에서 객체 URL 을 직접 읽어 경로를 대조하는 것이 유일하게 확실한 방법이다.

   **넣을 Statement** (4번과 합친 형태 — 콘솔의 버킷 정책 편집기에서 기존 `Statement` 배열에
   이 객체 하나를 추가한다. `{버킷명}`은 dev `fillmap-video-dev`, prod는 해당 환경 버킷):

   ```json
   {
     "Sid": "PublicReadEventLocationImages",
     "Effect": "Allow",
     "Principal": "*",
     "Action": "s3:GetObject",
     "Resource": [
       "arn:aws:s3:::{버킷명}/event-locations/seed/*",
       "arn:aws:s3:::{버킷명}/event-locations/org-submission/*"
     ]
   }
   ```

   **`event-locations/*` 한 줄로 줄이지 않는다.** Statement 하나로 4번과 5번을 함께 닫는 편의는
   Resource 배열로 그대로 얻으면서, 앞으로 이 접두사 아래에 생길 프리픽스가 자동으로 공개되는 것은
   막는다. 나중에 비공개 자료를 `event-locations/` 아래에 두는 순간 와일드카드가 그것까지 열어버린다.

   **올릴 객체 13장**은 레포 밖 로컬 산출물이다(`event-images/`, jpg 는 gitignore). 파일이 없으면
   `event-images/CREDITS.json`(이 파일만 커밋된다)의 `sourcePage` 를 열어 다시 받으면 된다 — 부산
   장소 일곱 장은 부산관광아카이브 공공누리 제1유형, 나머지 여섯 장은 위키미디어 공용이고, 항목마다
   원본 페이지 URL·라이선스·저작자가 적혀 있다.

   정책을 적용한 뒤, 업로드는 **`CREDITS.json` 에 적힌 열세 개만** 올린다. 폴더를 통째로 재귀 복사하면 그 폴더에
   섞여 들어온 파일까지 공개 버킷에 올라간다 — `event-images/` 는 gitignore 대상이라 리뷰를 거치지
   않는 자리다.

   ```bash
   cd "$(git rev-parse --show-toplevel)"
   python3 -c "import json;[print(i['file'].split('/')[-1]) for i in json.load(open('event-images/CREDITS.json'))['items']]" > /tmp/msg538-keys.txt \
     || { echo "목록 생성 실패 — CREDITS.json 을 확인한다"; exit 1; }
   [ "$(wc -l < /tmp/msg538-keys.txt)" -eq 13 ] || { echo "목록이 13개가 아니다"; exit 1; }
   while read -r f; do
     AWS_PROFILE=soma aws s3 cp "event-images/$f" "s3://fillmap-video-dev/event-locations/seed/$f" || {
       echo "업로드 실패: $f"; exit 1;
     }
   done < /tmp/msg538-keys.txt
   ```

   목록 생성이 실패하면 리다이렉션이 파일을 비워 놓으므로, 건수를 세지 않으면 뒤의 두 루프가
   아무 일도 안 하고 `OK 0 / FAIL 0` 으로 조용히 끝난다 — 그래서 13 을 확인하고 들어간다.
   목록을 파일로 받아 `while` 을 파이프 밖에서 도는 이유는 두 가지다. 파이프의 서브셸에서는
   `exit` 가 루프만 끝내고 스크립트는 계속 가고, 실패한 `cp` 를 그냥 지나치면 열세 개 중 몇 개가
   빠진 채로 "끝났다"고 보인다.
   프로파일을 안 주면 아래 경고대로 남의 계정이 잡혀 `AccessDenied` 로 떨어진다.

   **검증** — 한 장만 찍어 보면 나머지 열두 개가 빠졌는지 알 수 없다. 목록 전부를 돈다.
   `OK 13 / FAIL 0` 이어야 끝난 것이다.

   ```bash
   ok=0; fail=0
   while read -r f; do
     code=$(curl -s -o /dev/null -w '%{http_code}' \
       "https://fillmap-video-dev.s3.ap-northeast-2.amazonaws.com/event-locations/seed/$f")
     [ "$code" = "200" ] && ok=$((ok+1)) || { fail=$((fail+1)); echo "$code $f"; }
   done < /tmp/msg538-keys.txt
   echo "OK $ok / FAIL $fail"
   [ "$fail" -eq 0 ] || exit 1
   ```

   **403 은 원인을 하나로 말해 주지 않는다.** 위 경고대로 익명 요청은 없는 키에도 403 을 주므로,
   403 은 ⓐ 그 키에 객체가 없다(경로 오타·업로드 누락) ⓑ 정책이 저장되지 않았다 ⓒ Block Public
   Access 두 항목(1번 전제)이 켜져 있다 ⓓ 객체가 SSE-KMS 로 올라갔다 넷 중 무엇이든 될 수 있다.
   **HTTP 코드로 가르려 하지 말고 아래 순서로 좁힌다.**

   1. 일부만 403 이면 **프리픽스 정책은 정상이다**(나머지가 200 이므로). 그 파일들의 키를 콘솔에서
      대조하고, **키가 맞는데도 403 이면 그 객체의 암호화 유형을 본다** — 정책은 프리픽스 단위지만
      SSE-KMS 는 객체 단위라 같은 폴더에서도 한둘만 막힐 수 있다.
   2. 열세 개 전부 403 이면 콘솔에서 객체 하나의 **객체 URL** 을 읽어 기대 경로와 글자 단위로
      비교한다. 다르면 경로 문제이고, 같으면 정책·BPA·암호화를 본다.
   3. 자격증명이 있으면 `AWS_PROFILE=soma aws s3 ls s3://fillmap-video-dev/event-locations/seed/`
      한 번이 1·2 를 대신한다 — 객체 존재를 직접 보는 것이 가장 빠르다.
   ⚠️ **저작자 표시 의무가 미해결이다.** 열세 장 중 열한 장이 출처표시를 요구하는데(공공누리
   제1유형 일곱·CC BY 셋·CC BY-SA 하나) 화면에 표기할 자리가 없다. `CREDITS.json` 은 우리 쪽 추적 기록이지
   공개 표시가 아니라 의무를 대신하지 못한다. 그래서 **이 절차는 dev 까지다** — prod 적용과 대외
   노출(발표·데모 포함) 전에 표기 자리를 만들거나 표시 의무가 없는 사진으로 갈아타야 한다.
   결정 상태는 `docs/spec/MSG-538.md` 미해결 질문 1번에 있다.

   **순서가 어긋나도 이번 건은 피해가 작다.** 3번(미션)은 완성 URL 을 DB 에 저장해서 403 인 채로
   적재하면 전량을 다시 손봐야 했지만, 이벤트 이미지는 키만 저장하고 주소는 조회 시점에 조립하므로
   정책을 나중에 열어도 기존 행이 그대로 살아난다. 그래도 정책을 먼저 여는 것을 권한다 — 화면에
   깨진 이미지가 뜨는 구간이 없다.

### ⚠️ AWS 프로파일 — 로컬에 계정이 둘이고 기본값이 남의 계정이다

`~/.aws/credentials`에 프로파일이 둘 있는데 **기본값(`default`)이 FillMap 계정이 아니다.**

| 프로파일 | 계정 | 주체 | 이 레포와의 관계 |
|---|---|---|---|
| `default` | 438750401565 | `fillmap-local-dev` | **무관한 계정.** `fillmap-video-dev`에 아무 권한도 없다 |
| `soma` | 951142447485 | `fillmap-admin` | **이쪽이 FillMap이다.** 버킷·정책 모두 여기 있다 |

`AWS_PROFILE`을 안 주면 `default`가 잡혀 S3 호출이 전부 `AccessDenied`로 떨어진다. **그 실패는
권한 부족이 아니라 계정을 잘못 짚은 것**인데 오류 메시지가 똑같아서 IAM 정책을 고치러 가기 쉽다
(MSG-384에서 실제로 그렇게 오진했다). S3를 건드리는 스크립트·명령은 전부 `AWS_PROFILE=soma`로 돈다.

```bash
aws sts get-caller-identity --profile soma   # Account 951142447485 가 나와야 한다
AWS_PROFILE=soma aws s3 cp x.jpg s3://fillmap-video-dev/missions/festival/_healthcheck.jpg
curl -s -o /dev/null -w '%{http_code}\n' "https://fillmap-video-dev.s3.ap-northeast-2.amazonaws.com/missions/festival/_healthcheck.jpg"
```

앞의 것이 올려지고 뒤의 것이 200이면 쓰기와 읽기가 둘 다 열린 것이다. `fillmap-admin`은 관리자라
접두사별 IAM 권한을 따로 줄 필요가 없다.

## DB 마이그레이션 (Flyway)

`application.yml`에 활성화돼 있어 앱 기동 시 자동 적용된다.

- 위치: `src/main/resources/db/migration/`, 파일명 `V{N}__{description}.sql` (현재 `V1__init.sql`).
- `validate-on-migrate=true`, JPA `ddl-auto=validate` — 스키마는 Flyway가 소유, JPA는 검증만.
- **한 번 푸시된 `V` 파일은 절대 수정 금지** (checksum 불일치로 기동 실패).
- 변경은 되돌리지 말고 **새 `V` 파일로 전진 수정**.
- **CI가 강제한다** — 이미 적용된 V 파일을 수정한 PR은 빌드 실패 (`ci.yml` "Flyway V 파일 수정 검사").
  신규 V 파일 추가는 통과한다.

### 이 규칙을 어기면 무슨 일이 나는가 (실제 사고)

위 규칙은 원래도 이 문서에 있었다. 그런데 **MSG-66이 `V1__init.sql`을 v6 스키마로 재작성했고,
dev가 2026-07-14 00:28 ~ 07-15 약 32시간 죽었다** (재시작 6400여 회).

```text
Migration checksum mismatch for migration version 1
-> Applied to database : 1775598463     (2026-07-10 적용된 구 V1)
-> Resolved locally    : -590960112     (재작성된 V1)
Validate failed: Migrations have failed validation
```

앱이 아예 기동하지 못한다. **그리고 그동안 CD는 계속 초록불이었다** — 헬스체크가 없어서(MSG-129, 현재 수정됨).
규칙이 문서에만 있으면 안 지켜진다는 게 증명돼서 CI 검사를 넣었다.

### 복구 — dev

데이터를 버려도 되는 환경이므로 스키마를 비우고 다시 마이그레이션한다.

```bash
# 1. 백업 (되돌릴 수 있게)
docker exec fillmap-postgres-dev pg_dump -U dev -d fillmap > ~/fillmap-dev-backup-$(date +%Y%m%d-%H%M%S).sql

# 2. 크래시 루프 정지 (재시작 중 재생성 방지)
sudo systemctl stop fillmap-dev

# 3. 스키마 초기화
docker exec fillmap-postgres-dev psql -U dev -d fillmap -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO dev; GRANT ALL ON SCHEMA public TO public;"

# 4. 재기동 — Flyway가 새 V1을 처음부터 적용한다 (V1이 CREATE EXTENSION postgis 를 포함하므로 확장도 복구됨)
sudo systemctl start fillmap-dev

# 5. 확인
docker exec fillmap-postgres-dev psql -U dev -d fillmap -t -A -c \
  "SELECT version, checksum, success FROM flyway_schema_history;"
```

### 복구 — prod ⚠️

**`DROP SCHEMA`를 쓸 수 없다. 사용자 데이터가 있으면 사실상 답이 없다.**

- `flyway repair`는 **체크섬만 갱신**한다. 스키마는 구버전 그대로라 `ddl-auto=validate`에서 다시 터진다.
- 결국 구 스키마 → 신 스키마 차이를 **손으로 DDL을 써서** 맞춰야 하고, 그 사이 서비스는 죽어 있다.
- 즉 prod에서 이 사고가 나면 **정해진 복구 절차가 없다.** 이것이 V 파일을 고치면 안 되는 진짜 이유다.

### 재작성이 정말 불가피할 때

1. PR에 **`flyway-rewrite` 라벨**을 단다 (CI 검사가 건너뛴다)
2. 머지 **전에** 팀에 공지하고, 각자 로컬 DB를 정리하게 한다
3. 머지 **직후** 위 dev 복구 절차를 실행한다 — 자동화돼 있지 않으므로 **사람이 해야 한다**
4. prod가 이미 떠 있다면 하지 말 것

## 참고

- 패키지 구조·오너십·로컬 DB 상세: `.claude/docs/infrastructure.md`
- 빌드/실행 명령: `.claude/docs/project.md`
