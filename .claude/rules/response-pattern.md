# Response & Exception Pattern

FillMap 백엔드의 공통 응답 · 예외 처리 규칙. 모든 API가 이 형식을 따른다.
소스: `com.msg.fillmap.response.*`, `com.msg.fillmap.global.*`

## 공통 응답 포맷 — `ApiResponseDto<T>`

모든 응답은 아래 3개 필드로 감싼다.

| 필드 | 타입 | 의미 |
|---|---|---|
| `developCode` | `Integer` | 앱 내부 코드 (성공 200, 도메인 에러는 4자리 — 아래 규칙) |
| `message` | `String` | 사용자/개발자용 메시지 |
| `data` | `T` | 실제 데이터 (에러 시 보통 `null`) |

HTTP 상태는 body에 넣지 않는다 — 클라이언트는 응답 status line에서 이미 받으므로 중복이고,
`HttpStatusCode` 타입 필드는 springdoc이 실제와 다른 boolean 객체 예시를 생성한다 (MSG-265).

## 성공 응답 — `SuccessResponse<T>`

컨트롤러는 `ResponseEntity`를 직접 만들지 않고 `SuccessResponse.of(data)`만 반환한다.

```java
@PostMapping("/signup")
public SuccessResponse<SignupResponseDto> signup(@Valid @RequestBody SignupRequestDto request) {
	return SuccessResponse.of(authService.signup(request));
}
```

- `SuccessResponse`는 `ResponseEntity<ApiResponseDto<T>>`를 상속하며 항상 HTTP 200 + `developCode 200`.
- 성공 시 별도 코드/메시지 지정 불필요.

## 에러 코드 — `ErrorCodeIfs`

모든 에러 코드 enum은 `ErrorCodeIfs`를 구현한다 (`getHttpStatus`, `getErrorCode`, `getMessage`).

- **공통 에러**: `com.msg.fillmap.response.ErrorCode`
  (`BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `INTERNAL_SERVER_ERROR`)
  - 성공(`developCode 200`, message "성공")은 에러 코드가 아니므로 `ErrorCode`에 두지 않고 `SuccessResponse`가 자체 상수로 보유한다.
- **도메인 에러**: 각 도메인 `exception/` 하위에 `XxxErrorCode` enum으로 정의
  (예: `auth/exception/AuthErrorCode`)

### developCode 네이밍 규칙

도메인별로 `developCode` 대역을 나눠 쓴다. **이 표가 대역 배정의 단일 정본이다** — 새 도메인은
대역을 쓰기 전에 이 표에 행을 추가하는 커밋을 먼저 넣는다 (병렬 레인이 같은 대역을 동시에 잡는
경합 방지 — MSG-178/185가 9400을 동시 점유했던 실측 사건, 2026-08-03):

| 대역 | 도메인 | 예시 |
|---|---|---|
| `4xx` / `5xx` | 공통(`ErrorCode`) | `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `INTERNAL_SERVER_ERROR` |
| `1xxx` | user | `1404 USER_NOT_FOUND` |
| `2xxx` | auth | `2401 INVALID_TOKEN`, `2422 UNSUPPORTED_PROVIDER` |
| `3xxx` | video | `3420 INVALID_VISIBILITY`, `3424 RECORDED_AT_IN_FUTURE` |
| `4xxx` | grid | |
| `5xxx` | search | `5502 SEARCH_PROVIDER_ERROR` |
| `6xxx` | region | |
| `7xxx` | badge | |
| `8xxx` | hotzone | `8400 INVALID_VIEWPORT` |
| `9xxx` | friend | `9400 SELF_FRIEND_REQUEST` (MSG-185) |
| `10xxx` | notification | `10400 INVALID_PLATFORM` (MSG-178 — 9xxx 선확정했으나 MSG-185 병렬 경합으로 이동) |
| `11xxx` | moderation | `11409 DUPLICATE_REPORT` (MSG-192) |
| `12xxx` | mission | `12400 INVALID_VIEWPORT` (MSG-398) |
| `13xxx` | event | `13404 EVENT_NOT_FOUND` (MSG-438 대역 배정 — 행사방 도메인. 439 조회·440 업로드·442 생명주기가 9개 상수를 채움, 조회 은닉 응답 포함) |

새 도메인 에러 enum 추가 시:
- 상수명은 `SCREAMING_SNAKE_CASE`
- `errorCode`는 위 표에서 비어 있는 대역을 배정받아 부여 (표 갱신 커밋 선행)
- `HttpStatus`와 사용자 메시지를 함께 지정

```java
@Getter
@AllArgsConstructor
public enum AuthErrorCode implements ErrorCodeIfs {

	INVALID_TOKEN(2401, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
	UNSUPPORTED_PROVIDER(2422, HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 provider 입니다"),
	;

	private final Integer errorCode;
	private final HttpStatusCode httpStatus;
	private final String message;
}
```

## 예외 던지기 — `ApiException`

비즈니스 예외는 `ErrorCodeIfs`를 감싼 `ApiException`으로만 던진다.
직접 `ResponseEntity`를 만들거나 raw `RuntimeException`을 던지지 않는다.

```java
private AuthProvider parseProvider(String provider) {
	try {
		return AuthProvider.valueOf(provider.toUpperCase());
	} catch (IllegalArgumentException e) {
		throw new ApiException(AuthErrorCode.UNSUPPORTED_PROVIDER);
	}
}
```

생성자 3종:
- `new ApiException(errorCode)` — 기본 메시지 사용
- `new ApiException(errorCode, "커스텀 메시지")` — 메시지 override
- `new ApiException(errorCode, cause)` — 원인 예외 체이닝

## 예외 → 응답 변환 — `GlobalExceptionHandler`

`@RestControllerAdvice`가 모든 예외를 `ApiResponseDto`로 변환한다.
컨트롤러/서비스에서 try-catch로 응답을 직접 만들 필요 없음 — 던지기만 하면 된다.

| 예외 | 처리 |
|---|---|
| `ApiException` | `errorCode`의 status/code/message로 변환 (커스텀 메시지 우선) |
| `MethodArgumentNotValidException` | `@Valid` 검증 실패 → `BAD_REQUEST` + 필드별 메시지 조합 |
| 그 외 `Exception` | `INTERNAL_SERVER_ERROR` (메시지 노출 안 함) |

## 요약 규칙

- 컨트롤러는 `SuccessResponse.of(...)` 반환만 — status/직접 조립 금지
- 비즈니스 실패는 `throw new ApiException(도메인ErrorCode)` 하나로
- 새 실패 케이스가 생기면 공통 `ErrorCode`가 아니라 **도메인 `XxxErrorCode`에 상수 추가**
- 응답을 컨트롤러/서비스에서 손으로 만들지 않는다 (핸들러가 전담)