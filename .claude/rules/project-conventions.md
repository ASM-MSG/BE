# Project Conventions

FillMap 백엔드 프로젝트의 코딩 컨벤션. 모든 커밋에 강제 적용.

## Java 코딩 스타일

[네이버 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/) 준수.

- **들여쓰기**: 하드탭
- **중괄호**: K&R 스타일 (개행 문자 아님)
- **최대 줄 길이**: 120자
- **한 줄 하나의 문장**: 세미콜론 뒤 개행

## Import 순서

```text
static import
─ 빈 줄 ─
java.*
javax.*
─ 빈 줄 ─
org.*
─ 빈 줄 ─
lombok.*
─ 빈 줄 ─
com.*
```

각 그룹 사이에 빈 줄 하나. 알파벳 정렬.

## 네이밍

### DTO
- Request: `XxxRequestDto` (예: `UserSignupRequestDto`)
- Response: `XxxResponseDto` (예: `UserProfileResponseDto`)
- 클래스 → 파일명 완전 일치

### Entity / 도메인
- 클래스명: 명사 단수 (`User`, `Video`, `Grid`)
- 테이블명 (DB): 명사 복수 소문자 (`users`, `videos`, `grids`)

### 서비스 / 컨트롤러
- Service 인터페이스: `XxxService` (예: `GridQueryService`)
- Service 구현체: `XxxServiceImpl` (예: `GridQueryServiceImpl`)
- Controller: `XxxController` (예: `GridController`)

### Enum
- 클래스명: PascalCase (예: `VideoStatus`, `ProcessingStatus`)
- 상수명: SCREAMING_SNAKE_CASE (예: `ACTIVE`, `BLINDED`, `UPLOADED`)

## 커밋 메시지

git hook으로 강제됨. 형식:

```text
MSG-{번호} {타입}: {요약}
```

타입: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `style`

예시:
- `MSG-78 feat: GridEncoder 유틸리티 및 테스트 4건 추가`
- `MSG-86 fix: GIST 인덱스 누락 수정`
- `MSG-1 chore: PR 템플릿 추가`

## 브랜치 명명

`{타입}/MSG-{번호}-{짧은-설명}` — 티켓 번호와 설명은 **하이픈**으로 잇는다(슬래시 아님).

```text
feature/MSG-142-bench      # 기능
fix/MSG-86-gist-index      # 버그 수정
refactor/MSG-90-pagination # 리팩터링
chore/MSG-1-pr-template    # 설정/도구
```

작업은 **항상 새 브랜치에서 시작한다.** `develop`·`main`에 직접 파일을 만들거나
수정하지 않는다 — 문서(`docs/*.md`) 작업도 예외 없다.

## 테스트

- 프레임워크: JUnit 5 + AssertJ
- 파일 위치: `src/test/java/{패키지 미러링}/{클래스명}Test.java`
- 메서드명: 한국어 백틱 스타일
  ```java
  @Test
  void 강남역_좌표는_같은_격자로_매핑된다() { ... }
  ```

## 3-Layer MVC

```text
Controller → Service → Repository → DB
```

- Controller는 얇게. Request 파싱 + Service 호출 + Response 변환만.
- Service에 비즈니스 로직 집중.
- Repository는 JpaRepository 상속 + native UPSERT는 `@Modifying @Query nativeQuery = true`.

## Lombok 사용 원칙

- `@RequiredArgsConstructor` 로 DI (`@Autowired` 지양)
- `@Getter` 는 자유롭게, `@Setter` 는 지양 (불변성 우선)
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 로 JPA 요구사항 충족
- `@Builder` 는 필드 4개 이상일 때만