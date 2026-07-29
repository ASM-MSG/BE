---
name: spec-writer
description: MSG-XX 티켓을 docs/MSG-XXX.md 개발 스펙 문서로 변환한다. "MSG-XX 스펙 만들어줘", "스펙 문서 정리해줘", "MSG-XX 스펙 다시 써줘/수정해줘" 같은 요청에서 반드시 사용한다. 스펙 없이 바로 구현을 시작하는 요청("MSG-XX 개발 시작")은 spec-driven-dev 스킬이 처리하며, 그 스킬이 스펙이 없을 때 이 스킬을 내부적으로 재사용한다.
---

# Spec Writer

MSG-XX 티켓을 `docs/MSG-XXX.md` 스펙 문서로 만드는 단일 작업 스킬. 실행 모드는
**서브 에이전트** — 이 작업은 결과 하나(파일)만 만들면 끝나는 선형 작업이라 팀 통신
오버헤드가 필요 없다.

## 실행 절차

1. **입력 확보**: `docs/prd/MSG-{번호}-prd.md`가 있으면 요구사항 정본으로 최우선 사용한다
   (prd-writer 산출물 — 스펙은 PRD의 "무엇을/왜"에 "어떻게"를 붙이는 문서다).
   대화에 티켓 설명이 있으면 그대로 사용. 없으면 Jira MCP
   (`mcp__claude_ai_Atlassian_Rovo__getJiraIssue`)가 연결돼 있는지 확인하고 MSG-XX 키로
   조회를 시도한다. 조회 실패 시 사용자에게 티켓 내용을 요청한다 — 추측하지 않는다.
2. **기존 스펙 확인**: `docs/MSG-{번호}.md`가 이미 있으면 덮어쓰기 전에 diff를 요약해
   사용자에게 갱신 의사를 확인한다.
3. `.claude/agents/spec-writer.md`에 정의된 에이전트를 호출한다:

```
Agent(
  subagent_type: "spec-writer",
  prompt: "{MSG-XX 티켓 설명 또는 Jira 조회 결과 전체}를 바탕으로 docs/MSG-{번호}.md 스펙 문서를 작성하라.
           .claude/rules/glossary.md 용어를 기준으로 쓰고, .claude/docs/infrastructure.md 패키지
           구조를 참고해 Owner A/B/공동을 판정하라."
)
```

4. 에이전트가 반환한 결과(생성된 파일 경로 + Owner 판정 + 미해결 질문 유무)를 사용자에게
   요약해 보여준다. 미해결 질문이 있으면 구현 착수 전에 먼저 해소하도록 안내한다.

## 후속 처리

사용자가 스펙 확인 후 "이제 개발 시작해줘"라고 하면 `spec-driven-dev` 스킬로 넘어간다 —
이 스킬이 직접 구현까지 하지 않는다.

## 테스트 시나리오

- **정상 흐름**: "MSG-42 스펙 만들어줘, 티켓 내용은 [스폰서 격자 입찰 API]" → spec-writer 에이전트
  호출 → `docs/MSG-42.md` 생성 확인.
- **에러 흐름**: 티켓 설명 없이 "MSG-77 스펙 만들어줘"만 입력 + Jira 미연결 →
  사용자에게 티켓 내용을 되묻고 진행하지 않음.
