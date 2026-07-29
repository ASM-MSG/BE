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

## Skills — 특정 워크플로우

- **prd-writer** — PRD(제품 요구사항 문서) 생성 (`docs/prd/*.md`), 티켓·스펙보다 선행
  트리거: "PRD 만들어줘", "요구사항 문서 정리해줘", "개발 전에 문서부터"
- **spec-writer** — 개발 스펙 문서 생성 (`docs/MSG-XXX.md`)
  트리거: "MSG-XX 스펙 만들어줘", "스펙 문서 정리해줘"
- **spec-driven-dev** — 스펙 기반 TDD 개발 (grid-developer/auth-developer/convention-reviewer 팀 조율)
  트리거: "MSG-XX 개발 시작", "스펙대로 개발해줘", "MSG-XX 이어서/다시 개발"

## 하네스: FillMap 개발 에이전트 팀

**목표:** MSG-XX 티켓 → 스펙 문서 → Owner A/B 도메인별 구현 → 컨벤션/계약 검증까지
에이전트 팀(spec-writer, grid-developer, auth-developer, convention-reviewer)이 처리.
에이전트 정의: `.claude/agents/`, 오케스트레이터: `.claude/skills/spec-driven-dev/`.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-07-05 | 초기 구성 (spec-writer, grid-developer, auth-developer, convention-reviewer 4개 에이전트 + spec-writer/spec-driven-dev 2개 스킬) | 전체 | Owner A/B 도메인 분리 협업 구조를 에이전트 팀으로 반영, 계약 인터페이스 경계면 검증 자동화 |
| 2026-07-22 | Phase 4에 Codex 교차 리뷰 단계 추가 (명시적 `codex-companion review`) | spec-driven-dev | Codex 스톱 게이트는 턴 단위 판정이라 서브에이전트가 짠 코드를 못 봄 — MSG-145에서 명시 리뷰가 실제 결함 3건 적발, 구현 완료 시점 상시 편입 |
| 2026-07-23 | Phase 3에 커밋 포인트 스톱 게이트 추가 (사용자 커밋 응답까지 새 단계 보류) | spec-driven-dev | 오토모드에선 백그라운드 알림이 턴을 이어가 커밋 포인트가 쌓임 — MSG-206에서 4개 유실, 커밋 타이밍 보존 위해 명문화 |
| 2026-07-24 | Agent 호출의 `model: "opus"` 강제 제거 (세션 모델 상속) | spec-driven-dev | 2026-07-21 Opus 스펙 분리 폐지 — 스펙·구현 모두 Fable 5 가능 확인, 낡은 강제 조항 정리 |
| 2026-07-26 | 에이전트 정의 4종 프런트매터의 `model: opus` 제거 | 전체 에이전트 | 호출부 제거(07-24)만으론 미완 — 정의 쪽 `model`이 남아 있으면 호출부 생략 시 정의 값이 적용돼 Opus 고정이 유지됨 (PR #61 리뷰 지적). 정의·호출부 모두 생략해야 세션 모델 상속 |
| 2026-07-28 | prd-writer 스킬 신설 (PRD 템플릿 포함), spec-writer가 PRD를 선행 입력으로 사용 | prd-writer, spec-writer | 멘토링 피드백 — 개발 착수 전 PRD(목적·기능·비기능·다이어그램·변경 파일) 단계 표준화 (MSG-256) |
| 2026-07-28 | spec-driven-dev 분해 — SKILL.md는 라우팅만, 커밋 게이트·Codex 리뷰·마무리는 references/ 3종으로, 팀 운영 원칙은 rules/subagent-orchestration.md로 승격. spec-writer 예시의 `model: "opus"` 잔재 제거 | spec-driven-dev, spec-writer, rules | 멘토링 피드백(07-28) — 스킬은 작게 쪼개고 서브에이전트 오케스트레이션은 규칙으로 명문화 (MSG-257) |
| 2026-07-29 | finalize.md에 PR 단계 추가 — 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 구조 강제 | spec-driven-dev | 파이프라인이 커밋 제안에서 끝나 PR 형식 규정이 부재 — 템플릿 미준수 반복 지적(PR #70), 세션마다 재발해 명문화 (MSG-200에 편승) |

## Quick Commands

```bash
./gradlew build              # 빌드
./gradlew build -x test      # 테스트 제외
./gradlew test               # 전체 테스트
./gradlew bootRun            # 실행
```

## 협업 원칙 (요약)

- **Owner A**: 지도 인프라 도메인 (`com.msg.fillmap.grid.*`, `com.msg.fillmap.region.*`, `com.msg.fillmap.search.*` — MSG-251 §D1)
+ **Owner B**: 콘텐츠/인증 도메인 (`com.msg.fillmap.user.*`, `com.msg.fillmap.video.*`, `com.msg.fillmap.auth.*`, `com.msg.fillmap.usergrid.*`, `com.msg.fillmap.badge.*` — MSG-239, `com.msg.fillmap.streak.*` — MSG-200)
- 두 도메인의 접점은 인터페이스로만 (`GridQueryService`, `UserGridQueryService` 등)
- 상세: `@.claude/docs/infrastructure.md`