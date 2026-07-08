# Architecture — 서비스 아키텍처 (SA, 정본 · System Architecture v4 · 2026-07)

> ⚠️ **목표 설계 문서다.** 여기 서술된 8개 서비스 중 코드에 존재하는 것은 일부뿐이다.
> 실제 구현 현황은 `status.md` 참조 — 서비스가 코드에 있다고 가정하지 말 것.

FillMap 백엔드의 서비스 수준 아키텍처. 원본은 기획팀이 작성한 다이어그램 4종이며, 이 문서는
그 내용을 텍스트로 정리한 것이다:

1. **유즈케이스 다이어그램** — Actor별 기능(유즈케이스)과 include/extend 관계
2. **애플리케이션 아키텍처(엔드포인트 상세뷰)** — SA(기능 그룹) ↔ SysA(서비스) 사이 API 엔드포인트
3. **System Architecture v4** — Client Tier · Backend Service Cluster · Data Tier (정본)
4. **User Journey** — Before/During/After (UX 보조자료, `.claude/docs/ia.md`)

**SA(System Architecture v4)가 정본(canonical)** 이고, User Journey는 UX 참고용 보조자료다.
이전 버전 SA1은 User Journey 보조자료로 격하, SA2는 폐기됨.

**원본 다이어그램 (drawio 소스):** `.claude/docs/diagrams/`
- 유즈케이스 → `0_FillMap_UseCase.drawio.xml`
- SA(Service Architecture) → `3_SA_v2.drawio.xml`
- SysA(System Architecture v4, 정본) → `4_FillMap_SysA_v4.drawio.xml`
- CA(Component Architecture) → `5_FillMap_CA_v3.drawio.xml`

> 이 문서와 실제 코드가 다르면 **코드가 맞다.** 특히 아직 패키지로 구현되지 않은 도메인(Social·
> Notification·Moderation·광고)은 "설계상 존재"일 뿐 구현 여부는 `.claude/docs/infrastructure.md`
> 패키지 구조를 기준으로 판단할 것.

## Actor 3종 (표준)

| Actor | 설명 |
|---|---|
| 사용자 | 일반 사용자 (로그인·촬영·업로드·지도 탐색·도감·게임화·신고/차단) |
| 스폰서 (광고주·기업고객, 가게 주인) | 캠페인 관리, 스폰서 격자 지정, 성과 리포트 조회 (신규 갈래) |
| 운영자 (관리자) | 신고 처리, Trust Score 관리, 영상 강제 비공개, 사용자 정지·블랙리스트, 통계·모니터링 (Admin Zone) |

## 표준 서비스 8종

| 서비스 | 역할 |
|---|---|
| Auth | 로그인/회원가입, 카카오 OIDC, JWT 발급·Refresh Token 회전, 권한·위치 정보 |
| Video | 영상 촬영·업로드, Presigned URL, 메타데이터 저장, GPS 스푸핑 검증 |
| Grid | 격자 조회(viewport), 격자 자동 매핑, 핫존 랭킹(Redis ZSET), 태그 필터링 |
| Collection | 개인 도감(점령 격자 조회), 뱃지 지급 로직, 랭킹 집계, 초대 코드/그룹 공유 |
| Social | 친구 관리, 친구 활동 알림, 친구 도감 조회 |
| Notification | 친구 활동·뱃지·랭킹·핫존 알림 Push (Firebase FCM) |
| Moderation | 신고 접수, 사용자 차단, 블랙리스트, 관리자 신고 처리 대시보드 |
| AI Highlight-Blur | 하이라이트 추천 + 민감정보 자동 블러 (FastAPI, 단일 논리 서비스) |

이 저장소(Spring Boot)는 위 서비스 중 **Auth·Video·Grid·Collection·Social·Notification·Moderation**의
API 서버 역할을 하고(단일 Spring Boot 컨테이너 내 7개 서비스), **AI Highlight-Blur는 별도 Python FastAPI
서버**로 분리되어 있다 (`.claude/docs/infrastructure.md` 참조 — App Tier에 Spring Boot API Server와
Python FastAPI AI Server가 나란히 배치).

