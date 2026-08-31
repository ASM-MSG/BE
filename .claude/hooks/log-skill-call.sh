#!/bin/sh
# Skill 도구 호출을 로그로 남긴다 — 스킬 자동 호출 회귀 확인의 판정 근거 (MSG-529)
# 입력: PostToolUse hook 표준 입력 JSON. 출력: .claude/logs/skill-calls.jsonl 한 줄 append.
DIR="${CLAUDE_PROJECT_DIR:-.}"
mkdir -p "$DIR/.claude/logs"
python3 -c '
import datetime
import json
import sys

d = json.load(sys.stdin)
entry = {
	"ts": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
	"session": d.get("session_id", ""),
	"skill": d.get("tool_input", {}).get("skill", ""),
	"args": d.get("tool_input", {}).get("args", ""),
}
with open(sys.argv[1] + "/.claude/logs/skill-calls.jsonl", "a", encoding="utf-8") as f:
	f.write(json.dumps(entry, ensure_ascii=False) + "\n")
' "$DIR"
