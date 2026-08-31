# AGENTS.md

FillMap 백엔드에서 일하는 AI 코딩 도구를 위한 진입점이다. Claude Code는 `.claude/CLAUDE.md`를
자동으로 읽지만 다른 도구(Codex 등)는 이 파일을 읽으므로, 여기서 실제 규칙 문서로 안내한다.

**규칙 본문은 여기에 적지 않는다.** 같은 규칙이 두 곳에 있으면 한쪽이 반드시 낡는다.
아래 경로가 정본이고, 이 파일은 어디를 봐야 하는지와 자주 어기는 것만 짚는다.

## FillMap이 뭔가

사용자가 방문한 장소를 30초 이내 짧은 영상으로 기록하고, 지도 위 약 100×100m 격자를 수집하는
서비스다. 백엔드는 Spring Boot + PostgreSQL(PostGIS). Web → Android → iOS 순으로 확장한다.

## 작업 전에 읽을 것

| 문서 | 내용 |
|---|---|
| `.claude/CLAUDE.md` | 전체 안내. 아래 문서들의 목차이기도 하다 |
| `.claude/rules/coding-principles.md` | 코딩 행동 원칙 |
| `.claude/rules/project-conventions.md` | 네이버 Java 컨벤션, 커밋·브랜치 규칙, DTO 네이밍 |
| `.claude/rules/response-pattern.md` | 공통 응답 포맷과 예외 처리. 새 API를 만들면 반드시 본다 |
| `.claude/rules/glossary.md` | 도메인 용어 정의. 격자·점령·방문·도감의 뜻이 코드와 UI에서 다르다 |
| `.claude/docs/status.md` | **구현 현황. 문서 중 가장 먼저 본다** — 무엇이 실제로 있는지의 정본 |
| `.claude/docs/architecture.md` | 서비스 아키텍처 |
| `.claude/docs/grid-system.md` | 100×100m 격자 계산 규칙 |
| `.claude/docs/infrastructure.md` | 패키지 구조, 로컬 DB 세팅, AWS 구성 |

`docs/MSG-XXX.md`는 티켓별 개발 스펙, `docs/prd/*.md`는 그 앞단의 제품 요구사항 문서다.

## 자주 어기는 규칙 넷

정본은 `.claude/rules/project-conventions.md`와 `.claude/CLAUDE.md`다. 여기 적은 건 요약이다.

### 1. 새 브랜치에서 시작한다

`develop`·`main`에 직접 파일을 만들거나 고치지 않는다. 문서 작업도 예외가 없다.
이름은 git flow 타입만 쓰고 하이픈으로 잇는다.

```
feature/MSG-142-bench     (O)
feature/MSG-142/bench     (X) 슬래시
chore/MSG-1-pr-template   (X) git flow에 없는 타입
```

### 2. 커밋은 제목 한 줄이 기본이다

형식은 `MSG-{번호} {타입}: {요약}`이고 git hook이 강제한다.
제목은 코드를 안 연 사람이 무엇이 바뀌었는지 아는 문장이어야 한다. 가운뎃점 나열은 쓰지 않는다.

```
MSG-179 fix: 컨슈머 poll 튜닝·말폼드 비재시도 분류                (X)
MSG-179 fix: 소비 지연 시 그룹 이탈 방지, 깨진 메시지는 즉시 폐기   (O)
```

본문은 제목으로 설명이 안 되는 근거(실측 수치, 기각한 대안)가 있을 때만 붙인다.

### 3. PRD가 스펙과 구현보다 앞선다

```
아이디어/티켓 → PRD(docs/prd/*.md) → 스펙(docs/MSG-XXX.md) → 구현
                 ↑ 필수 게이트
```

면제 기준은 하나다. **이 작업이 제품 요구사항을 새로 만들거나 바꾸는가.**
요구사항이 그대로면 PRD에 쓸 내용이 없으므로 면제다(문서, 리팩터링, 버그 수정, 성능 개선).
새 기능·새 API·기존 동작의 의도적 변경은 언제나 PRD가 필요하다.
스펙이 이미 있어도 PRD가 없으면 통과가 아니다. 판단 기준의 정본은 `.claude/CLAUDE.md`의
"개발 파이프라인" 절이다.

### 4. PR 본문은 템플릿을 그대로 채운다

`.github/PULL_REQUEST_TEMPLATE.md`의 네 절(관련 티켓 / 작업 내용 / 고민한 내용 / 리뷰 포인트)을
그 순서대로 쓴다. 임의 구성은 리뷰에서 지적된다.

