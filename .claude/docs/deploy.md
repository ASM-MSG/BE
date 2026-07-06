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
| `KAKAO_CLIENT_ID` | 카카오 OIDC client-id |
| `REDIS_HOST` / `REDIS_PORT` | ElastiCache (Redis 활성화는 추후) |
| `SERVER_PORT` | 서버 포트 (기본 `8080`) |

운영 프로파일로 실행:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/msgbe-0.0.1-SNAPSHOT.jar
```

- `jpa.show-sql=false`, 로깅 `root=INFO`, `hibernate.SQL=WARN`.
- HikariCP 풀: max 20 / idle 5.

## DB 마이그레이션 (Flyway)

`application.yml`에 활성화돼 있어 앱 기동 시 자동 적용된다.

- 위치: `src/main/resources/db/migration/`, 파일명 `V{N}__{description}.sql` (현재 `V1__init.sql`).
- `validate-on-migrate=true`, JPA `ddl-auto=validate` — 스키마는 Flyway가 소유, JPA는 검증만.
- **한 번 푸시된 `V` 파일은 절대 수정 금지** (checksum 불일치로 기동 실패).
- 변경은 되돌리지 말고 **새 `V` 파일로 전진 수정**.

## 참고

- 패키지 구조·오너십·로컬 DB 상세: `.claude/docs/infrastructure.md`
- 빌드/실행 명령: `.claude/docs/project.md`