### SA(기능 그룹) ↔ SysA(서비스) 매핑

| SA3 기능 그룹 | SysA 서비스 |
|---|---|
| 인증·프로필 | Auth |
| 탐색 | Grid |
| 촬영·업로드 | Video |
| 도감 | Collection |
| 소셜 | Social |
| 알림 | Notification |
| 신고·차단 | Moderation |
| AI | AI Highlight-Blur |

> SysA 다이어그램에서 각 서비스 카드 상단의 SA3 컬러 스트립이 위 매핑을 1:1로 표현한다
> (같은 색·이름 = 같은 서비스). SysA는 SA와 같은 시스템의 다른 줌 레벨이다.

### SysA — 서비스 ↔ 데이터 저장소 통신

각 서비스가 실제로 읽고 쓰는 저장소(SysA 다이어그램의 실선 = 실시간 요청/응답):

| 서비스 | PostgreSQL | Redis | S3 | 기술 |
|---|---|---|---|---|
| Auth | 세션·프로필 | JWT Refresh | — | Spring Boot |
| Grid | 격자 | Hot ZSET(핫존 랭킹) | — | Spring Boot |
| Video | 메타데이터 | — | 원본·인코딩본 | Spring Boot |
| Collection | 도감 | — | — | Spring Boot |
| Social | 소셜(친구·그룹) | — | — | Spring Boot |
| Notification | — | — | — | Spring Boot (FCM 외부 호출) |
| Moderation | 신고 | — | — | Spring Boot |
| AI Highlight-Blur | — | — | 인코딩본 | Python FastAPI |

**Kafka 비동기 파이프라인** (점선 = 비동기): `Video → enqueue → Kafka → consume →
AI Highlight-Blur → S3(인코딩본) → Video(처리 완료 응답)`. Spring Boot 7종 + FastAPI 1종이
Kafka로 영상/AI 파이프라인을 비동기 연결한다.

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
| Grid | `grid`, `region` | 구현됨 (Owner A) |
| Collection | `usergrid` | 구현됨 (Owner B) |
| Social | — | **미구현** (친구 관리 패키지 없음) |
| Notification | — | **미구현** (Push 발송 패키지 없음) |
| Moderation | — | **미구현** (신고/차단/관리자 도구 패키지 없음) |
| 광고주·기업고객 (스폰서 격자) | — | **미구현** (신규 도메인, 패키지 없음) |
| AI Highlight-Blur | — | 별도 리포지토리/서버 (FastAPI), 이 Java 리포지토리 범위 밖 |

> SA 다이어그램에는 있지만 아직 패키지가 없는 도메인(Social/Notification/Moderation/광고)은
> MSG-XX 티켓으로 들어올 때 Owner A/B 중 누가 맡을지 먼저 정하고 `infrastructure.md`의
> 패키지 구조·계약 인터페이스 표에 추가해야 한다.

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

- **민감정보 자동 블러**: YOLO 얼굴 검출 → 차량번호 검출 → 민감정보 마스킹 → FFmpeg 인코딩
- **하이라이트 추천**: PySceneDetect(장면 전환 검출) → CLIP 프레임 스코어링 → pHash 중복 탐지
- 비동기 워커: `asyncio → Kafka` (블러·인코딩·태깅을 큐로 처리, 실시간 요청/응답과 분리)

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

## 다이어그램 범례 (참고)

- 실선: 실시간 요청/응답, 점선: 비동기·외부·조건부
- 색상: 노랑(UI 화면) · 회색(API 서버) · 보라(AI 서버) · 주황(외부 서비스) · 초록(Queue) ·
  파랑(알림 Push) · 빨강(관리자 도구)

## 관련 문서

- 화면 구조(IA) · User Journey: `.claude/docs/ia.md`
- AWS 물리 인프라(VPC·CI/CD·RDS): `.claude/docs/infrastructure.md`의 "AWS 인프라" 섹션
- 격자 시스템 상세: `.claude/docs/grid-system.md`
- 패키지 구조·오너십: `.claude/docs/infrastructure.md`
