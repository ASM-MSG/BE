# Implementation Status — 지금 코드에 실제로 있는 것

> **이 문서가 구현 현황의 단일 진실 원천이다.** `infrastructure.md`·`architecture.md`는
> **목표 설계**를 present tense로 서술한다 — 거기 나온 패키지·서비스·인터페이스가 코드에
> 존재한다고 가정하지 말 것. 여기서 ✅로 표시된 것만 실제로 import·호출할 수 있다.
>
> 상태 기준: `feature/MSG-103-ai-harness` 브랜치 (2026-07-08).

## 도메인 패키지

| 패키지 | 상태 | 있는 것 | 없는 것 |
|---|---|---|---|
| `response` | ✅ 완성 | `ApiResponseDto`, `SuccessResponse`, `ErrorCode`, `ErrorCodeIfs` | — |
| `global` | ✅ 완성 | `ApiException`, `GlobalExceptionHandler`, `config/SecurityConfig` | — |
| `auth` (Owner B) | ✅ 완성 | `controller`, `service`(AuthService·OidcLoginService), `dto`, `jwt`(TokenProvider·필터·JwtProperties), `oidc`(Kakao OIDC), `exception/AuthErrorCode` | — |
| `user` (Owner B) | 🟡 부분 | `entity`(User·AuthProvider·UserRole), `repository/UserRepository`, `exception/UserErrorCode` | `service`, `controller`, `dto` |
| `grid` (Owner A) | 🟡 부분 | `GridEncoder`·`GridConstants`·`GridWkt`(순수 유틸), `entity/Grid`·`entity/UserGrid`(+`UserGridId` 복합키)·`repository/GridRepository`·`repository/UserGridRepository`·`service/GridOccupationService`(+impl)·`dto/OccupationResult` (MSG-68, v6) | `controller`, `GridQueryService`(read, MSG-73) |
| `usergrid` (Owner B) | ❌ 미생성 | — | 패키지 전체. `UserGridQueryService` 계약 포함 |
| `region` (Owner A) | ❌ 미생성 | — | 패키지 전체 (entity·repository·service·controller·dto) |
| `video` (Owner B) | ❌ 미생성 | — | 패키지 전체 (entity·repository·service·controller·dto) |

## 계약 인터페이스 (Owner A ↔ B 경계면)

`infrastructure.md`가 계약 인터페이스로 명시하지만 **아직 코드에 하나도 없다.** 새로 만들기 전엔
소비하는 쪽에서 import 불가.

| 인터페이스 | 제공자 | 상태 |
|---|---|---|
| `GridOccupationService` | Owner A | ✅ built (MSG-68 — 격자 write: 점령/재방문) |
| `GridQueryService` | Owner A | ❌ 미생성 (read, MSG-73) |
| `HotZoneService` | Owner A | ❌ 미생성 |
| `UserGridQueryService` | Owner B | ❌ 미생성 |
| `UserOidcCommandService` | Owner B | ❌ 미생성 |

## 스키마 vs JPA 엔티티

`V1__init.sql`은 14개 테이블을 정의하지만, JPA 엔티티는 2개만 존재한다.

| 테이블 | 엔티티 | 상태 |
|---|---|---|
| `users` | `user/entity/User` | ✅ (v6 ENUM→VARCHAR 정합: provider/role은 plain `@Enumerated(STRING)`. `grid_color` 컬럼은 엔티티에 아직 없음) |
| `user_grids` | `grid/entity/UserGrid` | ✅ (v6 복합 PK `@IdClass(UserGridId)`) |
| `grids` | `grid/entity/Grid` | ✅ (MSG-68 — `center_geom`/`bbox_geom`/`grid_y`/`grid_x` 미매핑, write는 native) |
| `videos` · `regions` · `region_stats` · `badges` · `user_badges` · `friendships` · `likes` · `push_tokens` · `reports` · `sponsor_ads` · `streaks` | — | ❌ 엔티티 없음 |

## 로드맵 / 백로그

티켓 시퀀싱·의존성·백로그는 **Jira MSG 프로젝트**가 단일 진실 원천이다. 이 문서는 *무엇이 빌드됐는지*만
기록하고 *무엇을 언제 할지*는 다루지 않는다.

## 유지 규칙

패키지나 계약 인터페이스가 planned → partial → built로 바뀌면 해당 행을 즉시 갱신한다.
(spec-driven-dev Phase 5 wrap-up에서 갱신 — 자세히는 해당 스킬 참조.)
