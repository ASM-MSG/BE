# Architecture — 서비스 아키텍처 (SA · SysA v2 논리 뷰 정본 · 2026-07)

> ⚠️ **목표 설계 문서다.** 여기 서술된 9개 서비스 중 코드에 존재하는 것은 일부뿐이다.
> 실제 구현 현황은 `status.md` 참조 — 서비스가 코드에 있다고 가정하지 말 것.

FillMap 백엔드의 서비스 수준 아키텍처. 원본은 기획팀이 작성한 다이어그램 4종이며, 이 문서는
그 내용을 텍스트로 정리한 것이다:

1. **유즈케이스 다이어그램** — Actor별 기능(유즈케이스)과 include/extend 관계
2. **애플리케이션 아키텍처(엔드포인트 상세뷰)** — SA(기능 그룹) ↔ SysA(서비스) 사이 API 엔드포인트
3. **System Architecture v2 (논리 뷰)** — Client · Gateway · Service · Cache · Queue · Worker ·
   Scheduler · Data (정본). 역할만 표기하고 기술 선택·구현 상태는 SA v2와 CA v2가 담당한다
4. **User Journey** — Before/During/After (UX 보조자료, `.claude/docs/ia.md`)

**SysA v2(논리 뷰)가 정본(canonical)** 이고, User Journey는 UX 참고용 보조자료다.

**원본 다이어그램 (drawio 소스):** `.claude/docs/diagrams/` — 2026-08-06에 최신본으로 교체됐다.
- 유즈케이스 → `2026-07-21 0_FillMap_UseCase_v2_draft.drawio.xml`
- SA(애플리케이션 아키텍처) → `2026-07-21 3_FillMap_SA_v2_AppArch_draft.drawio.xml`
- SysA(System Architecture, 정본) → `2026-07-21 4_FillMap_SysA_v2_draft.drawio.xml`
- CA(Component Architecture) → `2026-07-21 5_FillMap_CA_v2_draft.drawio.xml`
- AI 파이프라인 → `2026-07-21 6_FillMap_AI_Pipeline_draft.drawio.xml` (신규)

> **버전 표기 주의**: 새 다이어그램은 전부 새로 그리면서 버전을 v2로 다시 매긴 것이다. 옛 파일의
> SysA v4·CA v3보다 낮은 번호지만 **이쪽이 최신**이다. 본문은 2026-08-06에 SysA v2 기준으로
> 맞췄다(서비스 9종·Worker/Cache 계층 분리·AI의 Worker 이동).

> 이 문서와 실제 코드가 다르면 **코드가 맞다.** 구현 현황의 단일 진실 원천은
> `.claude/docs/status.md`다. (예전 이 자리에 "Social·Notification·Moderation·광고는 미구현"이라
> 적혀 있었으나 `friend`·`notification`·`moderation`은 이미 구현됐다 — 광고만 여전히 없다.)

## Actor 3종 (표준)

| Actor | 설명 |
|---|---|
| 사용자 | 일반 사용자 (로그인·촬영·업로드·지도 탐색·도감·게임화·신고/차단) |
| 스폰서 (광고주·기업고객, 가게 주인) | 캠페인 관리, 스폰서 격자 지정, 성과 리포트 조회 (신규 갈래) |
| 운영자 (관리자) | 신고 처리, Trust Score 관리, 영상 강제 비공개, 사용자 정지·블랙리스트, 통계·모니터링 (Admin Zone) |

## 표준 서비스 9종

| 서비스 | 역할 |
|---|---|
| Auth | 로그인/회원가입, 카카오 OIDC, JWT 발급·Refresh Token 회전, 권한·위치 정보 |
| Video | 영상 촬영·업로드, Presigned URL, 메타데이터 저장, GPS 스푸핑 검증 |
| Grid | 격자 조회(viewport), 격자 자동 매핑, 핫존 랭킹(Redis ZSET), 태그 필터링 |
| Region | 행정동 역지오코딩, 탐험률(region_stats), 지역 탐색 |
| Collection | 개인 도감(점령 격자 조회), 뱃지 지급 로직, 랭킹 집계, 초대 코드/그룹 공유 |
| Mission | 축제·팝업·코스·테마 미션 조회, 스탬프 판정 (SysA v2 신규) |
| Social | 친구 관리, 친구 활동 알림, 친구 도감 조회 |
| Notification | 친구 활동·뱃지·랭킹·핫존 알림 Push (Firebase FCM) |
| Moderation | 신고 접수, 사용자 차단, 블랙리스트, 관리자 신고 처리 대시보드 |

