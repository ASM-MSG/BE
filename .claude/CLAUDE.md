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
- `@.claude/rules/agent-response-contract.md` — 에이전트 응답 컨트랙트 (작업 마무리 응답 형식 · 카나리)
- `@.claude/rules/subagent-orchestration.md` — 서브에이전트 팀 운영 원칙 (에이전트 팀 스킬 실행 시)

## Reference — 필요 시 참조 (문서)

- `@.claude/docs/status.md` — **구현 현황 (문서 중 먼저 확인)** · 코드에 실제 있는 패키지/인터페이스/엔티티
- `@.claude/docs/project.md` — 프로젝트 개요 · 기술 스택 · 빌드/실행
- `@.claude/docs/grid-system.md` — 100×100m 격자 시스템
- `@.claude/docs/infrastructure.md` — 패키지 구조 · 로컬 DB 세팅 · AWS 인프라
- `@.claude/docs/deploy.md` — 프로파일 · 환경변수 · 배포 설정
- `@.claude/docs/architecture.md` — 서비스 아키텍처(SysA v2 정본) · 9개 서비스 · Worker·Cache 계층
- `@.claude/docs/ia.md` — 화면 구조(IA) · User Journey · 구현 갭
- **피그마 디자인 정본** — fileKey `CpqOlgayviFOG0WXTBUfpp`, 최신 정본 2개 (2026-08-14 성민 확인)
  - 웹 `14599:3501` "필맵 웹 디자인 ver 13_통합 버전" (1440×900)
  - 앱 `14176:6312` "필맵 앱 디자인 MVP ver 5" (390×844)

  화면이 있는 작업에서 Figma MCP로 **해당 화면 노드만** 조회한다. 페이지를 통째로 열면 응답이
  18만 자를 넘는다. `get_metadata`를 fileKey만으로 부르면 **앱 페이지가 목록에 안 잡히니**
  위 노드 ID를 직접 넘긴다. 서버 내부 작업(로깅·성능·마이그레이션)은 해당 없음

## 팀 LLM 위키 (../LLM-WIKI)

**아래 네 경우엔 착수 전에 조회한다.** 스스로 "불확실한가"를 따지지 말고 해당하면 기계적으로 본다:

1. 스펙·PRD·ADR 문서를 새로 쓰거나 고칠 때 → `03-specs` · `04-decisions`
2. DB 스키마나 API 계약을 바꿀 때 → `03-specs` (DB Schema · API 명세 · FE 계약)
3. "왜 이렇게 정했나"를 문서·PR·티켓에 쓸 때 → `04-decisions` (ADR에 기각된 대안까지 있다)
4. 레포와 Jira를 찾았는데 근거가 안 나올 때 → `index.md`부터

**위키에만 있는 것**: 결정의 근거와 기각 대안, FE·AI·디자인과의 계약(격자 계약·zone 표시명 등),
기획 확정 이력과 확정일(`02-planning`), 회의·멘토링 기록(`05-meetings`). 레포 `docs/`에는 결론만
남고 그렇게 정한 이유는 위키에 있다.

**레포가 정본이라 위키로 확인하지 않는 것**: 지금 코드에 무엇이 있는지
(`.claude/docs/status.md`), 컨벤션(`.claude/rules/`), 커밋 이력. 위키의 "API 명세 v1"이 스스로를
구현 기준이라 적고 있어도 **구현 사실은 status.md가 이긴다** (위키는 작성 시점 스냅숏이다).

문서 종류가 아니라 **읽는 목적**으로 가른다. 스펙을 **쓸 때**는 기존 결정과 부딪히는지 보러 위키를
조회하고(조건 1), 이미 쓰인 `docs/spec/MSG-*.md`의 **구현 지시를 따를 때**는 레포가 정본이다.

조회는 **타겟 grep만** 한다(전체 탐색 금지). frontmatter의 `keywords`/`aliases`에 한·영 동의어가
들어 있으니 그것으로 검색한다. 진입점: `index.md`(전체 지도) · `hot.md`(최근 작업) ·
운영 규칙 `00-meta/SCHEMA.md`.

**어긋날 때는 무엇이 어긋났는지로 가른다.**

- **검증 가능한 사실**(어떤 API가 있나, 어떤 컬럼이 있나, 무엇이 구현됐나): 코드와
  `status.md`가 이긴다. 물어볼 것 없이 그쪽을 따르고 위키가 낡았다는 점만 알린다.
  위 "API 명세 v1" 사례가 여기 해당한다.
