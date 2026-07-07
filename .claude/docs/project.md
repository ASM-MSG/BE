# Project — FillMap Backend

## 개요

**FillMap** — 사용자가 방문한 장소를 5초 영상으로 기록하고, 지도 위 100×100m 정사각형 격자를 수집(개인 점령)하는 서비스.
확장 순서: Web → Android → iOS. 본 저장소는 백엔드(Spring Boot + PostgreSQL/PostGIS).

핵심 도메인 개념은 `.claude/rules/glossary.md` 참조 (격자 · 개인 점령 · 전역 격자 등록 · 도감 · 방문 등).

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 | Java 21 (toolchain) |
| 프레임워크 | Spring Boot 4.1.0 (Web MVC, Data JPA, Validation, Security) |
| DB | PostgreSQL 16 + PostGIS 3.4 |
| 공간 | Hibernate Spatial |
| 마이그레이션 | Flyway (`db/migration`) |
| 인증 | Spring Security + JJWT 0.13.0 + OAuth2 Resource Server (OIDC ID Token 검증) |
| 빌드 | Gradle (wrapper) |
| 로컬 인프라 | Docker Compose (postgis 컨테이너) |

## 빌드 · 실행

```bash
./gradlew build              # 빌드 (git hook 자동 설치 포함)
./gradlew build -x test      # 테스트 제외 빌드
./gradlew test               # 전체 테스트 (JUnit 5 + AssertJ)
./gradlew bootRun            # 앱 실행 (Flyway 자동 마이그레이션)
```

로컬 실행 절차:

```bash
docker compose up -d         # PostGIS 컨테이너 기동 (fillmap DB)
./gradlew bootRun            # http://localhost:8080
```

- 앱 포트: `8080`
- 활성 프로파일: `local` (기본, `application.yml`)
- 패키지 구조 · 오너십 · 로컬 DB 상세: `.claude/docs/infrastructure.md`

## 설정 · 시크릿

프로파일별 설정은 `src/main/resources/application*.yml`.

| 값 | local | 운영(prod) |
|---|---|---|
| DB 접속 | `application-local.yml` (Docker) | RDS + 환경변수 |
| `jwt.secret` | `application-local.yml` | `JWT_SECRET` 환경변수 |
| 카카오 `client-id` | `application-local.yml` | `KAKAO_CLIENT_ID` 환경변수 |

- `jwt.access-token-ttl`: `PT1H` (공통)
- `spring.jpa.hibernate.ddl-auto`: `validate` (스키마는 Flyway가 소유)

## 커밋 · 브랜치 규칙

- 커밋: `MSG-{번호} {타입}: {요약}` (git hook 강제, `core.hooksPath=.githooks`)
- 브랜치: `feature|fix|refactor|chore/MSG-{번호}/{설명}`
- 상세 컨벤션: `.claude/rules/project-conventions.md`

## 배포

환경 분리(local/dev/prod) 및 AWS 아키텍처 로드맵: `.claude/docs/deploy.md`