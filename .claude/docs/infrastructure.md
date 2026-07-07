# Infrastructure & Package Structure

## 패키지 구조

```text
com.msg.fillmap
├── FillmapApplication.java
├── response/                     # 공통 응답 (모든 API 사용)
│   ├── ErrorCodeIfs             # 에러 코드 인터페이스
│   ├── ErrorCode                # 공통 에러 코드 enum (9xxx)
│   ├── ApiResponseDto           # 응답 DTO
│   └── SuccessResponse          # ResponseEntity 상속
│
├── global/
│   ├── exception/
│   │   └── ApiException         # RuntimeException wrapping ErrorCodeIfs
│   └── GlobalExceptionHandler   # @RestControllerAdvice
│
├── auth/                         # 인증 (Owner B)
│   ├── controller/
│   ├── service/
│   ├── oidc/                    # OIDC provider 추상화
│   ├── jwt/                     # TokenProvider
│   ├── dto/
│   └── exception/
│
├── user/                         # 사용자 (Owner B)
│   ├── entity/
│   ├── repository/
│   ├── service/
│   ├── controller/
│   ├── dto/
│   └── exception/
│
├── video/                        # 영상 (Owner B)
│   ├── entity/
│   ├── repository/
│   ├── service/
│   ├── controller/
│   ├── dto/
│   └── exception/
│
├── grid/                         # 격자 (Owner A)
│   ├── entity/
│   ├── repository/
│   ├── service/
│   │   ├── GridQueryService     # ← 인터페이스 (Owner B 소비)
│   │   ├── HotZoneService       # ← 인터페이스 (Owner B 소비)
│   │   └── impl/
│   ├── controller/
│   ├── dto/
│   ├── GridEncoder              # ← 순수 유틸
│   ├── GridConstants
│   └── exception/
│
├── region/                       # 행정동 (Owner A)
│   ├── entity/
│   ├── repository/
│   ├── service/
│   ├── controller/
│   ├── dto/
│   └── exception/
│
└── usergrid/                     # 개인 도감 (Owner B)
    ├── entity/
    ├── repository/
    ├── service/
    │   └── UserGridQueryService # ← 인터페이스 (Owner A 소비)
    ├── controller/
    ├── dto/
    └── exception/
```

## 도메인 오너십 원칙

- Entity 오너십은 한 팀원에게 명확히 (겹치지 않게)
- 다른 도메인의 Entity를 Repository로 직접 접근 X → **Service 인터페이스로만**
- 겹치는 지점은 Service 인터페이스로 계약

**계약 인터페이스 (변경 시 상대 팀원 확인 필수)**:
- `GridQueryService` — Owner A 제공
- `HotZoneService` — Owner A 제공
- `UserGridQueryService` — Owner B 제공
- `UserOidcCommandService` — Owner B 제공

## 로컬 DB (Docker)

PostgreSQL + PostGIS는 Docker로 로컬 실행. `application-local.yml` 참조.

### 실행

```bash
docker compose up -d          # postgres 컨테이너 기동
docker compose ps             # 상태 확인
./gradlew bootRun             # 앱 실행 (Flyway 자동 마이그레이션)
```

### 리셋 (스키마 다시 만들기)

```bash
docker compose down -v        # 볼륨까지 삭제
docker compose up -d
./gradlew bootRun
```

### 접속 정보

- Host: `localhost`
- Port: `5432`
- DB: `fillmap`
- User: `user`
- Password: `user1234`

## 환경 분리

| 환경 | DB | 배포 |
|---|---|---|
| local | Docker PostgreSQL | 로컬 실행 |
| dev | AWS RDS t4g.micro | main 브랜치 자동 |
| prod | AWS RDS t4g.small Multi-AZ | release tag 수동 승인 |

상세: `.claude/docs/deploy.md` (준비 예정)

## AWS 아키텍처 (Phase별)

**Phase 1 (지금)**
- Local Docker + Dev RDS만
- Single VPC · Single AZ

**Phase 2 (사용자 100명)**
- Prod RDS 추가 (Single AZ 우선)
- ElastiCache Redis 도입

**Phase 3 (5만 MAU)**
- Prod RDS Multi-AZ + Read Replica
- Auto Scaling Group

**Phase 4 (10만 MAU+)**
- ECS 전환
- Kafka Multi-AZ
- VPC 완전 분리 검토

아키텍처 다이어그램: `docs/momentmap-aws-v2.drawio`

## Flyway 마이그레이션

위치: `src/main/resources/db/migration/`

- `V1__init.sql` — 초기 스키마 (14 tables, 11 enums)
- 이후 변경은 반드시 `V{N}__{description}.sql` 로 추가
- **한 번 푸시된 V 파일은 절대 수정 금지** (checksum 불일치)

DBML 스키마: `docs/momentmap-schema.dbml`