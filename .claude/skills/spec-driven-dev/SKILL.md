---
name: spec-driven-dev
description: MSG-XX 스펙 문서를 기반으로 grid-developer/auth-developer/convention-reviewer 팀을 조율해 TDD로 구현한다. "MSG-XX 개발 시작", "스펙대로 개발해줘", "MSG-XX 이어서 개발", "MSG-XX 리뷰 반영해서 다시", "MSG-XX 일부만 다시 구현" 같은 요청에서 사용한다. 스펙 문서가 없으면 이 스킬이 spec-writer를 먼저 호출한다 — 스펙 없이 바로 구현 요청이 들어와도 이 스킬로 처리한다.
---

# Spec-Driven Dev Orchestrator

`docs/MSG-XXX.md` 스펙을 읽고 grid-developer(Owner A) / auth-developer(Owner B) /
convention-reviewer 팀을 조율해 실제 코드와 테스트를 완성하는 오케스트레이터.

팀 운영 방식(이름 붙여 스폰, `SendMessage` 통신, `TaskCreate` 작업 보드, 점진적 QA,
에스컬레이션, 빌드 오류 루프, `model` 미지정)은 공통 규칙
`.claude/rules/subagent-orchestration.md`를 따른다. 이 문서는 FillMap 고유의 단계 라우팅만
담고, 무거운 단계별 절차는 그 단계에 **도달했을 때** `references/`에서 읽는다 — 미리 다 읽지 않는다.

## 에이전트 구성

| 팀원 이름 | 에이전트 타입 | 역할 | 산출물 |
|-----------|--------------|------|--------|
| `spec-writer` | `spec-writer` | 스펙 문서 없을 때만 선행 호출 | `docs/MSG-{번호}.md` |
| `grid-dev` | `grid-developer` | Owner A: grid/region 구현 | `src/main/java/com/msg/fillmap/{grid,region}/**`, 테스트 |
| `auth-dev` | `auth-developer` | Owner B: user/video/auth/usergrid 구현 | `src/main/java/com/msg/fillmap/{user,video,auth,usergrid}/**`, 테스트 |
| `reviewer` | `convention-reviewer` | 컨벤션·계약·빌드 검증 | 리뷰 결과(위반 목록 또는 통과) |

## 워크플로우

### Phase 0: 컨텍스트 확인

1. `docs/MSG-{번호}.md` 존재 여부 확인.
   - **없음** → `spec-writer` 에이전트를 먼저 호출해 스펙을 만든다(포그라운드, 결과를 받은 뒤 진행).
     사용자가 티켓 설명을 주지 않았고 Jira 조회도 안 되면 여기서 멈추고 되묻는다.
   - **있음, 첫 개발 요청** → Phase 1로 진행 (초기 실행).
   - **있음, "이어서/다시/일부만" 같은 후속 요청** → 관련 패키지의 기존 코드를 `Glob`/`Read`로
     확인해 이미 구현된 부분과 남은 작업을 구분한다(부분 재실행). 이미 끝난 모듈은 재호출하지 않는다.
2. `git status`로 미커밋 변경이 있는지 확인한다. 다른 작업 중인 변경이 있으면 사용자에게
   알리고 계속할지 확인한다 — 임의로 stash/reset하지 않는다.

### Phase 1: 오너 판정 및 팀 결정

`docs/MSG-{번호}.md`의 `**Owner**` 필드를 읽는다.

| Owner | 스폰할 개발 에이전트 |
|-------|---------------------|
| A | `grid-dev`만 |
| B | `auth-dev`만 |
| 공동 | `grid-dev` + `auth-dev` 둘 다 (계약 인터페이스 협의 필요) |

`reviewer`는 항상 스폰한다(모듈 단위 점진적 리뷰를 위해 개발 에이전트와 동시에 살아있어야 한다).

### Phase 2: 스폰 및 작업 등록

