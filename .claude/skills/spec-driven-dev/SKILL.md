---
name: spec-driven-dev
description: MSG-XX 스펙 문서를 기반으로 grid-developer/auth-developer/convention-reviewer 팀을 조율해 TDD로 구현한다. "MSG-XX 개발 시작", "스펙대로 개발해줘", "MSG-XX 이어서 개발", "MSG-XX 리뷰 반영해서 다시", "MSG-XX 일부만 다시 구현" 같은 요청에서 사용한다. 스펙 문서가 없으면 이 스킬이 spec-writer를 먼저 호출한다 — 스펙 없이 바로 구현 요청이 들어와도 이 스킬로 처리한다.
---

# Spec-Driven Dev Orchestrator

`docs/MSG-XXX.md` 스펙을 읽고 grid-developer(Owner A) / auth-developer(Owner B) /
convention-reviewer 팀을 조율해 실제 코드와 테스트를 완성하는 오케스트레이터.

## 실행 모드: 에이전트 팀

**이 환경의 실제 도구 기준**: `TeamCreate`/`TeamDelete` 도구는 없다. 이름을 지정해
`Agent(run_in_background: true)`로 스폰하면 그 이름이 곧 팀원 식별자가 되고, `SendMessage`로
이름을 주소로 서로/리더와 통신할 수 있다. 팀 구성은 "이름 붙여 스폰"으로 대체하고,
조율은 `TaskCreate`/`TaskUpdate`/`TaskGet`(공유 작업 보드) + `SendMessage`(실시간 소통)로 한다.

## 에이전트 구성

| 팀원 이름 | 에이전트 타입 | 역할 | 산출물 |
|-----------|--------------|------|--------|
| `spec-writer` | `spec-writer` | 스펙 문서 없을 때만 선행 호출 | `docs/MSG-{번호}.md` |
| `grid-dev` | `grid-developer` | Owner A: grid/region 구현 | `src/main/java/com/msg/fillmap/{grid,region}/**`, 테스트 |
| `auth-dev` | `auth-developer` | Owner B: user/video/auth/usergrid 구현 | `src/main/java/com/msg/fillmap/{user,video,auth,usergrid}/**`, 테스트 |
| `reviewer` | `convention-reviewer` | 컨벤션·계약·빌드 검증 | 리뷰 결과(위반 목록 또는 통과) |

모든 `Agent` 호출에 `model: "opus"`를 명시한다.

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
Agent(name: "grid-dev", subagent_type: "grid-developer", model: "opus",
      run_in_background: true,
      prompt: "docs/MSG-{번호}.md 스펙을 읽고 Owner A 범위를 구현하라. ...")

Agent(name: "auth-dev", subagent_type: "auth-developer", model: "opus",
      run_in_background: true, prompt: "...") // Owner B/공동일 때만

Agent(name: "reviewer", subagent_type: "convention-reviewer", model: "opus",
      run_in_background: true,
      prompt: "grid-dev/auth-dev가 모듈을 완성할 때마다 SendMessage로 리뷰 요청이 온다.
               .claude/rules/project-conventions.md, glossary.md 기준으로 검증하고 ./gradlew로
               빌드·테스트를 확인하라.")
```

`TaskCreate`로 스펙의 "테스트 시나리오"를 모듈 단위 작업으로 등록하고, 각 작업에
`owner`를 해당 팀원 이름으로 지정한다. `reviewer`의 검증 작업은 대응하는 개발 작업에
`addBlockedBy`로 의존시킨다(개발이 끝나야 리뷰가 의미 있으므로).

### Phase 3: 구현 (팀원 자체 조율)

- 개발 에이전트는 모듈을 완성하면 `TaskUpdate`로 상태를 갱신하고 `SendMessage`로 `reviewer`에게
  리뷰를 요청한다 — 전체 완료를 기다리지 않는다(점진적 QA).
- `grid-dev`/`auth-dev` 둘 다 스폰된 경우, 계약 인터페이스(`GridQueryService` 등) 변경이
  필요하면 서로 `SendMessage`로 먼저 합의한다. 리더(오케스트레이터)는 유휴 알림을 받으면
  `TaskGet`으로 진행 상황을 확인하고, 막힌 팀원에게 `SendMessage`로 개입한다.
- `reviewer`가 위반을 발견하면 해당 팀원에게 직접 `SendMessage`로 전달한다. 1회 수정 기회를
  준 뒤 재실패하면 리더에게 에스컬레이션한다.

### Phase 4: 통합 확인

1. 모든 개발 작업과 리뷰 작업이 `completed`인지 `TaskGet`으로 확인한다.
2. 리더가 직접 `./gradlew build`로 최종 확인한다(리뷰어의 부분 실행과 별개로 전체 빌드 1회 필수).
3. 실패가 남아있으면 담당 팀원에게 재작업을 요청한다. 스펙 자체의 결함으로 보이면 사용자에게
   보고하고 스펙 수정 여부를 확인한다 — 임의로 스펙을 재해석해 구현을 바꾸지 않는다.

### Phase 5: 마무리

1. 변경된 파일 목록과 함께 요약 보고 (Owner별 구현 내역, 리뷰 통과 여부, 남은 이슈).
2. 이번 작업으로 패키지·계약 인터페이스·엔티티가 planned → partial → built로 바뀌었으면
   `.claude/docs/status.md`의 해당 행을 갱신한다 (구현 현황 문서가 낡지 않게).
3. `docs/MSG-{번호}.md` 하단에 `## 작업 로그` 섹션을 append한다(없으면 생성). 이번 실행에서
   완료한 모듈, 내린 구현 결정, 리뷰 반영 사항, 제안한 커밋 메시지를 날짜 헤더 + 불릿 한 줄들로
   누적 기록한다 — 덮어쓰지 않고 항목만 추가한다(부분 재실행 시 이력 보존). status.md가 "현재 상태"라면
   여기 작업 로그는 "티켓별 시간순 이력"으로 역할을 분리한다.
4. 커밋 메시지 후보를 `MSG-{번호} {타입}: {요약}` 형식으로 제안한다 — **실제 커밋은 사용자가
   명시적으로 요청할 때만** 수행한다(하네스가 자동으로 커밋/푸시하지 않는다).
5. 스폰된 팀원들에게 `SendMessage`로 종료를 알린다.

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 개발 에이전트 1명 실패/중단 | 리더가 유휴 알림 수신 → `SendMessage`로 상태 확인 → 재시작. 재실패 시 해당 도메인 결과 없이 진행하고 보고서에 명시 |
| `reviewer`가 같은 위반을 2회 지적 | 담당 개발 에이전트가 아니라 리더가 개입 — 스펙-컨벤션 충돌 가능성을 사용자에게 확인 |
| Owner A/B 계약 불일치 발견 | 삭제하지 않고 양쪽 주장을 병기해 사용자 판단 요청 |
| `./gradlew build` 최종 실패 | 실패 로그 핵심부를 사용자에게 보여주고, 완료 처리하지 않음 |

## 테스트 시나리오

**정상 흐름**: "MSG-42 개발 시작해줘" → `docs/MSG-42.md` 존재 확인(Owner A) →
`grid-dev` + `reviewer` 스폰 → 모듈별 점진적 리뷰 → `./gradlew build` 통과 →
커밋 메시지 후보 제시.

**에러 흐름**: "MSG-50 개발 시작해줘" 인데 `docs/MSG-50.md`가 없음 → `spec-writer` 선행 호출 →
티켓 설명도 Jira도 없어 스펙 작성 불가 → 사용자에게 티켓 내용을 요청하고 개발 착수 보류.
