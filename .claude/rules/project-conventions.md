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

### 제목 가독성 (2026-08-04 신설)

커밋 이력은 코드를 같이 안 본 사람(리뷰 추적·나중의 복기)이 읽는 1차 자료다. 제목을
**압축 명사 나열**로 쓰지 않는다:

- 제목 = **코드를 안 연 사람이 무엇이 바뀌었는지 아는 문장.** 동작·효과 중심으로 쓴다
  ("무효 토큰 자동 삭제" O, "deleteAllByTokens 신설" X). 가운뎃점(·) 나열 금지.
- 전문용어는 제목에서 흔한 말로 풀고, 정확한 클래스명·수치·근거는 **커밋 본문**(둘째 줄
  이하)에 적는다.
- 커밋당 한 관심사 원칙은 그대로 — 관심사가 여럿이라 제목이 나열이 되면 커밋을 쪼갠다.

```text
MSG-179 fix: 컨슈머 poll 튜닝·말폼드 비재시도 분류               (X) 나열 압축체
MSG-179 fix: 소비 지연 시 그룹 이탈 방지, 깨진 메시지는 즉시 폐기  (O) 읽히는 문장
```

### 본문은 기본적으로 쓰지 않는다 (2026-08-06 신설)

**제목 한 줄이 기본이다.** 본문은 제목으로 설명되지 않는 근거가 있을 때만 2~3문장 붙인다
(실측 수치, 기각한 대안처럼 나중에 복기할 값). 제목이 다 말하는데 본문을 덧붙이면 군더더기다.

본문을 길게 쓰고 싶어지면 먼저 **커밋에 관심사가 여럿 섞였는지 의심하고 쪼갠다.** 소제목·표·
여러 문단이 필요하다면 그건 커밋이 아니라 PR 본문이나 스펙 작업 로그에 갈 내용이다.

## 브랜치 명명

git flow 브랜치 타입만 쓴다. **커밋 메시지 타입(`feat`·`fix`·`chore`·`docs`…)을
브랜치 접두어로 가져다 쓰지 않는다** — `chore/`, `refactor/`, `docs/`는 git flow에 없다.

```text
feature/MSG-{번호}-{짧은-설명}   # 일반 작업 전부 (기능·수정·리팩터링·문서·설정)
hotfix/MSG-{번호}-{짧은-설명}    # 운영 긴급 수정
release/{버전}                   # 릴리스 준비
```

티켓 번호와 설명은 **하이픈**으로 잇는다(슬래시 아님).

```text
feature/MSG-142-bench                 (O)
feature/MSG-142/bench                 (X)  슬래시
chore/MSG-1-pr-template               (X)  git flow에 없는 타입
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