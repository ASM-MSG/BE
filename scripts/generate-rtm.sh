#!/usr/bin/env bash
# MSG-364: SRS 기능 요구사항 ↔ 테스트 추적성 매트릭스(RTM) 생성
#
# 원천 데이터 2개를 잇는다:
#   - docs/srs.md 의 FR 표 행: | FR-XXX-NN | 요구 문장 | 상태 | 근거 |
#   - src/test/java 의 테스트 주석: // 검증: FR-XXX-NN
# 산출물 docs/rtm.md 는 이 스크립트가 덮어쓴다. 손으로 고치지 말 것.
#
# 사용법: scripts/generate-rtm.sh  (레포 어디서 실행해도 된다)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
SRS=docs/srs.md
OUT=docs/rtm.md
TEST_DIR=src/test/java

# NFR-XXX-NN 의 부분 문자열(FR-XXX-NN)이 잡히지 않도록 경계(\b)를 고정한다.
FR_RE='\bFR-[A-Z]+-[0-9]+'

rows=""
gap_impl=""
gap_plan=""
total=0
mapped=0

while IFS='|' read -r _ id_raw _ status_raw _; do
	id=$(printf '%s' "$id_raw" | tr -d ' ')
	status=$(printf '%s' "$status_raw" | sed 's/^ *//; s/ *$//')
	total=$((total + 1))
	# 마커 줄(// 검증:)에서만 센다. 파일 아무 데나(TODO·문자열·설명 주석) ID가 언급돼도
	# 매핑으로 치면 공백 목록에서 조용히 빠진다. ID 양끝은 구분자(공백·쉼표·줄끝)로 못박는다.
	# \b 경계만으로는 FR-XXX-01-extra 나 FR-XXX-01. 같은 오타 토큰의 유효 접두어가 매핑으로
	# 잡혀, 같은 토큰이 형식 오류 목록과 매핑에 동시에 오르는 모순이 생긴다.
	# 무매칭(exit 1)만 정상 취급한다. exit 2(디렉터리 부재·권한 등 실제 스캔 실패)를 함께 삼키면
	# 매핑돼 있는 요구가 "미검증"으로 둔갑한 매트릭스가 성공인 척 나간다.
	rc=0
	files_raw=$(grep -rlE "// *검증:.*[ ,	]${id}([ ,	]|$)" "$TEST_DIR" 2>&1) || rc=$?
	if [ "$rc" -ge 2 ]; then
		echo "오류: 테스트 스캔 실패 (grep exit $rc, id=$id): $files_raw" >&2
		exit 1
	fi
	files=$(printf '%s' "$files_raw" | sed 's|.*/||; s|\.java$||' | sort -u | tr '\n' ',' | sed 's/,$//; s/,/, /g')
	if [ -n "$files" ]; then
		mapped=$((mapped + 1))
		rows="${rows}| $id | $status | $files |
"
	else
		rows="${rows}| $id | $status | (없음) |
"
		case "$status" in
			계획*|폐기*) gap_plan="${gap_plan}- $id ($status)
" ;;
			*) gap_impl="${gap_impl}- $id ($status)
" ;;
		esac
	fi
done < <(grep -E '^\| FR-[A-Z]+-[0-9]+ \|' "$SRS")

# SRS 표가 한 행도 안 잡혔으면 소스 문제(파일 부재·표 형식 변경)다. 빈 매트릭스를
# 정본인 양 덮어쓰지 않고 실패한다.
if [ "$total" -eq 0 ]; then
	echo "오류: $SRS 에서 FR 표 행을 하나도 못 읽었다. 파일 존재와 표 형식(| FR-XXX-NN | ... |)을 확인할 것." >&2
	exit 1
fi

# 테스트 마커에는 있는데 SRS에 없는 ID — 주석 오타 또는 SRS에서 삭제된 요구
orphans=$(comm -23 \
	<( (grep -rh "// *검증:" "$TEST_DIR" 2>/dev/null || true) | grep -oE "$FR_RE" | sort -u) \
	<(grep -oE "$FR_RE" "$SRS" | sort -u))

# 마커 줄의 형식 오류 토큰 — FR-AUTH-01X 처럼 꼬리가 붙으면 위 정규식이 유효 접두어만 뽑아
# 오타가 orphan 탐지를 피해간다. 엄격 형식에 어긋나는 토큰을 따로 잡는다.
malformed=$( { (grep -rh "// *검증:" "$TEST_DIR" 2>/dev/null || true) | sed 's/.*검증://' \
	| tr ' ,	' '\n\n\n' | grep 'FR-' | grep -vE '^FR-[A-Z]+-[0-9]+$' | sort -u; } || true)

gap_impl_count=$(printf '%s' "$gap_impl" | grep -c '^-' || true)
gap_plan_count=$(printf '%s' "$gap_plan" | grep -c '^-' || true)

{
	echo "# 요구사항 추적성 매트릭스 (RTM)"
	echo
	echo "\`scripts/generate-rtm.sh\` 가 생성한다. 손으로 고치지 말 것. 원천은 테스트의 \`// 검증: FR-...\` 주석과 \`docs/srs.md\` 다."
	echo
	echo "요약: FR ${total}건 중 테스트 연결 ${mapped}건, 검증 공백 ${gap_impl_count}건 (계획·폐기라 테스트 부재가 정상인 ${gap_plan_count}건 별도)"
	echo
	echo "| 요구사항 ID | SRS 상태 | 검증 테스트 |"
	echo "|---|---|---|"
	printf '%s' "$rows"
	echo
	echo "## 검증 공백: 구현됐는데 대응 테스트가 없다 (조치 대상)"
	echo
	if [ -n "$gap_impl" ]; then printf '%s' "$gap_impl"; else echo "(없음)"; fi
	echo
	echo "## 미구현(계획)·폐기: 테스트 부재가 정상"
	echo
	if [ -n "$gap_plan" ]; then printf '%s' "$gap_plan"; else echo "(없음)"; fi
	echo
	echo "## 테스트에만 있고 SRS에 없는 ID (주석 오타 의심)"
	echo
	if [ -n "$orphans" ]; then printf '%s\n' "$orphans" | sed 's/^/- /'; else echo "(없음)"; fi
	echo
	echo "## 형식이 어긋난 마커 토큰 (매핑 집계에서 무시됨)"
	echo
	if [ -n "$malformed" ]; then printf '%s\n' "$malformed" | sed 's/^/- /'; else echo "(없음)"; fi
} > "$OUT"

if [ -n "$malformed" ]; then
	echo "경고: 형식이 어긋난 검증 마커 토큰이 있다 (rtm.md 하단 참조)" >&2
fi

echo "생성 완료: $OUT (FR ${total}건, 연결 ${mapped}건, 공백 ${gap_impl_count}건)"
