#!/usr/bin/env bash
# MSG-494 분산 인코딩 baseline 실측.
# 동일한 10~20초 MP4 세 개를 회차마다 동시에 확정하고, 총 3회 결과를 CSV로 남긴다.
set -euo pipefail

usage() {
	cat <<'EOF'
사용법:
  BASE_URL=https://api.fillmap.kr \
  SSH_KEY_PATH=~/.ssh/fillmap-key-soma.pem \
  ./load-test/measure-msg494.sh video-1.mp4 video-2.mp4 video-3.mp4

선택 환경변수:
  BE_SSH       기본 ubuntu@52.79.187.34
  AI_SSH       기본 ubuntu@52.78.158.240
  BENCH_OID    기본 msg494-bench
  LAT, LNG     기본 37.5665, 126.9780
  TIMEOUT_SEC  회차별 종결 대기, 기본 180
  RESULT_DIR   기본 build/reports/msg494/<실행시각>

자체 점검: ./load-test/measure-msg494.sh --self-test
EOF
}

fail() {
	echo "오류: $*" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || fail "$1 명령이 필요하다"
}

json_field() {
	python3 -c '
import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    value = value[key]
print(value)
' "$1"
}

statuses_for_ids() {
	python3 -c '
import json, sys
items = json.load(sys.stdin)["data"]
by_id = {str(item["videoId"]): item["processingStatus"] for item in items}
for video_id in sys.argv[1:]:
    print(by_id.get(video_id, "MISSING"))
' "$@"
}

now_ms() {
	python3 -c 'import time; print(time.time_ns() // 1_000_000)'
}

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | awk '{print $1}'
	else
		shasum -a 256 "$1" | awk '{print $1}'
	fi
}

append_csv() {
	python3 - "$RESULT_CSV" "$@" <<'PY'
import csv, sys
with open(sys.argv[1], "a", newline="", encoding="utf-8") as output:
    csv.writer(output).writerow(sys.argv[2:])
PY
}

self_test() {
	local value statuses
	value=$(printf '%s' '{"data":{"videoId":42}}' | json_field data.videoId)
	[[ "$value" = "42" ]] || fail "json_field 자체 점검 실패"
	statuses=$(printf '%s' '{"data":[{"videoId":11,"processingStatus":"READY"},{"videoId":12,"processingStatus":"FAILED"}]}' \
		| statuses_for_ids 11 12)
	[[ "$statuses" = $'READY\nFAILED' ]] || fail "statuses_for_ids 자체 점검 실패"
	echo "self-test 통과"
}

if [[ "${1:-}" = "--help" || "${1:-}" = "-h" ]]; then
	usage
	exit 0
fi
if [[ "${1:-}" = "--self-test" ]]; then
	self_test
	exit 0
