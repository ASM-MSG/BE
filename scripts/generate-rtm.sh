#!/usr/bin/env bash
# MSG-364: SRS 기능 요구사항 ↔ 테스트 추적성 매트릭스(RTM) 생성
#
# 원천 데이터 3개를 잇는다:
#   - docs/srs.md 의 FR 표 행: | FR-XXX-NN | 요구 문장 | 상태 | 근거 |
#   - docs/srs.md 8장의 테스트 비대상 목록: - FR-XXX-NN: 사유
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

# SRS 8장의 테스트 비대상 목록 (형식: "- FR-ID: 사유"). 성격상 테스트가 성립하지 않는
# 요구를 검증 공백에서 분리한다. 사유의 정본은 SRS라 여기 하드코딩하지 않는다 (MSG-375).
# 파싱은 해당 절 제목부터 다음 헤딩까지로 한정한다. 파일 전역 grep이면 다른 절의 평범한
# "- FR-...: " 불릿이 면제로 오인돼 진짜 공백을 숨긴다. 절 제목이 바뀌면 목록이 통째로
# 안 읽혀 8건이 검증 공백으로 되돌아오므로(fail-closed) 제목 변경은 여기와 같이 바꾼다.
nontest=$(sed -n '/^### 테스트로 검증하지 않는 요구/,/^#/p' "$SRS" | grep -E '^- FR-[A-Z]+-[0-9]+: ' || true)

rows=""
gap_impl=""
gap_plan=""
gap_nt=""
nt_check=""
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
		# 비대상으로 표기했는데 테스트가 연결됐다면 표기가 낡은 것이다. 조용히 두면
		# 테스트가 사라져도 공백 목록에 안 올라와 fail-open이 된다.
		if printf '%s\n' "$nontest" | grep -qE "^- ${id}: "; then
			nt_check="${nt_check}- (테스트가 연결돼 있음, SRS 비대상 표기 정리 필요) $id
"
		fi
	else
		rows="${rows}| $id | $status | (없음) |
"
		reason=$(printf '%s\n' "$nontest" | grep -E "^- ${id}: " | sed 's/^- [^:]*: //' || true)
		case "$status" in
			계획*|폐기*) gap_plan="${gap_plan}- $id ($status)
" ;;
			*) if [ -n "$reason" ]; then
				gap_nt="${gap_nt}- $id: $reason
"
			else
				gap_impl="${gap_impl}- $id ($status)
"
			fi ;;
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

# 비대상 목록에 있는데 FR 표에 없는 ID — 목록 오타 또는 표에서 삭제된 요구
if [ -n "$nontest" ]; then
	nt_unknown=$(comm -23 \
		<(printf '%s\n' "$nontest" | sed 's/^- //; s/:.*//' | sort -u) \
		<(grep -E '^\| FR-[A-Z]+-[0-9]+ \|' "$SRS" | awk -F'|' '{gsub(/ /,"",$2); print $2}' | sort -u))
	if [ -n "$nt_unknown" ]; then
		nt_check="${nt_check}$(printf '%s\n' "$nt_unknown" | sed 's/^/- (FR 표에 없음, 목록 오타 의심) /')
"
	fi
fi

gap_impl_count=$(printf '%s' "$gap_impl" | grep -c '^-' || true)
gap_plan_count=$(printf '%s' "$gap_plan" | grep -c '^-' || true)
gap_nt_count=$(printf '%s' "$gap_nt" | grep -c '^-' || true)

{
	echo "# 요구사항 추적성 매트릭스 (RTM)"
	echo
	echo "\`scripts/generate-rtm.sh\` 가 생성한다. 손으로 고치지 말 것. 원천은 테스트의 \`// 검증: FR-...\` 주석과 \`docs/srs.md\` 다."
	echo
	echo "**병합 충돌이 나면 손으로 합치지 말고 재생성한다.** 어느 쪽이든 골라 충돌만 없앤 뒤(\`git checkout --ours docs/rtm.md\` 등)"
	echo "스크립트를 다시 돌려 그 결과를 커밋한다. 이 표는 두 원천에서 계산되는 값이라 양쪽 diff를 섞으면 어느 쪽과도 다른 상태가 된다."
	echo
	echo "요약: FR ${total}건 중 테스트 연결 ${mapped}건, 검증 공백 ${gap_impl_count}건 (계획·폐기라 테스트 부재가 정상인 ${gap_plan_count}건, 성격상 테스트 비대상 ${gap_nt_count}건 별도)"
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
	echo "## 성격상 테스트로 검증하지 않는 요구 (사유 정본: SRS 8장 목록)"
	echo
	if [ -n "$gap_nt" ]; then printf '%s' "$gap_nt"; else echo "(없음)"; fi
	echo
	echo "## 비대상 표기 점검 (표기와 실제가 어긋난 항목)"
	echo
	if [ -n "$nt_check" ]; then printf '%s' "$nt_check"; else echo "(없음)"; fi
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
# 비대상 표기 어긋남은 경고가 아니라 실패다. 낡은 비대상 표기가 남은 채 통과하면, 나중에
# 그 FR의 마커가 삭제돼도 공백 목록 대신 비대상 절로 분류돼 회귀가 숨는다 (fail-open).
# malformed 마커와 달리 이쪽은 공백 목록이라는 안전망이 없어 생성 자체를 막는다.
if [ -n "$nt_check" ]; then
	echo "오류: 테스트 비대상 표기와 실제가 어긋났다 (rtm.md '비대상 표기 점검' 절 참조). docs/srs.md 8장 목록을 정리할 것." >&2
	exit 1
fi

echo "생성 완료: $OUT (FR ${total}건, 연결 ${mapped}건, 공백 ${gap_impl_count}건)"
