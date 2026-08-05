# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About FillMap

**FillMap** — 사용자가 방문한 장소를 짧은 영상(자유 길이, 최대 30초)으로 기록하고, 지도 위 약 100×100m 격자를 수집하는 서비스.
Web → Android → iOS 순서로 확장 예정. 백엔드는 Spring Boot + PostgreSQL(PostGIS).

## Read First — 항상 준수 (규칙)

작업 전 반드시 확인:

- `@.claude/rules/coding-principles.md` — 코딩 행동 원칙 (Karpathy 4원칙)
- `@.claude/rules/project-conventions.md` — 네이버 Java 컨벤션 · DTO 네이밍
- `@.claude/rules/response-pattern.md` — 공통 응답 · 예외 처리 패턴
- `@.claude/rules/subagent-orchestration.md` — 서브에이전트 팀 운영 원칙 (에이전트 팀 스킬 실행 시)

## Reference — 필요 시 참조 (문서)

- `@.claude/docs/status.md` — **구현 현황 (문서 중 먼저 확인)** · 코드에 실제 있는 패키지/인터페이스/엔티티
- `@.claude/docs/project.md` — 프로젝트 개요 · 기술 스택 · 빌드/실행
- `@.claude/docs/grid-system.md` — 100×100m 격자 시스템
- `@.claude/docs/infrastructure.md` — 패키지 구조 · 로컬 DB 세팅 · AWS 인프라
- `@.claude/docs/deploy.md` — 프로파일 · 환경변수 · 배포 설정
- `@.claude/docs/architecture.md` — 서비스 아키텍처(SA, 정본) · 8개 서비스 · AI Highlight-Blur
- `@.claude/docs/ia.md` — 화면 구조(IA) · User Journey · 구현 갭

## 팀 LLM 위키 (../LLM-WIKI)

스펙·스키마·ADR 작업 전 `../LLM-WIKI`의 `03-specs`/`04-decisions`를 grep으로 대조한다
(전체 탐색 금지, 타겟 조회만 — frontmatter의 keywords/aliases로 검색). 그 외에는 레포·Jira 우선,
불확실할 때만 위키 참조. 진입점: `index.md` · `hot.md` · 운영 규칙은 `00-meta/SCHEMA.md`.

## 개발 파이프라인 — PRD 필수

```text
아이디어/티켓 → PRD(docs/prd/*.md) → 스펙(docs/MSG-XXX.md) → 구현
                 ↑ 필수 게이트
```

**PRD 없이 스펙·구현에 착수하지 않는다.** PRD가 없으면 `prd-writer`를 먼저 실행한다 —
`spec-writer`·`spec-driven-dev` 양쪽 진입부에 게이트가 있고, **스펙이 이미 있어도 PRD가 없으면
통과가 아니다**(구 티켓 이어받기가 이 구멍으로 샌다).

**면제 기준 = "이 작업이 제품 요구사항을 새로 만들거나 바꾸는가" 하나다.** PRD는 요구사항
문서이므로, 요구사항이 그대로면 쓸 내용이 없다. 요구사항 불변 → 면제: 문서(`docs`), 리팩터링,
버그 수정(**기존** 요구사항의 복구 — 무엇이 옳은 동작인지는 이미 정의돼 있음), 성능 개선(요구사항
불변, 단 성능 목표 자체를 새로 세우면 요구사항 신설), 설정/의존성 갱신 중 요구사항에 안 닿는 것
(보안 패치 버전업·포맷). 반대로 기능 플래그 on·동작이 달라지는 업그레이드처럼 **설정·의존성이라도
요구사항을 바꾸면 면제가 아니다.** 면제로 판단하면 근거를 사용자에게 한 줄 알린다.
**새 기능·새 API·기존 동작의 의도적 변경 = 요구사항 변경 = 언제나 PRD 필수.**
(이 절이 면제 기준의 단일 정본 — 스킬·에이전트는 이 절을 참조한다)

## 작업 로그 — 런타임 동작 기록

