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
│   ├── entity/                  # UserGrid 포함 (개인 도감 엔티티 — MSG-78 D6로 Owner A 소유 확정)
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
└── usergrid/                     # 개인 도감 조회 계약 (Owner B) — 엔티티는 grid/entity/UserGrid (Owner A, MSG-78 D6)
    ├── repository/
    ├── service/
    │   └── UserGridQueryService # ← 인터페이스 (Owner A 소비)
    ├── controller/
    ├── dto/
    └── exception/
```

## 도메인 오너십 원칙

- Entity 오너십은 한 팀원에게 명확히 (겹치지 않게)
- `user_grids` 엔티티(`grid.entity.UserGrid`)는 **grid 도메인(Owner A) 소유** (MSG-78 D6 확정)
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

## AWS 인프라 (목표 설계, MVP: Single VPC · Single AZ · Single Instance)

> 아래는 인프라 **목표 설계**다. `deploy.md`가 기술하는 "현재 저장소에 실제로 존재하는 것"
> (로컬 Docker + 프로파일 설정)과는 별개이며, 아직 이 저장소에 Dockerfile·CI 파이프라인은 없다.

**리전/네트워크**: `ap-northeast-2` (Seoul), VPC `10.0.0.0/16`, AZ `ap-northeast-2a` 단일.

| 영역 | 리소스 |
|---|---|
| Public Subnet | Internet Gateway, Route 53, CloudFront(영상 CDN), ALB, AWS WAF, NAT Gateway |
| Private Subnet · App Tier | Spring Boot API Server, Python FastAPI AI Server, Apache Kafka(비동기 파이프라인) |
| Private Subnet · Data Tier | RDS(Dev/Prod), ElastiCache Redis |
| CI/CD | GitHub Actions → ECR → Systems Manager Run Command |
| 관측/보안 | CloudWatch Logs+Metrics, Secrets Manager(`/dev/*`, `/prod/*`) |

### DB (RDS PostgreSQL + PostGIS)

| 환경 | 인스턴스 | 구성 | 비용 |
|---|---|---|---|
| Dev (`fillmap-dev`) | `db.t4g.micro` | Single AZ | ≈ $15/월 |
| Prod (`fillmap-prod`) | `db.t4g.small` | Single AZ → Multi-AZ(Phase 3) | Phase 2 ≈ $60/월 → Phase 3 $200+/월 |

### Redis (ElastiCache)

- 용도: 핫존 랭킹(Hot ZSET) 캐시, JWT Refresh 캐시
- **Prod 전용**. Dev는 로컬 Docker Redis 사용 (ElastiCache 미사용).

### 배포 파이프라인

```text
GitHub push → GitHub Actions (Build·Test·Push) → ECR → Systems Manager Run Command
  main 브랜치 push        → Dev 자동 배포
  release tag             → Prod 수동 승인 배포
```

### 외부 연동

Kakao OAuth / Kakao Maps SDK / Firebase FCM (Push) — 모두 VPC 외부, App Tier에서 직접 호출.

### Phase 로드맵

- **Phase 1 (지금)**: Local Docker + Dev RDS만, Single VPC·Single AZ
- **Phase 2 (사용자 100명)**: Prod RDS 추가(Single AZ), ElastiCache Redis 도입
- **Phase 3 (5만 MAU)**: Prod RDS Multi-AZ + Read Replica, Auto Scaling Group
- **Phase 4 (10만 MAU+)**: ECS 전환, Kafka Multi-AZ, VPC 완전 분리 검토

서비스 수준 아키텍처(SA): `.claude/docs/architecture.md`

## Flyway 마이그레이션

위치: `src/main/resources/db/migration/`

- `V1__init.sql` — 초기 스키마 (14 tables, 11 enums)
- 이후 변경은 반드시 `V{N}__{description}.sql` 로 추가
- **한 번 푸시된 V 파일은 절대 수정 금지** (checksum 불일치)

DBML 스키마: `docs/momentmap-schema.dbml`