fi
[[ $# -eq 3 ]] || { usage >&2; exit 1; }

require_command curl
require_command ffprobe
require_command python3
require_command ssh
require_command awk

BASE_URL="${BASE_URL:-}"
[[ -n "$BASE_URL" ]] || fail "실수로 다른 환경에 업로드하지 않도록 BASE_URL을 명시해야 한다"
BASE_URL="${BASE_URL%/}"
BE_SSH="${BE_SSH:-ubuntu@52.79.187.34}"
AI_SSH="${AI_SSH:-ubuntu@52.78.158.240}"
BENCH_OID="${BENCH_OID:-msg494-bench}"
LAT="${LAT:-37.5665}"
LNG="${LNG:-126.9780}"
TIMEOUT_SEC="${TIMEOUT_SEC:-180}"
RESULT_DIR="${RESULT_DIR:-build/reports/msg494/$(date -u +%Y%m%dT%H%M%SZ)}"
RESULT_CSV="$RESULT_DIR/results.csv"

[[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] && ((TIMEOUT_SEC > 0)) || fail "TIMEOUT_SEC는 양의 정수여야 한다"

SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10)
if [[ -n "${SSH_KEY_PATH:-}" ]]; then
	[[ -r "$SSH_KEY_PATH" ]] || fail "SSH_KEY_PATH를 읽을 수 없다: $SSH_KEY_PATH"
	SSH+=(-i "$SSH_KEY_PATH")
fi

FILES=("$1" "$2" "$3")
SHAS=()
DURATIONS=()
SIZES=()
for file in "${FILES[@]}"; do
	[[ -f "$file" && -r "$file" ]] || fail "영상 파일을 읽을 수 없다: $file"
	[[ "$file" != *$'\t'* && "$file" != *$'\n'* ]] || fail "파일명에 탭이나 줄바꿈을 쓸 수 없다: $file"
	extension=$(printf '%s' "${file##*.}" | tr '[:upper:]' '[:lower:]')
	[[ "$extension" = "mp4" ]] || fail "MP4만 받는다: $file"
	duration=$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$file")
	python3 - "$duration" "$file" <<'PY'
import sys
duration = float(sys.argv[1])
if not 10 <= duration <= 20:
    raise SystemExit(f"오류: 10~20초 영상만 받는다: {sys.argv[2]} ({duration:.3f}초)")
PY
	SHAS+=("$(sha256 "$file")")
	DURATIONS+=("$(python3 -c 'import sys; print(round(float(sys.argv[1])))' "$duration")")
	SIZES+=("$(wc -c < "$file" | tr -d ' ')")
done

mkdir -p "$RESULT_DIR"
printf '%s\n' 'round,input_file,sha256,video_id,grid_id,confirmed_at_ms,terminal_at_ms,elapsed_ms,processing_status,job_status,claimed_by,attempt_count,notification_count,duplicate_notification_count' > "$RESULT_CSV"

db_query() {
	printf '%s\n' "$1" | "${SSH[@]}" "$BE_SSH" \
		"docker exec -i fillmap-postgres-dev psql -v ON_ERROR_STOP=1 -U dev -d fillmap -At -F '|'"
}

queue_snapshot() {
	db_query "
SELECT status, count(*), min(enqueued_at), max(attempt_count)
FROM video_encoding_jobs
WHERE completed_at IS NULL
GROUP BY status
ORDER BY status;"
}

remote_snapshot() {
	local target="$1" service="$2" port="$3" output="$4"
	"${SSH[@]}" "$target" "
set -eu
echo timestamp_utc=\$(date -u +%Y-%m-%dT%H:%M:%SZ)
main_pid=\$(systemctl show -p MainPID --value '$service')
[ \"\$main_pid\" != 0 ]
curl -fsS -o /dev/null http://127.0.0.1:'$port'/actuator/health
echo service='$service' main_pid=\$main_pid
free -m
swapon --show
if command -v pidstat >/dev/null 2>&1 && [ \"\$main_pid\" != 0 ]; then
  pidstat -r -u -p \"\$main_pid\" 1 1
elif [ \"\$main_pid\" != 0 ]; then
  ps -o pid,pcpu,pmem,rss,vsz,etime,command -p \"\$main_pid\"
fi
" > "$output"
}

token() {
	local request
	request=$(python3 -c 'import json,sys; print(json.dumps({"provider": "KAKAO", "oid": sys.argv[1]}))' \
		"$BENCH_OID")
	curl -fsS -X POST "$BASE_URL/api/auth/dev/social-login" \
		-H 'Content-Type: application/json' \
		-d "$request" \
		| json_field data.accessToken
}

upload_one() {
	local round="$1" index="$2" file="$3" duration="$4" size="$5"
	local presign upload_url s3_key recorded_at request response confirmed_at video_id grid_id

	presign=$(curl -fsS -X POST "$BASE_URL/api/videos/presigned-url" \
		-H "Authorization: Bearer $TOKEN" \
		-H 'Content-Type: application/json' \
		-d "{\"extension\":\"mp4\",\"contentType\":\"video/mp4\",\"contentLength\":$size,\"purpose\":\"UPLOAD\"}")
	upload_url=$(printf '%s' "$presign" | json_field data.uploadUrl)
	s3_key=$(printf '%s' "$presign" | json_field data.s3Key)

	curl -fsS -o /dev/null -X PUT "$upload_url" -H 'Content-Type: video/mp4' --data-binary "@$file"
	recorded_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
	request=$(python3 -c '
import json, sys
print(json.dumps({"s3Key": sys.argv[1], "lat": float(sys.argv[2]), "lng": float(sys.argv[3]),
                  "durationSec": int(sys.argv[4]), "recordedAt": sys.argv[5], "visibility": "PRIVATE"}))
' "$s3_key" "$LAT" "$LNG" "$duration" "$recorded_at")
	response=$(curl -fsS -X POST "$BASE_URL/api/videos" \
		-H "Authorization: Bearer $TOKEN" \
		-H 'Content-Type: application/json' \
		-d "$request")
	confirmed_at=$(now_ms)
	video_id=$(printf '%s' "$response" | json_field data.videoId)
	grid_id=$(printf '%s' "$response" | json_field data.gridId)
	[[ "$video_id" =~ ^[0-9]+$ ]] || fail "videoId가 숫자가 아니다: $video_id"
	printf '%s\t%s\t%s\n' "$video_id" "$grid_id" "$confirmed_at"
	printf '%s\n' "$response" > "$RESULT_DIR/round-${round}-upload-${index}.json"
}

poll_round() {
	local round="$1" grid_id="$2"
	shift 2
	local ids=("$1" "$2" "$3")
	local terminal=("" "" "") terminal_at=("" "" "")
	local started current response lines status index finished
	started=$(now_ms)

	while true; do
		response=$(curl -fsS -H "Authorization: Bearer $TOKEN" \
			"$BASE_URL/api/grids/$grid_id/my-videos")
		lines=$(printf '%s' "$response" | statuses_for_ids "${ids[@]}")
		current=$(now_ms)
		finished=0
		for index in 0 1 2; do
			status=$(printf '%s\n' "$lines" | sed -n "$((index + 1))p")
			if [[ -z "${terminal[$index]}" && ("$status" = "READY" || "$status" = "FAILED") ]]; then
				terminal[$index]="$status"
				terminal_at[$index]="$current"
			fi
			[[ -n "${terminal[$index]}" ]] && finished=$((finished + 1))
		done
		[[ "$finished" -eq 3 ]] && break
		((current - started < TIMEOUT_SEC * 1000)) || fail "${round}회차가 ${TIMEOUT_SEC}초 안에 종결되지 않았다"
		sleep 0.5
	done

	for index in 0 1 2; do
		ROUND_TERMINAL[$index]="${terminal[$index]}"
		ROUND_TERMINAL_AT[$index]="${terminal_at[$index]}"
	done
}

echo "결과 디렉터리: $RESULT_DIR"
echo "입력 SHA-256:"
for index in 0 1 2; do
	echo "  ${FILES[$index]}  ${SHAS[$index]}  ${DURATIONS[$index]}초  ${SIZES[$index]}B"
done

TOKEN=$(token)
remote_snapshot "$BE_SSH" fillmap-dev 8080 "$RESULT_DIR/be-before.txt"
remote_snapshot "$AI_SSH" fillmap-encoding-worker 8081 "$RESULT_DIR/ai-before.txt"
queue_snapshot > "$RESULT_DIR/queue-before.txt"

ALL_VIDEO_IDS=()
SLOW=0
for round in 1 2 3; do
	echo "[$round/3] 세 영상 동시 업로드"
	pids=()
	for index in 0 1 2; do
		upload_one "$round" "$((index + 1))" "${FILES[$index]}" "${DURATIONS[$index]}" "${SIZES[$index]}" \
			> "$RESULT_DIR/round-${round}-upload-$((index + 1)).tsv" \
			2> "$RESULT_DIR/round-${round}-upload-$((index + 1)).err" &
		pids+=("$!")
	done
	upload_failed=0
	for pid in "${pids[@]}"; do
		wait "$pid" || upload_failed=1
	done
	if [[ "$upload_failed" -ne 0 ]]; then
		cat "$RESULT_DIR"/round-"$round"-upload-*.err >&2
		fail "${round}회차 업로드 실패"
	fi

	VIDEO_IDS=() GRID_IDS=() CONFIRMED_AT=()
	for index in 0 1 2; do
		IFS=$'\t' read -r video_id grid_id confirmed_at \
			< "$RESULT_DIR/round-${round}-upload-$((index + 1)).tsv"
		VIDEO_IDS+=("$video_id")
		GRID_IDS+=("$grid_id")
		CONFIRMED_AT+=("$confirmed_at")
		ALL_VIDEO_IDS+=("$video_id")
	done
	[[ "${GRID_IDS[0]}" = "${GRID_IDS[1]}" && "${GRID_IDS[0]}" = "${GRID_IDS[2]}" ]] \
		|| fail "같은 좌표의 업로드가 서로 다른 gridId를 반환했다"

	ROUND_TERMINAL=("" "" "") ROUND_TERMINAL_AT=("" "" "")
	poll_round "$round" "${GRID_IDS[0]}" \
		"${VIDEO_IDS[0]}" "${VIDEO_IDS[1]}" "${VIDEO_IDS[2]}"

	for index in 0 1 2; do
		job=$(db_query "
SELECT j.status, COALESCE(j.claimed_by, ''), j.attempt_count,
       (SELECT count(*) FROM notifications n WHERE n.event_key LIKE 'VIDEO:' || v.id || ':%')
FROM videos v
JOIN video_encoding_jobs j ON j.video_id = v.id AND j.original_s3_key = v.original_s3_key
WHERE v.id = ${VIDEO_IDS[$index]};")
		IFS='|' read -r job_status claimed_by attempt_count notification_count <<< "$job"
		[[ "$claimed_by" = "be" || "$claimed_by" = "ai" ]] \
			|| fail "videoId=${VIDEO_IDS[$index]}의 최종 처리 노드가 비어 있거나 알 수 없는 값이다: $claimed_by"
		elapsed=$((ROUND_TERMINAL_AT[$index] - CONFIRMED_AT[$index]))
		duplicate_count=$((notification_count > 1 ? notification_count - 1 : 0))
		if ((elapsed > 30000)); then
			SLOW=1
		fi
		append_csv "$round" "${FILES[$index]}" "${SHAS[$index]}" "${VIDEO_IDS[$index]}" \
			"${GRID_IDS[$index]}" "${CONFIRMED_AT[$index]}" "${ROUND_TERMINAL_AT[$index]}" "$elapsed" \
			"${ROUND_TERMINAL[$index]}" "$job_status" "$claimed_by" "$attempt_count" \
			"$notification_count" "$duplicate_count"
		echo "  videoId=${VIDEO_IDS[$index]} ${ROUND_TERMINAL[$index]} ${elapsed}ms node=$claimed_by attempt=$attempt_count notifications=$notification_count"
	done
done

ids_sql=$(IFS=,; echo "${ALL_VIDEO_IDS[*]}")
db_query "
SELECT count(*)
FROM videos v
LEFT JOIN video_encoding_jobs j ON j.video_id = v.id AND j.original_s3_key = v.original_s3_key
WHERE v.id = ANY(ARRAY[$ids_sql]::bigint[])
  AND (j.id IS NULL OR v.processing_status NOT IN ('READY', 'FAILED')
       OR j.status NOT IN ('COMPLETED', 'DEAD'));" \
	> "$RESULT_DIR/invariant-violations.txt"
db_query "
SELECT count(*)
FROM (
  SELECT v.id
  FROM videos v
  LEFT JOIN notifications n ON n.event_key LIKE 'VIDEO:' || v.id || ':%'
  WHERE v.id = ANY(ARRAY[$ids_sql]::bigint[])
  GROUP BY v.id
  HAVING count(n.id) <> 1
) anomalies;" > "$RESULT_DIR/notification-anomalies.txt"
queue_snapshot > "$RESULT_DIR/queue-after.txt"
remote_snapshot "$BE_SSH" fillmap-dev 8080 "$RESULT_DIR/be-after.txt"
remote_snapshot "$AI_SSH" fillmap-encoding-worker 8081 "$RESULT_DIR/ai-after.txt"

violations=$(tr -d '[:space:]' < "$RESULT_DIR/invariant-violations.txt")
[[ "$violations" = "0" ]] || fail "종결 불변식 위반 영상이 $violations건이다"
notification_anomalies=$(tr -d '[:space:]' < "$RESULT_DIR/notification-anomalies.txt")
[[ "$notification_anomalies" = "0" ]] || fail "알림이 정확히 한 건이 아닌 영상이 ${notification_anomalies}개다"

echo "완료: $RESULT_CSV"
if [[ "$SLOW" -eq 1 ]]; then
	echo "30초 초과 영상이 있다. 두 노드의 poll interval을 PT0.2S로 낮춘 뒤 같은 입력으로 다시 측정한다." >&2
	exit 2
fi