## 구현을 마치면 런타임 동작을 기록한다

스펙 문서(`docs/MSG-XXX.md`) 작업 로그에 diff 요약만 남기지 않는다. 코드를 다시 읽어도 안 보이는
실행 시점 사실을 해당되는 만큼 적는다.

1. **데이터 저장 위치** — 테이블·컬럼·Redis 키·TTL·S3 경로
2. **실행 쿼리** — 핵심 SQL 실측. 파생 쿼리는 생성된 SQL, native는 원문
3. **예외 흐름** — 어디서 던져져 어느 핸들러가 어떤 developCode로 바꾸는지
4. **빈 동작** — 생명주기 콜백·스케줄러·자체 executor·트랜잭션 프록시 경계

상세 기준은 `.claude/skills/spec-driven-dev/references/finalize.md` 3번에 있다.

## 도메인 소유권

두 도메인의 접점은 인터페이스로만 잇는다(`GridQueryService`, `UserGridQueryService` 등).

- **Owner A** — 지도 인프라: `grid`, `region`, `search`, `hotzone`, `zone`
- **Owner B** — 콘텐츠/인증: `user`, `video`, `auth`, `usergrid`, `badge`, `streak`, `mission`,
  `notification`, `friend`, `moderation`

**Owner는 도메인 분담 라벨이지 "누가 짰나"의 기록이 아니다.** 계약 인터페이스 경계를 따질 때와
작업을 배정할 때 쓰는 값은 이 라벨이 맞다. 실제 사람 구현자는 별개이고 git 이력과 지라 담당자가
정본이며, 패키지별 주 구현자는 `.claude/docs/status.md` 헤더에 병기돼 있다. 둘이 어긋나는 건
오류가 아니다.

## 팀 위키

설계 근거와 기각된 대안, FE·AI·디자인과의 계약은 별도 레포에 있다(`../LLM-WIKI`,
Obsidian vault). 레포 `docs/`에는 결론만 남고 그렇게 정한 이유는 위키에 있다.

아래 네 경우엔 착수 전에 조회한다. 스스로 "불확실한가"를 따지지 말고 해당하면 기계적으로 본다.

1. 스펙·PRD·ADR을 새로 쓰거나 고칠 때 → `03-specs` · `04-decisions`
2. DB 스키마나 API 계약을 바꿀 때 → `03-specs`
3. "왜 이렇게 정했나"를 문서·PR·티켓에 쓸 때 → `04-decisions`
4. 레포와 지라를 찾았는데 근거가 안 나올 때 → `index.md`부터

전체 탐색은 하지 말고 frontmatter의 `keywords`/`aliases`로 타겟 grep 한다.

**위키와 레포가 어긋나면** 무엇이 어긋났는지로 가른다. 검증 가능한 사실(어떤 API가 있나,
무엇이 구현됐나)은 코드와 `.claude/docs/status.md`가 이긴다. 결정과 정책은 확정일로 최신을
가리고, 우열을 못 가리면 양쪽을 병기해 사람 판단으로 올린다.

## 빌드

```bash
./gradlew build              # 빌드
./gradlew build -x test      # 테스트 제외
./gradlew test               # 전체 테스트
./gradlew bootRun            # 실행
```

테스트는 로컬 PostgreSQL(5432)과 Redis(6379)가 떠 있어야 한다. `application-local.yml`은
gitignore 대상이라 레포에 없으니 `src/main/resources/application-local.yml.example`을 복사해 쓴다.

## 도구별 참고

`.claude/skills/`의 워크플로우 스킬 중 `prd-writer`와 `srs-writer`는 도구를 가리지 않지만,
`spec-driven-dev`와 `spec-writer`는 Claude Code의 서브에이전트 기능(Agent·SendMessage·작업 보드)에
의존하므로 다른 도구에서는 그대로 쓸 수 없다. 그 경우 스킬 문서를 절차 설명서로 읽고 수동으로
따른다 — 역할 정의는 `.codex/agents/*.toml`에 있고, 위임하는 자리에서 같은 세션이 그 역할로
이어서 작업한다. **구현과 검증은 그때도 분리한 패스로 돈다.** 자기가 쓴 코드를 자기가 통과시키지
말고 `convention-reviewer.toml`의 체크리스트로 따로 훑는다.

`.agents/skills`는 `.claude/skills`를 가리키는 심볼릭 링크다. 예전에는 별도 사본이었는데 하루 만에
갈라져 두 도구가 다른 규칙을 읽었다 (MSG-525). 사본을 되살리지 말고 `.claude/skills/` 한 곳만 고친다.
