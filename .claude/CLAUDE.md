# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**FillMap** — 사용자가 방문한 장소를 5초 영상으로 기록하고, 지도 위 100×100m 정사각형 격자를 수집하는 서비스.
Web → Android → iOS 순서로 확장 예정. 백엔드는 Spring Boot + PostgreSQL(PostGIS).

## 빌드 및 실행

```bash
# 빌드
./gradlew build

# 테스트 제외 빌드
./gradlew build -x test

# 전체 테스트
./gradlew test

# 단일 테스트 클래스 실행
./gradlew test --tests "com.msg.fillmap.SomeTest"

# 애플리케이션 실행
./gradlew bootRun
```

## 기술 스택

- **Java 21**, **Spring Boot 4.1.0**
- **PostgreSQL + PostGIS** (100×100m 자체 정사각형 그리드 기반 공간 쿼리)
- **Lombok** — 보일러플레이트 제거
- JPA는 현재 `build.gradle`에서 주석 처리 상태 — DB 연결 설정 후 활성화 필요

## 격자 시스템

사용자 위치를 **100×100m 자체 정사각형 그리드**로 양자화한다. (
- 격자 첫 점령 시 색 채움 및 뱃지 지급
- 같은 격자 재방문(다른 시간대·날씨) 시 타임라인에 누적
- 영상 업로드 후 24시간 이내 교체 가능, 이후 삭제 시 격자 점령 롤백GeoHash는 격자 크기가 100m에 정확히 맞지 않아 미사용)
- 격자 좌표 계산 및 공간 쿼리는 PostGIS 활용

## 로컬 DB (Docker)

PostgreSQL + PostGIS는 Docker로 로컬 실행 후 RDS로 전환 예정.
`application.yml`에 DB 접속 정보 추가 후 `build.gradle`의 JPA 의존성 주석 해제 필요.

## 패키지 구조

```
com.msg.fillmap
├── response/           # 공통 응답 처리
│   ├── ErrorCodeIfs    # 에러 코드 인터페이스 — 도메인별 enum이 구현
│   ├── ErrorCode       # 공통 에러 코드 enum (4xx/5xx)
│   ├── ApiResponseDto  # 공통 응답 DTO { developCode, httpStatus, message, body }
│   └── SuccessResponse # extends ResponseEntity<ApiResponseDto<T>>
└── global/
    ├── exception/
    │   └── ApiException        # ErrorCodeIfs를 wrapping하는 RuntimeException
    └── GlobalExceptionHandler  # @RestControllerAdvice 전역 예외 처리
```

## 공통 응답 패턴

모든 API는 `ApiResponseDto<T>` 형식으로 응답한다.

**성공 응답** — 컨트롤러에서 `SuccessResponse.of(data)` 리턴:
```java
public SuccessResponse<UserResponseDto> getUser(@PathVariable Long id) {
    return SuccessResponse.of(userService.getUser(id));
}
```

**에러 코드 추가** — 도메인별 enum이 `ErrorCodeIfs`를 구현:
```java
@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCodeIfs {
    USER_NOT_FOUND(1404, HttpStatus.NOT_FOUND, "존재하지 않는 유저");

    private final Integer errorCode;      // 개발 정의 코드 (1xxx = User, 2xxx = Auth, ...)
    private final HttpStatusCode httpStatus;
    private final String message;
}
```

**예외 던지기**:
```java
throw new ApiException(UserErrorCode.USER_NOT_FOUND);
// 커스텀 메시지가 필요할 경우
throw new ApiException(UserErrorCode.USER_NOT_FOUND, "커스텀 메시지");
```

`GlobalExceptionHandler`가 `ApiException`을 캐치해 자동으로 `ApiResponseDto`로 변환한다.

## 코딩 컨벤션

[네이버 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/) 준수.
- 들여쓰기: **하드탭**
- 중괄호: **K&R 스타일**
- import 순서: `static` → `java.*` → `javax.*` → `org.*` → `lombok.*` → `com.*`
- DTO 네이밍: `XxxResponseDto`, `XxxRequestDto`