- **결정·정책**(무엇을 하기로 했나): 확정일로 최신을 가린다. 어느 쪽이 새것인지 분명하면 그것을
  따르고 사용자에게 알린다. 날짜가 없거나 우열을 못 가리면 양쪽을 병기해 사용자 판단으로 올린다 —
  이 경우에만 임의로 한쪽을 고르지 않는다.

## 개발 파이프라인 — PRD 필수

```text
요구 발생 → SRS 등재(docs/srs.md) → PRD(docs/prd/*.md) → 스펙(docs/spec/MSG-XXX.md) → 구현
              ↑ 소프트 (PRD 리서치에 편입)   ↑ 필수 게이트
```

**`docs/srs.md`는 요구사항의 전역 정본이다** (2026-08-10 신설, MSG-358). PRD는 기능 하나의
스냅숏이라 "지금 서비스 요구사항 전체가 뭐냐"에 답하지 못한다. SRS는 전역 1개 living document로
요구마다 불변 ID(`FR-{영역}-{번호}`·`NFR-{분류}-{번호}`)를 붙이고, PRD·티켓·테스트가 그 ID를
참조해 추적성을 만든다. 등재·갱신·조회는 `srs-writer` 스킬이 맡는다.

**SRS는 소프트 게이트다** — 등재가 PRD 착수의 차단 조건은 아니고, prd-writer 리서치 단계에서
대조하고 신규 요구면 등재한다. 스톱 게이트를 하나 더 만들지 않는 이유는 요구사항을 새로 만드는
작업이 어차피 PRD 게이트를 통과하기 때문이다. 게이트가 늘수록 우회 유인이 커진다.

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

구현을 마치면 스펙 문서(`docs/spec/MSG-XXX.md`) 작업 로그에 diff 요약만 남기지 않는다.
**데이터 저장 위치**(테이블·Redis 키·S3 경로) · **실행 쿼리**(핵심 SQL 실측) ·
**예외 흐름**(어디서 던져져 어느 핸들러가 어떤 developCode로 변환) · **빈 동작**(생명주기·
스케줄러·트랜잭션 프록시 경계)을 해당되는 만큼 함께 기록한다. 상세 기준:
`.claude/skills/spec-driven-dev/references/finalize.md` 3번 — 스킬 밖 직접 작업에도 동일 적용.

## Skills — 특정 워크플로우

- **srs-writer** — 전역 요구사항 명세 (`docs/srs.md`) 등재·갱신·조회, **파이프라인 최상단**
  트리거: "SRS 만들어줘", "요구사항 등재해줘", "전체 요구사항 뭐 있지", 정책 확정·번복 시
- **prd-writer** — PRD(제품 요구사항 문서) 생성 (`docs/prd/*.md`), **티켓·스펙보다 선행 · 필수 게이트**
  트리거: "PRD 만들어줘", "요구사항 문서 정리해줘", "개발 전에 문서부터"
- **spec-writer** — 개발 스펙 문서 생성 (`docs/spec/MSG-XXX.md`)
  트리거: "MSG-XX 스펙 만들어줘", "스펙 문서 정리해줘"
- **spec-driven-dev** — 스펙 기반 TDD 개발 (grid-developer/auth-developer/convention-reviewer 팀 조율)
  트리거: "MSG-XX 개발 시작", "스펙대로 개발해줘", "MSG-XX 이어서/다시 개발"