구현을 마치면 스펙 문서(`docs/MSG-XXX.md`) 작업 로그에 diff 요약만 남기지 않는다.
**데이터 저장 위치**(테이블·Redis 키·S3 경로) · **실행 쿼리**(핵심 SQL 실측) ·
**예외 흐름**(어디서 던져져 어느 핸들러가 어떤 developCode로 변환) · **빈 동작**(생명주기·
스케줄러·트랜잭션 프록시 경계)을 해당되는 만큼 함께 기록한다. 상세 기준:
`.claude/skills/spec-driven-dev/references/finalize.md` 3번 — 스킬 밖 직접 작업에도 동일 적용.

## Skills — 특정 워크플로우

- **prd-writer** — PRD(제품 요구사항 문서) 생성 (`docs/prd/*.md`), **티켓·스펙보다 선행 · 필수 게이트**
  트리거: "PRD 만들어줘", "요구사항 문서 정리해줘", "개발 전에 문서부터"
- **spec-writer** — 개발 스펙 문서 생성 (`docs/MSG-XXX.md`)
  트리거: "MSG-XX 스펙 만들어줘", "스펙 문서 정리해줘"
- **spec-driven-dev** — 스펙 기반 TDD 개발 (grid-developer/auth-developer/convention-reviewer 팀 조율)
  트리거: "MSG-XX 개발 시작", "스펙대로 개발해줘", "MSG-XX 이어서/다시 개발"

## 하네스: FillMap 개발 에이전트 팀

