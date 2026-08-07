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
| `SERVER_PORT` | 서버 포트 (기본 `8080`) |

- 카카오 토큰 엔드포인트(`oauth.kakao.token-uri`, 웹 인가 코드 교환 — MSG-345)는 issuer·jwk-set-uri 와 같은
  공개 고정값이라 공통 `application.yml`에 있다. 프로파일별 값도 환경변수도 없다.
- `oauth.kakao.nonce-cookie-secure`(같은 티켓)는 공통 `true` — nonce 쿠키를 `Secure; SameSite=None`으로 심는다.
  로컬만 `false`(`SameSite=Lax`, Secure 없음)로 덮는다. http://localhost 는 Secure 쿠키를 저장하지 않아
  켜두면 로컬 웹 로그인이 전부 401(2423)로 죽는다. 환경변수는 없다.

운영 프로파일로 실행:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/msgbe-0.0.1-SNAPSHOT.jar
```

- `jpa.show-sql=false`, 로깅 `root=INFO`, `hibernate.SQL=WARN`.
- HikariCP 풀: max 20 / idle 5.

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
- **Block Public Access**: 켜둔 채로 무관 — presigned URL은 서명된 인증 요청이지 익명 공개 접근이 아니다.

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