```
Agent(name: "grid-dev", subagent_type: "grid-developer",
      run_in_background: true,
      prompt: "docs/MSG-{번호}.md 스펙을 읽고 Owner A 범위를 구현하라. ...")

Agent(name: "auth-dev", subagent_type: "auth-developer",
      run_in_background: true, prompt: "...") // Owner B/공동일 때만

Agent(name: "reviewer", subagent_type: "convention-reviewer",
      run_in_background: true,
      prompt: "grid-dev/auth-dev가 모듈을 완성할 때마다 SendMessage로 리뷰 요청이 온다.
               .claude/rules/project-conventions.md, glossary.md 기준으로 검증하고 ./gradlew로
               빌드·테스트를 확인하라.")
```

`TaskCreate`로 스펙의 "테스트 시나리오"를 모듈 단위 작업으로 등록한다 — `owner` 지정과
리뷰 작업의 `addBlockedBy` 의존은 공통 규칙대로.

### Phase 3: 구현 (팀원 자체 조율)

- 조율(모듈 완성 → 즉시 리뷰 요청, 계약 인터페이스 합의, 1회 수정 후 에스컬레이션)은 공통
  규칙 그대로. 여기서 계약 인터페이스란 `GridQueryService` 등 Owner A/B 접점 인터페이스다.
- **커밋 포인트 + 스톱 게이트 (필수)**: 모듈이 green + 리뷰를 통과하는 시점마다 발생한다.
  첫 커밋 포인트에 도달하기 전에 `references/commit-gate.md`를 읽고 그대로 적용한다.

### Phase 4: 통합 확인

1. 모든 개발 작업과 리뷰 작업이 `completed`인지 `TaskGet`으로 확인한다.
2. 리더가 직접 `./gradlew build`로 최종 확인한다(리뷰어의 부분 실행과 별개로 전체 빌드 1회 필수).
3. 실패가 남아있으면 담당 팀원에게 재작업을 요청한다. 스펙 자체의 결함으로 보이면 사용자에게
   보고하고 스펙 수정 여부를 확인한다 — 임의로 스펙을 재해석해 구현을 바꾸지 않는다.
4. **Codex 교차 리뷰**: 빌드 green 후 `references/codex-review-loop.md`를 읽고 그대로 실행한다.
   필수 게이트는 3번까지고 Codex는 추가 검증층이지만, 백그라운드 서브에이전트가 짠 코드를 보는
   유일한 실질 diff 리뷰다(MSG-145 실측) — 임의 생략·생략 위장 금지.

### Phase 5: 마무리

`references/finalize.md` 체크리스트대로: 요약 보고 → `.claude/docs/status.md` 갱신 →
스펙 문서에 작업 로그 append → 커밋 메시지 제안(실행은 사용자) → 팀원 종료 통지.

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 개발 에이전트 1명 실패/중단 | 리더가 유휴 알림 수신 → `SendMessage`로 상태 확인 → 재시작. 재실패 시 해당 도메인 결과 없이 진행하고 보고서에 명시 |
| `./gradlew build` 최종 실패 | 실패 로그 핵심부를 사용자에게 보여주고, 완료 처리하지 않음 |

반복 위반·계약 불일치 에스컬레이션은 공통 규칙(`subagent-orchestration.md`)의 조율 원칙을 따른다.

## 테스트 시나리오

**정상 흐름**: "MSG-42 개발 시작해줘" → `docs/MSG-42.md` 존재 확인(Owner A) →
`grid-dev` + `reviewer` 스폰 → 모듈별 점진적 리뷰 → `./gradlew build` 통과 →
커밋 메시지 후보 제시.

**에러 흐름**: "MSG-50 개발 시작해줘" 인데 `docs/MSG-50.md`가 없음 → `spec-writer` 선행 호출 →
티켓 설명도 Jira도 없어 스펙 작성 불가 → 사용자에게 티켓 내용을 요청하고 개발 착수 보류.
