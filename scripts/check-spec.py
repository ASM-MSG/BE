#!/usr/bin/env python3
"""스펙 문서 완료 정의 검사기 (spec-writer 스킬 절차 6의 증거 생성기).

docs/spec/MSG-{n}.md 한 건을 받아 기계로 확인 가능한 항목을 돌리고, 항목마다
PASS / FAIL / WARN / INFO 와 증거(줄 번호, 실측값)를 출력한다. FAIL 이 하나라도 있으면
exit 1. "괜찮아 보인다"는 검증이 아니므로, 스킬은 이 출력을 그대로 보고에 싣는다.

판정 등급
  FAIL  파이프라인 규칙 위반. 고치기 전에 보고하지 않는다.
  WARN  규칙 위반일 가능성이 높지만 스펙 시점에는 정상일 수도 있는 것. 보고에 그대로 싣는다.
  INFO  사람이 판단할 항목. 스크립트는 사실만 적는다.

검사 항목 (S1~S10)
  S1  파일명 MSG-{n}.md 와 제목 `# MSG-{n}:` 의 번호 일치
  S2  `**Owner**:` 헤더 존재, 첫 토큰이 A / B / 공동
  S3  요구사항 정본: 언급된 docs/prd/*.md 가 전부 실존하고, 헤더 영역(첫 `## ` 전)의 PRD 는
      `상태:` 첫 토큰이 검토됨/확정. PRD 언급도 "PRD 면제" 문구도 없으면 게이트 우회로 FAIL
  S4  본문의 FR-/NFR- ID 가 docs/srs.md 표에 실존 (없으면 FAIL, 폐기됨이면 WARN)
  S5  `## 성공 기준` 의 최상위 불릿이 `- AC-{티켓}-{2자리}:` 형식, 티켓 번호 일치, 순번 유일
  S6  필수 절 존재: 개요 / 배경 / 성공 기준 / API 명세 / 도메인 로직 / 데이터 모델 / 계약 변경 / 테스트 시나리오
  S7  본문(작업 로그 전, 코드블록 제외)에 줄표(— –) 0건
  S8  각주 `[^n]` 참조와 정의가 1:1 (불일치 FAIL), 개수 3~7 밖이면 WARN
  S9  `## 변경 파일` 절의 전체 경로가 실존하거나 같은 줄에 신설/신규/가져오기/삭제 표기 (아니면 WARN)
  S10 `## 미해결 질문` 에 종결되지 않은 항목이 있으면 INFO

사용법
  scripts/check-spec.py docs/spec/MSG-608.md
  scripts/check-spec.py docs/spec/MSG-608.md docs/spec/MSG-594.md
  scripts/check-spec.py --all            # 전수 (요약만)
레포 어디서 실행해도 된다. 표준 라이브러리만 쓴다.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ID_RE = re.compile(r"\b(N?FR-[A-Z]+-\d+)\b")
AC_RE = re.compile(r"^- (AC-(\d+)-(\d+)):")
FOOT_REF_RE = re.compile(r"\[\^(\d+)\](?!:)")
FOOT_DEF_RE = re.compile(r"^\[\^(\d+)\]:")
DASH_RE = re.compile(r"[—–]")
# `docs/prd/x.md` 와 스펙에서 쓰는 상대 링크 `../prd/x.md` 둘 다 잡는다. 그룹 1이 파일명.
PRD_PATH_RE = re.compile(r"(?:docs/|\.\./)prd/([A-Za-z0-9_.-]+\.md)")
STATUS_RE = re.compile(r"^>?\s*\**상태\**\s*:\s*(\S+)")
PATH_TOKEN_RE = re.compile(r"`((?:src|docs|scripts|load-test|monitoring|\.claude|\.github|api-docs)/[^`\s]+)`")
NEW_FILE_WORDS = ("신설", "신규", "가져오기", "삭제", "생성")
REQUIRED_SECTIONS = {
    "개요": re.compile(r"^## 개요"),
    "배경": re.compile(r"^## 배경"),
    "성공 기준": re.compile(r"^## 성공 기준"),
    "API 명세": re.compile(r"^## API 명세"),
    "도메인 로직": re.compile(r"^## 도메인 로직"),
    "데이터 모델": re.compile(r"^## 데이터 모델"),
    "계약 변경": re.compile(r"^## 계약 변경"),
    "테스트 시나리오": re.compile(r"^## 테스트 시나리오"),
}


def repo_root() -> Path:
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True)
        return Path(out.stdout.strip())
    except Exception:
        return Path.cwd()


@dataclass
class Result:
    code: str
    level: str  # PASS FAIL WARN INFO
    title: str
    evidence: str = ""


@dataclass
class Report:
    path: Path
    results: list[Result] = field(default_factory=list)

    def add(self, code: str, level: str, title: str, evidence: str = "") -> None:
        self.results.append(Result(code, level, title, evidence))

    @property
    def failed(self) -> bool:
        return any(r.level == "FAIL" for r in self.results)

    def counts(self) -> dict[str, int]:
        c = {"PASS": 0, "FAIL": 0, "WARN": 0, "INFO": 0}
        for r in self.results:
            c[r.level] += 1
        return c


def load_srs(root: Path) -> dict[str, str]:
    """docs/srs.md 표 행에서 {ID: 상태} 를 만든다. 행 형식: | FR-XXX-NN | 문장 | 상태 | 근거 |"""
    srs = root / "docs" / "srs.md"
    table: dict[str, str] = {}
    if not srs.exists():
        return table
    row_re = re.compile(r"^\|\s*(N?FR-[A-Z]+-\d+)\s*\|")
    for line in srs.read_text(encoding="utf-8").splitlines():
        m = row_re.match(line)
        if not m:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        status = cells[2] if len(cells) >= 3 else ""
        table[m.group(1)] = status
    return table


def split_sections(lines: list[str]) -> dict[str, tuple[int, int]]:
    """`## ` 헤딩 기준으로 {헤딩 텍스트: (시작 줄 idx, 끝 줄 idx)} 를 돌려준다 (0-based, 끝은 exclusive)."""
    heads = [(i, l[3:].strip()) for i, l in enumerate(lines) if l.startswith("## ")]
    out: dict[str, tuple[int, int]] = {}
    for n, (i, title) in enumerate(heads):
        end = heads[n + 1][0] if n + 1 < len(heads) else len(lines)
        out.setdefault(title, (i, end))
    return out


def body_range(lines: list[str]) -> int:
    """작업 로그 절 시작 전까지를 '본문'으로 본다. 작업 로그는 spec-driven-dev 가 붙이는 이력이다."""
    for i, l in enumerate(lines):
        if l.startswith("## 작업 로그"):
            return i
    return len(lines)


def check(path: Path, root: Path, srs: dict[str, str]) -> Report:
    rep = Report(path)
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    body_end = body_range(lines)
    sections = split_sections(lines)
    first_h2 = min((s for s, _ in sections.values()), default=len(lines))
    header = "\n".join(lines[:first_h2])

    # S1 파일명 ↔ 제목 번호
    m_file = re.match(r"MSG-(\d+)\.md$", path.name)
    ticket = m_file.group(1) if m_file else None
    title_line = next((l for l in lines if l.startswith("# ")), "")
    m_title = re.match(r"# MSG-(\d+):", title_line)
    if ticket is None:
        rep.add("S1", "WARN", "파일명이 MSG-{n}.md 형식이 아님 (아이디어 단계 slug 스펙). 티켓 번호 검사(S1·S5 번호 일치)는 건너뜀",
                f"파일명 {path.name}")
    elif not m_title:
        rep.add("S1", "FAIL", "제목이 `# MSG-{n}:` 형식이 아님", f"1행: {title_line[:80]!r}")
    elif m_title.group(1) != ticket:
        rep.add("S1", "FAIL", "파일명 번호와 제목 번호 불일치", f"파일 MSG-{ticket} / 제목 MSG-{m_title.group(1)}")
    else:
        rep.add("S1", "PASS", "파일명과 제목 번호 일치", f"MSG-{ticket}")

    # S2 Owner
    owner_line = next(((i, l) for i, l in enumerate(lines, 1) if l.startswith("**Owner**")), None)
    if not owner_line:
        rep.add("S2", "FAIL", "`**Owner**:` 헤더 없음 (spec-driven-dev 팀 배정 기준)")
    else:
        val = re.sub(r"^\*\*Owner\*\*\s*:\s*", "", owner_line[1]).strip()
        tok = re.match(r"(공동|A|B)\b", val)
        if tok:
            rep.add("S2", "PASS", "Owner 판정 있음", f"{owner_line[0]}행: {tok.group(1)}")
        else:
            rep.add("S2", "FAIL", "Owner 값 첫 토큰이 A / B / 공동 이 아님", f"{owner_line[0]}행: {val[:60]!r}")

    # S3 요구사항 정본 (PRD 게이트 흔적)
    body_text = "\n".join(lines[:body_end])
    prd_paths = sorted({f"docs/prd/{n}" for n in PRD_PATH_RE.findall(body_text)})
    # 면제 문구는 헤더 블록쿼트가 관례지만 개요 첫 문단에 적은 스펙도 있어 본문 전체에서 찾는다.
    # 표기 변형: "PRD 면제", "PRD 게이트: 면제", "**PRD**: 면제". PRD 뒤 20자 안의 '면제'를 면제 선언으로 본다.
    exempt = re.search(r"PRD[^\n]{0,20}면제", body_text) is not None
    missing = [p for p in prd_paths if not (root / p).exists()]
    if not prd_paths and not exempt:
        rep.add("S3", "FAIL", "PRD 경로 언급도 'PRD 면제' 문구도 없음 (게이트 우회 의심). 헤더 영역에 `> 요구사항 정본:` 또는 `> PRD 면제:` 를 적는다")
    elif missing:
        rep.add("S3", "FAIL", "언급된 PRD 파일이 레포에 없음", ", ".join(missing))
    else:
        header_prds = sorted({f"docs/prd/{n}" for n in PRD_PATH_RE.findall(header)})
        bad_status = []
        for p in header_prds:
            st = None
            for l in (root / p).read_text(encoding="utf-8").splitlines()[:15]:
                ms = STATUS_RE.match(l)
                if ms:
                    st = ms.group(1)
                    break
            if st not in ("검토됨", "확정"):
                bad_status.append(f"{p} (상태: {st or '헤더 없음'})")
        if bad_status:
            rep.add("S3", "FAIL", "헤더가 정본으로 지목한 PRD 가 승인 전 (첫 토큰이 검토됨/확정 아님, fail-closed)", "; ".join(bad_status))
        elif exempt and not header_prds:
            rep.add("S3", "PASS", "PRD 면제 근거 명시", "헤더에 'PRD 면제' 문구")
        else:
            rep.add("S3", "PASS", "정본 PRD 실존·승인 확인", ", ".join(header_prds) if header_prds else f"본문 언급 {len(prd_paths)}건 실존(헤더 지목 없음)")

    # S4 FR/NFR ID 실존
    ids = sorted(set(ID_RE.findall("\n".join(lines[:body_end]))))
    if not srs:
        rep.add("S4", "WARN", "docs/srs.md 를 읽지 못해 ID 실존 검사 생략")
    elif not ids:
        rep.add("S4", "INFO", "본문에 FR/NFR ID 참조 없음. SRS 추적이 끊긴 스펙이면 정본 절에 관련 ID 를 적는다")
    else:
        unknown = [i for i in ids if i not in srs]
        retired = [i for i in ids if srs.get(i, "").startswith("폐기")]
        if unknown:
            rep.add("S4", "FAIL", "SRS 표에 없는 요구사항 ID 참조 (오타 또는 미등재)", ", ".join(unknown))
        elif retired:
            rep.add("S4", "WARN", "폐기된 요구사항 ID 참조", ", ".join(retired))
        else:
            rep.add("S4", "PASS", "참조 ID 전부 SRS 표에 실존", f"{len(ids)}건: {', '.join(ids[:8])}{' …' if len(ids) > 8 else ''}")

    # S5 성공 기준 AC
    if "성공 기준" not in sections:
        rep.add("S5", "FAIL", "`## 성공 기준` 절 없음")
    else:
        s, e = sections["성공 기준"]
        acs, bad_bullets, seq_dup, wrong_ticket = [], [], [], []
        seen = set()
        for i in range(s + 1, e):
            l = lines[i]
            if not l.startswith("- "):
                continue
            m = AC_RE.match(l)
            if not m:
                bad_bullets.append(i + 1)
                continue
            acs.append(m.group(1))
            if ticket and m.group(2) != ticket:
                wrong_ticket.append(m.group(1))
            if len(m.group(3)) != 2:
                bad_bullets.append(i + 1)
            if m.group(3) in seen:
                seq_dup.append(m.group(1))
            seen.add(m.group(3))
        if not acs:
            rep.add("S5", "FAIL", "성공 기준에 `- AC-{티켓}-{NN}:` 최상위 불릿이 없음 (generate-rtm.sh 가 이 형식만 파싱, MSG-526)")
        elif wrong_ticket or seq_dup:
            rep.add("S5", "FAIL", "AC 번호 오류", f"티켓 불일치 {wrong_ticket or '없음'} / 순번 중복 {seq_dup or '없음'}")
        elif bad_bullets:
            rep.add("S5", "WARN", "성공 기준 안에 AC 형식이 아닌 최상위 불릿 (RTM 에 안 잡힘)", f"{len(acs)}건 AC + 비형식 {bad_bullets}행")
        else:
            rep.add("S5", "PASS", "AC 형식·번호 정상", f"{len(acs)}건 (AC-{ticket}-01 ~ {acs[-1].split('-')[-1]})")

    # S6 필수 절
    absent = [name for name, rx in REQUIRED_SECTIONS.items() if not any(rx.match(l) for l in lines)]
    if absent:
        rep.add("S6", "FAIL", "필수 절 누락 (agents/spec-writer.md 최소 구성)", ", ".join(absent))
    else:
        rep.add("S6", "PASS", "필수 절 8종 존재")

    # S7 줄표
    dash_lines = []
    in_code = False
    for i in range(body_end):
        l = lines[i]
        if l.lstrip().startswith("```"):
            in_code = not in_code
            continue
        if not in_code and DASH_RE.search(l):
            dash_lines.append(i + 1)
    if dash_lines:
        rep.add("S7", "FAIL", "본문에 줄표(— –) 사용 (2026-08-05 문체 조항: 마침표·쉼표·콜론·괄호로 대체)",
                f"{len(dash_lines)}줄: {dash_lines[:12]}{' …' if len(dash_lines) > 12 else ''}")
    else:
        rep.add("S7", "PASS", "본문 줄표 0건", f"검사 범위 1~{body_end}행 (코드블록 제외)")

    # S8 각주
    # 참조는 작업 로그까지 포함해 센다 (작업 로그에서만 쓰는 각주도 '참조됨'이다). 3~7 개수 판정도 같은 집합.
    refs = set(FOOT_REF_RE.findall(text))
    defs = set(m.group(1) for l in lines for m in [FOOT_DEF_RE.match(l)] if m)
    undefined = sorted(refs - defs, key=int)
    unused = sorted(defs - refs, key=int)
    if undefined or unused:
        rep.add("S8", "FAIL", "각주 참조와 정의 불일치", f"정의 없는 참조 {undefined or '없음'} / 참조 없는 정의 {unused or '없음'}")
    elif not refs:
        rep.add("S8", "WARN", "각주 0개 (문체 조항: 결론을 떠받치는 전문 용어 3~7개에 각주)")
    elif not (3 <= len(refs) <= 7):
        rep.add("S8", "WARN", "각주 개수가 3~7 범위 밖", f"{len(refs)}개")
    else:
        rep.add("S8", "PASS", "각주 참조·정의 일치", f"{len(refs)}개")

    # S9 변경 파일 경로
    cf_key = next((k for k in sections if k.startswith("변경 파일")), None)
    if not cf_key:
        rep.add("S9", "INFO", "`## 변경 파일` 절 없음 (선택 절. 구현 범위를 파일로 못 박으면 리뷰가 쉬워진다)")
    else:
        s, e = sections[cf_key]
        unresolved = []
        checked = 0
        for i in range(s + 1, e):
            l = lines[i]
            for p in PATH_TOKEN_RE.findall(l):
                checked += 1
                p = re.sub(r":\d+([~-]\d*)?$", "", p)  # `Foo.java:58`, `:42~43`, `:144~` 같은 줄 번호 접미사
                if (root / p).exists() or any(w in l for w in NEW_FILE_WORDS):
                    continue
                unresolved.append(f"{i + 1}행 {p}")
        if unresolved:
            rep.add("S9", "WARN", "실존하지 않고 신설/신규 표기도 없는 경로", "; ".join(unresolved[:6]) + (" …" if len(unresolved) > 6 else ""))
        else:
            rep.add("S9", "PASS", "변경 파일 경로 정합", f"전체 경로 {checked}건 확인")

    # S10 미해결 질문
    if "미해결 질문" in sections:
        s, e = sections["미해결 질문"]
        # 종결 표기: `- [x]`, `- ~~취소선~~`, 불릿 머리에 "종결"/"확정". `- [ ]` 와 맨 불릿은 미종결.
        open_items = [i + 1 for i in range(s + 1, e)
                      if lines[i].startswith("- ")
                      and not lines[i].startswith(("- [x]", "- [X]", "- ~~"))
                      and not re.search(r"종결|확정", lines[i][:40])]
        if open_items:
            rep.add("S10", "INFO", "미종결 질문 있음. 구현 착수 전에 해소한다 (스킬 절차 6)", f"{len(open_items)}건: {open_items}행")
        else:
            rep.add("S10", "PASS", "미해결 질문 없음 또는 전부 종결")
    else:
        rep.add("S10", "INFO", "`## 미해결 질문` 절 없음. 없으면 '없음'이라고 적는 편이 생략과 구분된다")

    return rep


def print_report(rep: Report, root: Path) -> None:
    rel = rep.path.relative_to(root) if rep.path.is_relative_to(root) else rep.path
    print(f"\n### {rel}")
    for r in rep.results:
        ev = f"  ({r.evidence})" if r.evidence else ""
        print(f"{r.level:4} {r.code:3} {r.title}{ev}")
    c = rep.counts()
    print(f"→ PASS {c['PASS']} / FAIL {c['FAIL']} / WARN {c['WARN']} / INFO {c['INFO']}")


def main(argv: list[str]) -> int:
    root = repo_root()
    os.chdir(root)
    srs = load_srs(root)
    if not argv or argv == ["-h"] or argv == ["--help"]:
        print(__doc__)
        return 2
    if argv == ["--all"]:
        files = sorted((root / "docs" / "spec").glob("*.md"))
        fails = 0
        print(f"| 파일 | FAIL 항목 | WARN 항목 |\n|---|---|---|")
        for f in files:
            rep = check(f, root, srs)
            fails += rep.failed
            fl = ",".join(r.code for r in rep.results if r.level == "FAIL") or "-"
            wl = ",".join(r.code for r in rep.results if r.level == "WARN") or "-"
            print(f"| {f.name} | {fl} | {wl} |")
        print(f"\n{len(files)}건 중 FAIL 있는 스펙 {fails}건")
        return 1 if fails else 0
    any_fail = False
    for a in argv:
        p = Path(a)
        if not p.is_absolute():
            p = root / p
        if not p.exists():
            print(f"FAIL --- 파일 없음: {a}")
            any_fail = True
            continue
        rep = check(p, root, srs)
        print_report(rep, root)
        any_fail |= rep.failed
    return 1 if any_fail else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
