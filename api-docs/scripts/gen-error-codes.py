#!/usr/bin/env python3
"""src/main/java 의 *ErrorCode.java enum 을 읽어 docs/error-codes.md 를 만든다 (MSG-568).

코드가 정본이라 손으로 고치지 않는다. 대역 배정 규칙은 .claude/rules/response-pattern.md.
상수 형식: NAME(developCode, HttpStatus.X, "메시지") — 여러 줄에 걸쳐 있어도 잡는다.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src/main/java/com/msg/fillmap"
OUT = ROOT / "api-docs/docs/error-codes.md"

CONST = re.compile(
	r'([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,\s*HttpStatus\.([A-Z_0-9]+)\s*,\s*"((?:[^"\\]|\\.)*)"')

DOMAIN_LABEL = {
	"response": "공통",
	"user": "사용자 (user)", "auth": "인증 (auth)", "video": "영상 (video)", "grid": "격자 (grid)",
	"search": "장소 검색 (search)", "region": "행정동 (region)", "badge": "뱃지 (badge)",
	"hotzone": "핫구역 (hotzone)", "friend": "친구 (friend)", "notification": "알림 (notification)",
	"moderation": "신고·모더레이션 (moderation)", "mission": "미션 (mission)", "event": "행사 (event)",
	"route": "AI 경로 추천 (route)",
}


def domain_of(path: Path) -> str:
	rel = path.relative_to(SRC).parts
	return rel[0]


def main() -> int:
	rows = defaultdict(list)
	for f in sorted(SRC.rglob("*ErrorCode.java")):
		text = f.read_text(encoding="utf-8")
		body = text.split("enum", 1)[1] if "enum" in text else text
		found = CONST.findall(body)
		if not found:
			print(f"경고: {f.relative_to(ROOT)} 에서 상수를 하나도 못 읽었다", file=sys.stderr)
		for name, code, status, msg in found:
			rows[domain_of(f)].append((int(code), status, name, msg.replace("\\\"", '"'), f.name))
	if not rows:
		print("에러 코드 enum 을 하나도 못 찾았다", file=sys.stderr)
		return 1

	total = sum(len(v) for v in rows.values())
	lines = [
		"# 에러 코드",
		"",
		"모든 실패 응답은 `developCode` 로 원인을 구분한다. HTTP 상태는 응답 status line 으로만 오고 body 에는 없다.",
		"도메인마다 `developCode` 대역이 나뉘고, 공통 코드(4xx·5xx)는 모든 API 에서 나올 수 있다.",
		"",
		f"이 표는 서버 코드의 `*ErrorCode` enum 에서 빌드 때 자동으로 만든다. 현재 {len(rows)}개 도메인, {total}개 코드.",
		"",
		"```json",
		'{ "developCode": 2401, "message": "유효하지 않은 토큰입니다", "data": null }',
		"```",
		"",
	]
	# 공통을 맨 앞에, 나머지는 대역 오름차순
	order = sorted(rows, key=lambda d: (d != "response", min(c for c, *_ in rows[d])))
	for d in order:
		items = sorted(rows[d])
		label = DOMAIN_LABEL.get(d, d)
		lo, hi = items[0][0], items[-1][0]
		lines += [f"## {label}", "", f"대역 {lo}~{hi}, 정의 `{items[0][4]}`", "",
			"| developCode | HTTP | 상수 | 메시지 |", "|---|---|---|---|"]
		for code, status, name, msg, _ in items:
			lines.append(f"| `{code}` | {status} | `{name}` | {msg} |")
		lines.append("")
	OUT.write_text("\n".join(lines), encoding="utf-8")
	print(f"error-codes.md 생성: {len(rows)}개 도메인, {total}개 코드")
	return 0


if __name__ == "__main__":
	sys.exit(main())
