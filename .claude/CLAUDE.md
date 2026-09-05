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
- `api-docs/README.md` — 팀 전용 API 문서 사이트(docs.fillmap.kr, MSG-568) 소스와 로컬 빌드. 인프라·접근 계정은 deploy.md "API 문서 사이트" 절
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
스케줄러·트랜잭션 프록시 경계)을 해당되는 만큼 함께 기록한다. **호출 스킬·플러그인**(실제
호출한 스킬과 외부 플러그인·도구, hook 로그 `.claude/logs/skill-calls.jsonl` 대조, 없으면
"없음" 명시)도 함께 남긴다 (2026-08-31 신설). 상세 기준:
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

**변경 이력:** [CLAUDE-changelog.md](./CLAUDE-changelog.md) — 하네스·스킬·에이전트 구성이 바뀐 이유가
날짜순으로 있다. 구성을 바꾸는 작업은 그 표 끝에 행을 붙인다.

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