**AI Highlight-Blur는 SysA v2에서 Service가 아니라 Worker 계층으로 이동했다** (아래 "Worker ·
Cache · Queue" 참조). 별도 Python FastAPI 서버로 도는 것은 그대로다.

이 저장소(Spring Boot)는 위 9개 서비스의 API 서버 역할을 하고(단일 Spring Boot 컨테이너),
AI만 별도 FastAPI 서버로 분리돼 있다 (`.claude/docs/infrastructure.md` 참조).

### SA(기능 그룹) ↔ SysA(서비스) 매핑

| SA3 기능 그룹 | SysA 서비스 |
|---|---|
| 인증·프로필 | Auth |
| 탐색 | Grid |
| 촬영·업로드 | Video |
| 도감 | Collection |
| 지역·탐험률 | Region |
| 미션·스탬프 | Mission |
| 소셜 | Social |
| 알림 | Notification |
| 신고·차단 | Moderation |
| AI | AI Highlight·Blur Worker (Service 아님) |

> SysA 다이어그램에서 각 서비스 카드 상단의 SA3 컬러 스트립이 위 매핑을 1:1로 표현한다
> (같은 색·이름 = 같은 서비스). SysA는 SA와 같은 시스템의 다른 줌 레벨이다.

### SA ③ 서비스 계약 ↔ SysA 서비스 (2026-08-07 추가)

SA의 ③열은 **코드의 계약 인터페이스**를 그리고 SysA는 **논리 서비스**를 그린다. 줌 레벨이
달라 개수가 어긋나 보이므로 대응을 여기서 못 박는다. SA 다이어그램의 각 칸에도 같은 값이
작은 글씨로 병기돼 있다.

| SA ③ 서비스 계약 | SysA 서비스 |
|---|---|
| `AuthService` · `UserService` | Auth |
| `GridQueryService` · `HotZoneService` | Grid |
| `VideoService` + 인코딩 워커 | Video |
| `RegionService` | Region |
| `UserGridQueryService` | Collection |
| `MissionService` | Mission |
| `SocialService` | Social |
| `NotificationService` (`NotificationCommandService`·`NotificationPreferenceService`·`PushTokenService`) | Notification |
| `ModerationService` | Moderation |

**`~QueryService` 접미사는 CQRS가 아니다.** Owner A와 Owner B의 도메인 경계를 넘는 호출을
인터페이스 하나로만 받으려고 둔 **계약 인터페이스**이고, 이름이 Query인 건 경계를 넘는 호출이
읽기뿐이기 때문이다(`GridQueryService`·`UserGridQueryService` — CLAUDE.md 협업 원칙).
쓰기 경로는 각 도메인 안에 있고 별도 Command 모델로 갈라 두지 않았다.

**API 경로는 전부 `/api` 접두사이고 버전은 붙이지 않는다** (컨트롤러 16개 전수 확인,
2026-08-07). 다이어그램 라벨도 이 규칙으로 통일했다. `/api/v1` 도입은 MVP 범위 밖이고,
넣으려면 컨트롤러와 FE 계약을 함께 옮겨야 한다.

### SysA — 서비스 ↔ 데이터 저장소 통신

각 서비스가 실제로 읽고 쓰는 저장소(SysA 다이어그램의 실선 = 실시간 요청/응답):

| 서비스 | PostgreSQL | Redis | S3 | 기술 |
|---|---|---|---|---|
| Auth | 세션·프로필 | JWT Refresh | — | Spring Boot |
| Grid | 격자 | Hot ZSET(핫존 랭킹) | — | Spring Boot |
| Video | 메타데이터 | — | 원본·인코딩본 | Spring Boot |
| Region | 행정동·region_stats | — | — | Spring Boot |
| Collection | 도감 | — | — | Spring Boot |
| Mission | 미션·스탬프 | Mission Cache(1h) | — | Spring Boot |
| Social | 소셜(친구·그룹) | — | — | Spring Boot |
| Notification | 알림 outbox | — | — | Spring Boot (FCM 외부 호출) |
| Moderation | 신고 | — | — | Spring Boot |
| AI Highlight·Blur (Worker) | — | — | 인코딩본 | Python FastAPI |

**Kafka 비동기 파이프라인** (점선 = 비동기): `Video → enqueue → Kafka → consume →
AI Highlight·Blur Worker → S3(인코딩본) → Video(처리 완료 응답)`.

## Worker · Cache · Queue (SysA v2에서 명시된 계층)

SysA v2는 서비스와 별개로 비동기 처리 계층을 갈라 그린다.

| 계층 | 구성 | 비고 |
|---|---|---|
| Message Queue | Event Queue | 업로드 이벤트를 워커로 넘긴다 |
| Scheduler | Batch Scheduler | 주기 실행 트리거 |
| Worker (이벤트 구동) | Encoding Worker, AI Highlight·Blur Worker | 업로드 이벤트로 깨어난다 |
| Worker (배치) | Badge·Streak Batch, Region Stats Batch, Mission Sync(축제·코스) | 스케줄러가 돌린다 |
| Cache | Token Cache, Hot Zone Cache, Mission Cache | 전부 Redis 한 인스턴스 위 논리 분리 |

> 코드에서는 배치 워커가 별도 프로세스가 아니라 같은 Spring Boot 안의 `@Scheduled`다
> (`WeeklySummaryScheduler`·`StreakRemindScheduler`·`NotificationRelay`·`HotZoneEntryDetector`).
> 다이어그램은 역할 분리를 그린 것이고 물리 배치는 CA v2를 따른다.

## Client Tier (클라이언트 4종)

| 클라이언트 | 대상 Actor | 기술 |
|---|---|---|
| Mobile App | 사용자 | iOS · Android (React Native) |
| Web Browser | 사용자 | React SPA |
| Sponsor Portal | 스폰서(광고주·기업고객) | 광고주 대시보드 (Web) |
| Admin Console | 운영자(관리자) | 운영자 대시보드 (Web) |

사용자는 인증 후 API를 호출한다(REST·실시간). Sponsor Portal은 Ad-Billing API로, Admin Console은
관리 API로 각각 Backend Service Cluster에 접속한다.

## 현재 패키지 구조와의 매핑

| SA 서비스 | 현재 패키지 (`infrastructure.md`) | 상태 |
|---|---|---|
| Auth | `auth` | 구현됨 (Owner B) |
| Video | `video` | 구현됨 (Owner B) |
| Grid | `grid`, `hotzone`, `search`, `zone` | 구현됨 (Owner A) |
| Region | `region` | 구현됨 (Owner A) |
| Collection | `usergrid`, `badge`, `streak` | 구현됨 (Owner B) |
| Mission | `mission` | 구현됨 (Owner B) |
| Social | `friend` | 구현됨 (Owner B — MSG-185·186·187) |
| Notification | `notification` | 구현됨 (Owner B — MSG-178~181, 313~315) |
| Moderation | `moderation` | 부분 — 신고 접수만(MSG-192), 관리자 처리 API 없음(MSG-195) |
| 광고주·기업고객 (스폰서 격자) | — | **미구현** (신규 도메인, 패키지 없음) |
| AI Highlight·Blur | — | 별도 리포지토리/서버 (FastAPI), 이 Java 리포지토리 범위 밖 |

> 남은 미구현은 **스폰서/광고 하나**다. 새로 들어올 때 Owner A/B 중 누가 맡을지 먼저 정하고
> `infrastructure.md`의 패키지 구조·계약 인터페이스 표에 추가한다. 패키지별 실제 진척은
> `status.md`가 정본이다.

## 인증 흐름

로그인/회원가입 → JWT 발급 → Refresh Token 회전 → 프로필 CRUD, 권한·위치 정보.
카카오 소셜 로그인(OIDC) 사용. 3rd-party: **Kakao OAuth**.

## 영상 촬영·업로드 흐름

**입력 소스 2종**:
- **갤러리 선택 업로드** — 기존 영상을 골라 업로드. 업로드 후 수정·삭제 자유.
- **카메라 촬영 업로드** — 앱 내에서 그 자리 촬영(자유 길이, 최대 30초). **사용자가 실제로 해당 격자에 있을 때만
  가능**(현재 GPS 위치 = 매핑 격자). 업로드 후 수정·삭제 자유.

> 카메라 촬영은 현장성이 핵심이라 위치 제약을 두고(GPS 스푸핑 검증), 갤러리 업로드는 위치 제약 없이
> 메타데이터의 좌표로 격자를 매핑한다.

영상 선택/촬영 → AI 하이라이트 확인 → 구간 조정 → 미리보기 → 공개 범위 선택 → 블러 결과 확인
→ (Video Service) Presigned URL 발급 → 메타데이터 저장 → 격자 자동 매핑(Grid Service 위임)
→ Rate Limiting → GPS 스푸핑 검증.

## AI Highlight-Blur Service (FastAPI, 단일 논리 서비스)

Video Service가 Presigned URL로 원본을 S3에 저장한 뒤, 인코딩본은 Kafka 비동기 파이프라인을 거쳐
AI 서버가 처리한다.

**실행 환경은 상시 FastAPI 서버로 확정** (MSG-143 ADR). Lambda·GPU는 채택하지 않았다.
EC2에 Docker 컨테이너로 배포하며 `c7g.medium`(1 vCPU / 2GB) 이상을 쓴다.

- **1080p 다운스케일 (필수 선행 단계)**: 입력을 1080p 30fps로 낮춘 뒤 처리한다.
  4K 원본을 그대로 넣으면 30초 영상에 9.7분이 걸리고 메모리가 1,152MB까지 오른다.
  추론이 어차피 `imgsz=640` 기준이라 정확도 손실 없이 3~4분으로 줄어든다 (MSG-142 실측)
- **민감정보 자동 블러**: YOLOv11n 얼굴 검출 → YOLOv11n 번호판 검출 → 마스킹 → FFmpeg 인코딩
  (모델 선정 근거는 MSG-144. Ultralytics가 AGPL-3.0이라 AI 서버는 별도 레포·별도 프로세스)
- **하이라이트 추천**: PySceneDetect(장면 전환 검출) 룰 기반. CLIP 프레임 스코어링·pHash는
  Phase 2로 미룸 — MVP는 룰 기반으로 확정(MSG-141)
- 비동기 워커: `asyncio → Kafka` (블러·인코딩·태깅을 큐로 처리, 실시간 요청/응답과 분리).
  처리에 3~4분이 걸리므로 동기 응답은 불가능하다

## 지도 탐색 · 핫구역

격자 조회(viewport) → 핫존 랭킹(Redis ZSET) → 태그·분위기 필터링 → 나/친구/전체/핫 탐색 모드 토글
→ 격자 상세 조회(대표 영상 재생·영상 목록·좋아요·신고 진입). 영상 신고는 격자 상세 조회를
«extend»하는 선택적 확장 유즈케이스다.

## 개인 도감 · 게임화

개인 도감 조회(3뷰: 지도·갤러리·뱃지) → 격자 점령·뱃지 → 수집률·스트릭 → 일일 도감 부스터 →
시즌 뱃지·입적 → 친구 초대 코드·소셜(그룹 공유). 뱃지 지급 로직·랭킹 집계는 Collection이 담당하되,
격자 매핑 자체는 Grid Service(`GridQueryService`) 소유이며 Collection은 이를 소비만 한다.

## 신고 · 관리자 도구 (Admin Zone)

- 사용자 측: 영상 신고 → 사용자 차단 → AI 블러 토글 → 정책·약관 노출
- 운영자 측(운영자 Actor 전용, Admin Console): 신고 처리(신고 접수) → Trust Score 관리 →
  영상 강제 비공개 → 사용자 정지·블랙리스트 → 통계·모니터링
  - Trust Score 관리는 신고 처리를, 사용자 정지·블랙리스트는 사용자 차단을 각각 «extend»하는
    확장 유즈케이스다.
- 공통: 권한 검증 · 감사 로그 · 일괄 처리

## 광고주·기업고객 (신규 갈래)

스폰서(가게 주인) 관점: 캠페인 관리 → 스폰서 격자 지정 → 성과 리포트 조회. 백엔드는
스폰서 격자 등록(가게·매장 정보·격자 지정) → 스폰서 격자 CRUD → 노출·클릭 집계 →
정산(PG 연동, 결제·정산은 외부 PG 서비스).

## 알림 · 스케줄러 · 배치 파이프라인

실시간 요청/응답과 분리된 비동기 처리 계층. Kafka 큐와 스케줄러 배치가 담당한다.

- **알림 전송 방식**: 기본은 Firebase FCM 푸시. 앱 포그라운드 실시간 알림용으로 **SSE(Server-Sent
  Events) 병행 검토 중** 🚧 (FCM = 백그라운드/디바이스 푸시, SSE = 접속 중 실시간 스트림).
- **알림 Push (Notification Service → Firebase FCM / SSE)**:
  - 친구 활동 알림 · 뱃지·랭킹 알림
  - **위치 기반 핫구역 알림** — 사용자 GPS가 핫한 구역 근처에 진입하면 "근처에 핫한 구역이 있어요"
    푸시 (탐색·방문 유도)
  - **리텐션 알림** — 사용자가 특정 격자를 다녀온 뒤 "○○ 다녀오셨나요?" 푸시로 영상 업로드·
    도감 채우기 유도
- **스케줄러 배치**:
  - 핫존 랭킹 집계 — 업로드·좋아요 이벤트로 Redis Hot ZSET 갱신
  - 뱃지 지급 배치 — 점령·수집률 조건 충족 시 뱃지 지급
  - 신고 SLA 감시 — 신고 접수 후 처리 지연 감시(운영자 알림 트리거)

## 데이터 계층

| 저장소 | 역할 |
|---|---|
| PostgreSQL + PostGIS | 정본 데이터 (users, videos, grids, user_grids 등), 공간 쿼리 |
| Redis (Hot ZSET Cache) | 핫존 랭킹, JWT Refresh 캐시 |
| S3 Storage | 영상 원본 · 인코딩본 · 블라인드 처리본 |

## 3rd-Party 연동

| 외부 서비스 | 용도 |
|---|---|
| Kakao OAuth | 소셜 로그인 (Auth) |
| Kakao Maps SDK | 지도 렌더링 (클라이언트) |
| Firebase FCM | Push 알림 (Notification, SSE 병행 검토) |
| PG (결제·정산) | 스폰서 격자 광고 결제·정산 |
| CloudWatch | 전 서비스 로그·메트릭 |
| Secrets Manager | DB·Key 관리 |
| AWS WAF | IP-Rate Limit·요청 제한 |

## 논리(SysA) ↔ 물리(CA) 대응 (2026-08-07 추가)

SysA는 역할만 그리므로 이름이 AWS 서비스명과 다르다. 헷갈리기 쉬운 것만 적는다.

| SysA (논리) | CA (물리) |
|---|---|
| API Gateway | **ALB (Active-Active) + AWS WAF** — AWS API Gateway가 아니다 |
| Service 9종 | Spring Boot API #1·#2 컨테이너 (단일 애플리케이션) |
| Message Queue (Event Queue) | Kafka (EC2) |
| Cache 3종 (Token·Hot Zone·Mission) | ElastiCache Redis 한 인스턴스 위 논리 분리 |
| Worker (이벤트·배치) | 같은 Spring Boot 안의 `@Scheduled`·컨슈머, AI만 FastAPI 서버 분리 |
| Main Database | RDS PostgreSQL + PostGIS (Primary·Standby) |
| Object Storage | S3 (원본·인코딩본) |

두 다이어그램 모두 해당 도형에 상대 쪽 이름을 병기해 뒀다.

## 다이어그램 범례 (참고)

- 실선: 실시간 요청/응답, 점선: 비동기·외부·조건부
- 색상: 노랑(UI 화면) · 회색(API 서버) · 보라(AI 서버) · 주황(외부 서비스) · 초록(Queue) ·
  파랑(알림 Push) · 빨강(관리자 도구)

## 관련 문서

- 화면 구조(IA) · User Journey: `.claude/docs/ia.md`
- AWS 물리 인프라(VPC·CI/CD·RDS): `.claude/docs/infrastructure.md`의 "AWS 인프라" 섹션
- 격자 시스템 상세: `.claude/docs/grid-system.md`
- 패키지 구조·오너십: `.claude/docs/infrastructure.md`
