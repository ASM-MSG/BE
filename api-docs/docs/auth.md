# 인증

FillMap은 JWT 액세스 토큰과 리프레시 토큰 두 장으로 세션을 유지한다. 액세스 토큰은 1시간,
리프레시 토큰은 14일이고, 재발급할 때마다 리프레시가 회전하면서 14일이 다시 채워지는 슬라이딩
세션이다.

## 토큰 흐름

1. 로그인 계열 API(`POST /api/auth/login`, `POST /api/auth/oauth/{provider}`,
   `POST /api/auth/oauth/kakao/code`)가 액세스 토큰과 리프레시 토큰을 발급한다.
2. 클라이언트는 이후 요청마다 `Authorization: Bearer {accessToken}`을 붙인다.
3. 액세스가 만료돼 401 + `developCode` 2402를 받으면 `POST /api/auth/reissue`로 새 액세스 토큰과
   회전된 새 리프레시 토큰을 받는다. 이때 직전 리프레시 토큰은 즉시 무효가 된다.
4. 회전된 옛 리프레시 토큰을 다시 쓰면 탈취로 보고 그 세션 체인 전체를 폐기한다
   (401 + `developCode` 2433). 이 응답을 받으면 재로그인 화면으로 보낸다.
5. `POST /api/auth/logout`이 액세스 토큰을 무효화하고 리프레시 세션을 지운다.

### 웹과 앱의 리프레시 전달 방식

로그인 계열과 재발급 API는 `X-Client-Type` 헤더로 갈린다.

| 헤더 값 | 리프레시 토큰 전달 | 응답 본문의 `refreshToken` |
|---|---|---|
| `web` (기본) | 응답의 HttpOnly 쿠키 `refreshToken` | `null` |
| `app` | 응답 본문 | 실제 토큰 문자열 |

재발급에서 쿠키로 리프레시를 보내는 웹은 CSRF 방어 때문에 `X-Client-Type`이 필수다. 빠지면
400 + `developCode` 2434가 온다. 본문으로 보내는 앱은 생략해도 되고, 생략하면 web으로 취급한다.

`X-Device-Id`는 세션을 디바이스 단위로 가르는 값이다. 보내지 않으면 서버가 UUID를 만들어 응답
헤더 `X-Device-Id`로 돌려주므로, 클라이언트는 그 값을 저장했다가 이후 요청에 계속 실어 보낸다.

## 이메일 회원가입과 로그인

`POST /api/auth/signup`은 이메일, 비밀번호, 닉네임으로 계정을 만든다. 비밀번호는 영문과 숫자를
각각 하나 이상 포함한 8자에서 64자, 닉네임은 2자에서 20자다. 응답은 생성된 사용자 정보만 담고
토큰은 주지 않으므로 가입 직후 로그인을 이어서 부른다.

```bash
curl -X POST https://api.fillmap.kr/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@fillmap.dev",
    "password": "Fillmap1234",
    "nickname": "채우미"
  }'
```

`POST /api/auth/login`이 토큰을 발급한다. 응답의 `role`은 화면이 일반 사용자, 행사 운영자,
관리자 진입을 가르는 재료다.

```bash
curl -X POST https://api.fillmap.kr/api/auth/login \
  -H "Content-Type: application/json" \
  -H "X-Client-Type: app" \
  -H "X-Device-Id: 9f0c8f2e-1f0a-4d2b-9a1e-4c6c9a2b7d10" \
  -d '{
    "email": "user@fillmap.dev",
    "password": "Fillmap1234"
  }'
```

```json
{
  "developCode": 200,
  "message": "성공",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "role": "USER"
  }
}
```

이메일이나 비밀번호가 틀리면 401 + `developCode` 2411이 온다. 어느 쪽이 틀렸는지는 구분해서
알려주지 않는다.

## 소셜 로그인

세 경로가 있고 셋 다 같은 응답 모양을 돌려준다.

| 경로 | 쓰는 쪽 | 입력 |
|---|---|---|
| `POST /api/auth/oauth/{provider}` | 네이티브 SDK가 ID Token까지 받아오는 앱 | `idToken` |
| `POST /api/auth/oauth/kakao/code` | 콜백으로 인가 코드를 받는 웹 | `code`, `redirectUri` |
| `GET /api/auth/oauth/kakao/authorize` | 웹 로그인 시작점 | 쿼리 `redirectUri`, 선택 `state` |