**스킬 description을 고치는 PR은 자동 호출 회귀 확인을 돌린다** — 고친 스킬의 문장 세트로
호출/미호출을 hook 로그로 판정하고 결과를 PR 본문에 남긴다. 절차·문장 세트 정본:
`.claude/docs/skill-trigger-regression.md` (MSG-529)

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
| 2026-08-06 | prd-writer 리서치 단계에 피그마 정본 참조 추가(화면 있는 작업 한정, 해당 노드만 조회), CLAUDE.md Reference에 fileKey·정본 페이지 명시 | prd-writer, CLAUDE.md | 피그마가 스킬·규칙 어디에서도 참조되지 않아 설계 입력에서 통째로 빠져 있었음 — glossary의 "피그마 ver8 실측" 같은 결과만 사후 인용되던 상태. 위키 폴더명도 같은 날 `fillmap-wiki` → `LLM-WIKI`로 바꿔 문서가 가리키던 `../LLM-WIKI`를 실재하게 함 (MSG-331) |
| 2026-08-06 | 위키 참조 조건을 "불확실할 때"에서 **관찰 가능한 트리거 4종**으로 교체 (문서 작성·스키마/계약 변경·근거 서술·레포 검색 실패), 위키/레포 정본 분담과 충돌 처리 명문화 | CLAUDE.md, prd-writer | "불확실할 때만 참조"는 자기 불확실성 판정을 요구하는데 LLM이 가장 못하는 일이라 실질적으로 "참조 안 함"으로 귀결 — 성민 지적, 실제로 같은 세션에서 위키를 한 번도 안 열었음 (MSG-331) |
| 2026-08-10 | **SRS 단계 신설** — 전역 요구사항 명세 `docs/srs.md`(요구사항 ID `FR-{영역}-{번호}`·`NFR-{분류}-{번호}`) + `srs-writer` 스킬. 파이프라인이 SRS → PRD → 스펙 → 구현으로 확장, prd-writer 리서치에 SRS 대조 소프트 편입 | CLAUDE.md, srs-writer(신설), prd-writer | 멘토 피드백(SRS 단계 표준화) + PRD가 티켓 단위 스냅숏이라 "지금 서비스 요구사항 전체가 뭐냐"에 답하는 문서가 부재 — 뱃지 작업에서 디자인 화면 8칸과 시드 17종의 이름 갭, 미션 축 개념 불일치가 늦게 발견된 것이 그 부재의 실측 사례. 게이트를 하드로 걸지 않은 것은 단계가 늘수록 우회 유인이 커지기 때문 (MSG-358) |
| 2026-08-11 | 시각(날짜·시간) 처리 컨벤션 신설 — 인자 없는 `now()` 금지·필드별 `@JsonFormat` 금지·KST 라벨은 `LocalDate`·DTO 시각 타입 가드·손조립 매퍼 테스트 금지·쿼리 파라미터 시각 금지 6항 | project-conventions.md | 사용자 지시 — MSG-376 전역 UTC `Z` 코덱의 전제("naive = UTC")가 코드로 강제되지 않는 관례라서, 위반이 로컬 KST 머신에서만 9시간 어긋나는 잠복 버그가 됨. convention-reviewer가 매 리뷰에서 검사하도록 규칙화 |
| 2026-08-13 | 스펙 작성 직후 Codex 교차 리뷰를 상시 편입 (절차 5 신설, `git add -N` 선행·수정 지시는 원저자 에이전트·미채택 지적 근거 보고) | spec-writer | 사용자 지시 — 구현 후 리뷰(Phase 4)만 있어 스펙 결함은 구현으로 번진 뒤에야 잡혔다. MSG-384 스펙에서 첫 적용에 P1 2건·P2 1건 적발: reset 삭제의 완전성 가드 부재(파서가 깨진 행을 세고 넘어가 1건만 유효해도 전량 삭제), 이미지 객체 키 `.jpg` 고정과 저작권 미해결의 자기모순, 롤백 테스트가 삭제 이전에 예외를 터뜨려 검증하려던 성질을 못 잡음 |
| 2026-08-15 | convention-reviewer 정의에 `model: opus` 고정 — 07-26 전면 상속 결정의 부분 번복(reviewer 한정), subagent-orchestration.md에 예외 명문화 | convention-reviewer, rules/subagent-orchestration.md | 사용량 보고 실측 — 사용량 100%가 서브에이전트 헤비 세션이고 Fable 주간 소진(36%)이 전체(20%)를 크게 앞섬. 체크리스트 검증(빌드 실행·컨벤션 grep·계약 대조)은 기계적이라 Fable 불요, 성민 지시. general-purpose 단순 조회도 호출 시 `model: "opus"` 명시(메모리 규칙, 2026-07-30 확정의 재확인) |
| 2026-08-14 | 마무리에 SRS 상태 갱신·rtm 재생성 단계 신설 (finalize.md 2-1) — 테스트에 `// 검증: FR-...` 주석을 먼저 달고, 요구사항 상태와 검증 테스트를 같은 브랜치에 함께 커밋한다. 일부 구현은 `구현됨`이 아니라 `진행 중` | spec-driven-dev (finalize.md) | MSG-390 실측 — 마무리에 status.md와 작업 로그는 있었지만 SRS·rtm이 없어, 구현된 요구가 `계획`으로 실리고 연결 테스트 0건인 채 develop에 머지됐다. 뒤늦게 문서 브랜치에서 상태만 올리자 CI의 RTM 신선도 검사가 막았다(그 브랜치엔 테스트가 없어 검증 공백 1건). 상태와 검증을 갈라 커밋하면 추적이 끊긴다는 것이 이 단계의 요지 |
| 2026-08-31 | 스킬 정의 이원화 해소 — `.agents/skills` 사본 10개를 `.claude/skills` 심볼릭 링크로 대체, 재분기는 CI 가드가 차단 | 전체 스킬 | `.agents` 미러(MSG-336)가 생성 하루 만에 갈라져 4종 전부 판본이 어긋남 — Codex 세션이 낡은 커밋 게이트·리뷰 루프 절차를 읽던 상태. 정본은 `.claude`(08-13 이후 개정 4건이 이쪽에만 쌓임 — 내용 대조로 판정), 링크 방향은 실패 비용 비대칭(`.claude` 링크화는 Claude Code 추종 미검증, `.agents`는 소비자 0)으로 티켓 제안을 번복해 성민 승인 (MSG-525) |
| 2026-08-31 | 에이전트 응답 컨트랙트 신설 — 작업 마무리 응답에 Applied rules·Applied skills(없으면 "없음" 명시)·Why this routing·수정하지 않은 파일 4항목 MUST, 존댓말 카나리로 컨텍스트 유실 판정 | rules/agent-response-contract.md(신설), CLAUDE.md, AGENTS.md, 전체 에이전트 | 2026-08-29 멘토 리뷰 — 에이전트가 잘못 동작해도 어떤 룰·스킬·라우팅을 탔는지 기록이 없어 되짚을 수단이 없음(.claude 전체 grep 0건). 리뷰를 통째로 건너뛴 사고를 결과를 보고서야 발견한 실측 — 컨트랙트가 있었으면 "Applied skills: 없음"이 그 자리에서 보였다. 형식 자체가 유실 탐지기 (MSG-527) |
| 2026-08-31 | 스킬 자동 호출 회귀 확인 신설 — PostToolUse hook(matcher Skill)이 모든 스킬 호출을 `.claude/logs/skill-calls.jsonl`에 기록, description 변경 PR은 문장 세트(스킬 4종 × 호출/미호출 + 체이닝 2건)로 회귀 확인 후 결과를 PR 본문에 남김 | settings.json(신설), hooks/log-skill-call.sh(신설), docs/skill-trigger-regression.md(신설), CLAUDE.md | 멘토 지적("자동 호출이 실제로 되는지 매번 테스트한다") — description을 고치면 호출 동작이 바뀌는데 확인 수단이 없어 조용히 깨짐. 스킬이 안 불리면 오류가 아니라 규칙 빠진 결과물이 나옴. 판정을 응답 자기 보고가 아닌 hook 로그로 하는 이유는 컨텍스트 유실과 무관한 기계적 기록이라서 — MSG-527 Applied skills와 대조하면 거짓 보고도 잡힘 (MSG-529) |