**목표:** MSG-XX 티켓 → PRD → 스펙 문서 → Owner A/B 도메인별 구현 → 컨벤션/계약 검증까지
에이전트 팀(spec-writer, grid-developer, auth-developer, convention-reviewer)이 처리.
에이전트 정의: `.claude/agents/`, 오케스트레이터: `.claude/skills/spec-driven-dev/`.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-07-05 | 초기 구성 (spec-writer, grid-developer, auth-developer, convention-reviewer 4개 에이전트 + spec-writer/spec-driven-dev 2개 스킬) | 전체 | Owner A/B 도메인 분리 협업 구조를 에이전트 팀으로 반영, 계약 인터페이스 경계면 검증 자동화 |
| 2026-07-22 | Phase 4에 Codex 교차 리뷰 단계 추가 (명시적 `codex-companion review`) | spec-driven-dev | Codex 스톱 게이트는 턴 단위 판정이라 서브에이전트가 짠 코드를 못 봄 — MSG-145에서 명시 리뷰가 실제 결함 3건 적발, 구현 완료 시점 상시 편입 |
| 2026-07-23 | Phase 3에 커밋 포인트 스톱 게이트 추가 (사용자 커밋 응답까지 새 단계 보류) | spec-driven-dev | 오토모드에선 백그라운드 알림이 턴을 이어가 커밋 포인트가 쌓임 — MSG-206에서 4개 유실, 커밋 타이밍 보존 위해 명문화 |
| 2026-07-24 | 커밋 포인트 규칙 보강: 한 에이전트 런에 구현이 몰렸어도 레이어 단위 경로 스테이징으로 커밋 계획 분할 | spec-driven-dev | MSG-222에서 패키지 전체(17파일)를 1커밋으로 제안 → "잘게 해달라 했는데 왜 안 하냐" 재지적. 중간 포인트 부재 시 사후 분할을 명문화 (MSG-257 분해 후 references/commit-gate.md로 이동) |
| 2026-07-24 | Agent 호출의 `model: "opus"` 강제 제거 (세션 모델 상속) | spec-driven-dev | 2026-07-21 Opus 스펙 분리 폐지 — 스펙·구현 모두 Fable 5 가능 확인, 낡은 강제 조항 정리 |
| 2026-07-26 | 에이전트 정의 4종 프런트매터의 `model: opus` 제거 | 전체 에이전트 | 호출부 제거(07-24)만으론 미완 — 정의 쪽 `model`이 남아 있으면 호출부 생략 시 정의 값이 적용돼 Opus 고정이 유지됨 (PR #61 리뷰 지적). 정의·호출부 모두 생략해야 세션 모델 상속 |
| 2026-07-28 | prd-writer 스킬 신설 (PRD 템플릿 포함), spec-writer가 PRD를 선행 입력으로 사용 | prd-writer, spec-writer | 멘토링 피드백 — 개발 착수 전 PRD(목적·기능·비기능·다이어그램·변경 파일) 단계 표준화 (MSG-256) |
| 2026-07-28 | spec-driven-dev 분해 — SKILL.md는 라우팅만, 커밋 게이트·Codex 리뷰·마무리는 references/ 3종으로, 팀 운영 원칙은 rules/subagent-orchestration.md로 승격. spec-writer 예시의 `model: "opus"` 잔재 제거 | spec-driven-dev, spec-writer, rules | 멘토링 피드백(07-28) — 스킬은 작게 쪼개고 서브에이전트 오케스트레이션은 규칙으로 명문화 (MSG-257) |
| 2026-07-30 | **PRD 필수 게이트** — CLAUDE.md에 파이프라인 명문화 + spec-writer 절차 1번·spec-driven-dev Phase 0에 게이트 신설(면제 목록 포함) | CLAUDE.md, spec-writer, spec-driven-dev | prd-writer 신설(07-28) 이후에도 PRD는 "있으면 쓴다"였을 뿐 강제가 없어 MSG-234·239 외엔 PRD 없이 스펙부터 착수 — 특히 **스펙이 이미 있는 티켓**은 게이트를 아예 안 거쳤다. 요구사항 즉석 창작 방지 (MSG-261) |
| 2026-07-29 | finalize.md에 PR 단계 추가 — 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 구조 강제 | spec-driven-dev | 파이프라인이 커밋 제안에서 끝나 PR 형식 규정이 부재 — 템플릿 미준수 반복 지적(PR #70), 세션마다 재발해 명문화 (MSG-200에 편승) |
| 2026-08-03 | 작업 로그에 런타임 동작 기록 의무화 — 저장 위치·실행 쿼리·예외 흐름·빈 동작 4항목 (finalize.md 3번 상세) | CLAUDE.md, spec-driven-dev | 사용자 요청 — diff 요약만으론 실행 시점 사실(생성 SQL·핸들러 변환·빈 생명주기)이 안 남아 코드 재독으로도 복원이 어려움 |
| 2026-08-03 | Codex 리뷰 라운드 상한 확대 — 재리뷰 2회(총 3회) → 3회(총 4회) | spec-driven-dev (codex-review-loop.md) | 사용자 지시 — MSG-183이 4라운드에서 수렴한 실측 반영, 상한 도달 시 사용자 확인 절차는 유지 |
| 2026-08-05 | PRD·스펙 작성 원칙에 문체·각주 조항 신설 — korean-humanizer 기준(줄표 금지·읽히는 문장) + 전문 용어 3~7개 마크다운 각주, 작성 후 줄표 grep 자가검증. 기존 문서 소급 재작성은 안 함 | prd-writer, agents/spec-writer | 사용자 지시 2건(PRD 먼저, 이어서 스펙 포함) — 두 문서의 독자는 팀원·멘토라 외부 문서 성격인데 레포 관례(줄표·압축체)로 쓰여 왔음. MSG-313 PRD가 적용 선례 |

## Quick Commands

```bash
./gradlew build              # 빌드
./gradlew build -x test      # 테스트 제외
./gradlew test               # 전체 테스트
./gradlew bootRun            # 실행
```

## 협업 원칙 (요약)

- **Owner A**: 지도 인프라 도메인 (`com.msg.fillmap.grid.*`, `com.msg.fillmap.region.*`, `com.msg.fillmap.search.*` — MSG-251 §D1, `com.msg.fillmap.hotzone.*` — MSG-233)
- **Owner B**: 콘텐츠/인증 도메인 (`com.msg.fillmap.user.*`, `com.msg.fillmap.video.*`, `com.msg.fillmap.auth.*`, `com.msg.fillmap.usergrid.*`, `com.msg.fillmap.badge.*` — MSG-239, `com.msg.fillmap.streak.*` — MSG-200, `com.msg.fillmap.mission.*` — MSG-222, `com.msg.fillmap.notification.*` — MSG-178, `com.msg.fillmap.friend.*` — MSG-185)
- 두 도메인의 접점은 인터페이스로만 (`GridQueryService`, `UserGridQueryService` 등)
- 상세: `@.claude/docs/infrastructure.md`