웹 흐름은 진입점부터 시작한다. 클라이언트는 `GET /api/auth/oauth/kakao/authorize?redirectUri=...`로
이동만 하면 되고(`location.href`), 서버가 카카오 인가 URL을 조립해 302로 보내면서 같은 응답에
`OAUTH_NONCE` 쿠키를 10분짜리 HttpOnly로 심는다. 그래서 `scope=openid` 누락이나 nonce 누락이
구조적으로 불가능하고, 카카오 REST API 키가 클라이언트 코드로 나갈 일도 없다. 이 응답은
리다이렉트라 공통 응답 포맷을 쓰지 않는다.

콜백에서 받은 인가 코드는 `POST /api/auth/oauth/kakao/code`로 넘긴다. 서버가 카카오 토큰
엔드포인트를 호출해 ID Token을 받은 뒤 OIDC 경로와 완전히 같은 검증과 발급을 태운다. 진입점이
심은 `OAUTH_NONCE` 쿠키가 함께 와야 하고, 없으면 401이다. `redirectUri`는 인가 요청에 쓴 값과
글자까지 같아야 하며 카카오 콘솔 등록값과도 일치해야 한다.

앱은 네이티브 SDK가 교환까지 끝내므로 인가 코드 경로가 아니라 `POST /api/auth/oauth/{provider}`에
`idToken`만 보낸다.

주요 실패 코드는 2421(유효하지 않은 소셜 로그인 토큰), 2422(지원하지 않는 provider),
2423(유효하지 않은 인가 코드), 2502(제공자 오류, 재시도 가능)다.

## 토큰 재발급

```bash
curl -X POST https://api.fillmap.kr/api/auth/reissue \
  -H "Content-Type: application/json" \
  -H "X-Client-Type: app" \
  -H "X-Device-Id: 9f0c8f2e-1f0a-4d2b-9a1e-4c6c9a2b7d10" \
  -d '{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }'
```

웹은 본문 없이 쿠키만으로 부르되 `X-Client-Type: web`을 반드시 넣는다. 응답은 새 액세스 토큰과
회전된 리프레시 토큰이고, 웹이면 리프레시가 쿠키로 재설정되며 본문 값은 null이다.

이 API는 액세스 토큰이 만료된 상태에서 불리므로 인증이 필요 없다. 자격 증명 역할을 리프레시
토큰이 대신한다.

## 로그아웃

`POST /api/auth/logout`은 인증이 필요하다. `Authorization` 헤더의 액세스 토큰을 무효화하고
`X-Device-Id`가 가리키는 디바이스의 리프레시 세션을 지운다. `X-Device-Id`가 없으면 그 사용자의
모든 디바이스 세션을 지우는 전체 로그아웃이 된다. 본문에 `fcmToken`을 함께 보내면 푸시 토큰도
같이 정리한다.

## 보호된 API 호출

```bash
curl https://api.fillmap.kr/api/collections/summary \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

## 개발용 소셜 로그인 모의

`POST /api/auth/dev/social-login`은 실제 OIDC ID Token 검증 없이 `(provider, oid)`로 사용자를
찾거나 만들고 토큰을 발급한다. 리프레시는 앱 모드처럼 본문으로 내려온다. 로컬과 dev 프로파일에만
컨트롤러가 올라가고 prod에는 아예 존재하지 않는다.

```bash
curl -X POST https://api.fillmap.kr/api/auth/dev/social-login \
  -H "Content-Type: application/json" \
  -d '{ "oid": "dev-kakao-1", "nickname": "카카오테스터" }'
```

`provider`를 생략하면 KAKAO, `email`을 생략하면 `{oid}@dev.local`, `nickname`을 생략하면
`dev-{oid}`가 들어간다. 같은 `oid`로 다시 부르면 같은 사용자로 재로그인된다.

## 비밀번호 API

행사 운영자 계정 흐름에서 주로 쓰는 다섯 개다. 요청과 응답 스키마는 [API 레퍼런스](reference.md)를
본다.

| 엔드포인트 | 용도 | 인증 |
|---|---|---|
| `POST /api/auth/password/reset-request` | 재설정 링크 요청 | 불필요 |
| `POST /api/auth/password/reset` | 재설정 확정 | 불필요 |
| `GET /api/auth/password/status` | 강제 변경 상태 조회 | 필요 |
| `POST /api/auth/password/change` | 비밀번호 변경 | 필요 |
| `POST /api/auth/password/initial` | 초기 비밀번호 설정 | 필요 |

!!! note "초기 비밀번호"
    관리자가 발급한 계정은 초기 비밀번호를 설정하기 전까지 다른 API가 403 + `developCode` 2441로
    막힌다. 로그인 직후 `GET /api/auth/password/status`로 강제 변경 대상인지 확인한다.