## Quick Commands

```bash
./gradlew build              # 빌드
./gradlew build -x test      # 테스트 제외
./gradlew test               # 전체 테스트
./gradlew bootRun            # 실행
```

## 협업 원칙 (요약)

- **Owner A**: 지도 인프라 도메인 (`com.msg.fillmap.grid.*`, `com.msg.fillmap.region.*`, `com.msg.fillmap.search.*` — MSG-251 §D1, `com.msg.fillmap.hotzone.*` — MSG-233, `com.msg.fillmap.zone.*` — MSG-234 §Owner 판정·MSG-259 확정, 2026-08-07 목록 누락 보완)
- **Owner B**: 콘텐츠/인증 도메인 (`com.msg.fillmap.user.*`, `com.msg.fillmap.video.*`, `com.msg.fillmap.auth.*`, `com.msg.fillmap.usergrid.*`, `com.msg.fillmap.badge.*` — MSG-239, `com.msg.fillmap.streak.*` — MSG-200, `com.msg.fillmap.mission.*` — MSG-222, `com.msg.fillmap.notification.*` — MSG-178, `com.msg.fillmap.friend.*` — MSG-185, `com.msg.fillmap.moderation.*` — MSG-192, `com.msg.fillmap.event.*` — MSG-438 §Owner 판정·MSG-443, `com.msg.fillmap.route.*` — MSG-457 §Owner 판정(라벨 공동·주 구현 B, A 접점은 PlaceSearchService 오버로드 1건))
- 두 도메인의 접점은 인터페이스로만 (`GridQueryService`, `UserGridQueryService`, `ZoneNameQueryService`(격자 표시명 계산, MSG-341) 등)
- **Owner는 도메인 분담 라벨이지 "과거에 누가 짰나"의 기록이 아니다.** 계약 인터페이스 경계
  판정과 spec-driven-dev의 개발 에이전트 배정(A→grid-dev·B→auth-dev)에 쓰는 값은 이 라벨이
  맞다. 다만 실제 사람 구현자는 별개이고 git 이력·지라 담당자가 정본이다 — 패키지별 주 구현자는
  `@.claude/docs/status.md` 헤더에 병기해 뒀다. 둘이 어긋나는 건 오류가 아니라 분담과 실제 작업
  배분이 다르게 흘렀다는 사실이다 (2026-08-07 전수 대조, MSG-337)
- 상세: `@.claude/docs/infrastructure.md`