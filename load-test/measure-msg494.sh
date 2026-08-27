#!/usr/bin/env bash
# MSG-494 분산 인코딩 실측과 복구 시험.
set -euo pipefail

usage() {
	cat <<'EOF'
사용법:
  BASE_URL=https://api.fillmap.kr \
  SSH_KEY_PATH=~/.ssh/fillmap-key-soma.pem \
  ./load-test/measure-msg494.sh video-1.mp4 video-2.mp4 video-3.mp4

  ALLOW_SERVICE_SIGNALS=true BASE_URL=https://api.fillmap.kr \
  SSH_KEY_PATH=~/.ssh/fillmap-key-soma.pem \
  ./load-test/measure-msg494.sh signal-term video.mp4

  # 두 노드 lease-duration=PT10S 적용·재시작 뒤 실행하고, 끝나면 PT35M로 복구한다.
  ALLOW_SERVICE_SIGNALS=true BASE_URL=https://api.fillmap.kr \
  SSH_KEY_PATH=~/.ssh/fillmap-key-soma.pem \
  ./load-test/measure-msg494.sh signal-kill video.mp4

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

valid_immediate_signal_state() {
	local mode="$1" initial_node="$2" initial_attempt="$3"
	local job_status="$4" node="$5" attempt="$6"
	if [[ "$mode" = "signal-term" ]]; then
		[[ ("$job_status" = "PENDING" && -z "$node" && "$attempt" -eq $((initial_attempt - 1))) \
			|| ("$job_status" = "PROCESSING" && "$node" != "$initial_node" \
				&& "$attempt" -eq "$initial_attempt") ]]
	else
		[[ "$job_status" = "PROCESSING" && "$node" = "$initial_node" \
			&& "$attempt" -eq "$initial_attempt" ]]
	fi
}

self_test() {
	local value statuses
	value=$(printf '%s' '{"data":{"videoId":42}}' | json_field data.videoId)
	[[ "$value" = "42" ]] || fail "json_field 자체 점검 실패"
	statuses=$(printf '%s' '{"data":[{"videoId":11,"processingStatus":"READY"},{"videoId":12,"processingStatus":"FAILED"}]}' \
		| statuses_for_ids 11 12)
	[[ "$statuses" = $'READY\nFAILED' ]] || fail "statuses_for_ids 자체 점검 실패"
	valid_immediate_signal_state signal-term be 1 PENDING "" 0 \
		|| fail "SIGTERM 반납 자체 점검 실패"
	valid_immediate_signal_state signal-term ai 1 PROCESSING be 1 \
		|| fail "SIGTERM 재선점 자체 점검 실패"
	valid_immediate_signal_state signal-kill be 1 PROCESSING be 1 \
		|| fail "SIGKILL 임대 유지 자체 점검 실패"
	if valid_immediate_signal_state signal-kill be 1 PENDING "" 0; then
		fail "SIGKILL 잘못된 반납 상태를 허용했다"
	fi
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

MODE=baseline
if [[ "${1:-}" = "signal-term" || "${1:-}" = "signal-kill" ]]; then
	MODE="$1"
	shift
	[[ $# -eq 1 ]] || { usage >&2; exit 1; }
else
	[[ $# -eq 3 ]] || { usage >&2; exit 1; }
fi

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

if [[ "$MODE" = "baseline" ]]; then
	FILES=("$1" "$2" "$3")
else
	[[ "${ALLOW_SERVICE_SIGNALS:-false}" = "true" ]] \
		|| fail "서비스 중단 시험은 ALLOW_SERVICE_SIGNALS=true를 명시해야 한다"
	FILES=("$1")
fi
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
if [[ "$MODE" = "baseline" ]]; then
	printf '%s\n' 'round,input_file,sha256,video_id,grid_id,confirmed_at_ms,terminal_at_ms,elapsed_ms,processing_status,job_status,claimed_by,attempt_count,notification_count,duplicate_notification_count' > "$RESULT_CSV"
else
	printf '%s\n' 'signal,input_file,video_id,initial_node,initial_attempt,immediate_status,immediate_node,immediate_attempt,terminal_status,final_job_status,final_node,final_attempt,elapsed_ms,notification_count' > "$RESULT_CSV"
fi

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

job_state() {
	db_query "
SELECT v.processing_status, j.status, COALESCE(j.claimed_by, ''), j.attempt_count
FROM videos v
JOIN video_encoding_jobs j ON j.video_id = v.id AND j.original_s3_key = v.original_s3_key
WHERE v.id = $1;"
}

wait_for_claim() {
	local video_id="$1" started current state video_status job_status node attempt
	started=$(now_ms)
	while true; do
		state=$(job_state "$video_id")
		if [[ -n "$state" ]]; then
			IFS='|' read -r video_status job_status node attempt <<< "$state"
			if [[ "$job_status" = "PROCESSING" && ("$node" = "be" || "$node" = "ai") ]]; then
				SIGNAL_INITIAL_STATE="$state"
				SIGNAL_INITIAL_NODE="$node"
				SIGNAL_INITIAL_ATTEMPT="$attempt"
				return
			fi
			[[ "$job_status" != "COMPLETED" && "$job_status" != "DEAD" ]] \
				|| fail "videoId=$video_id가 신호를 보내기 전에 종결됐다"
		fi
		current=$(now_ms)
		((current - started < TIMEOUT_SEC * 1000)) || fail "videoId=$video_id 선점을 기다리다 시간 초과"
		sleep 0.2
	done
}

wait_for_terminal() {
	local video_id="$1" started current state video_status job_status node attempt
	started=$(now_ms)
	while true; do
		state=$(job_state "$video_id")
		if [[ -n "$state" ]]; then
			IFS='|' read -r video_status job_status node attempt <<< "$state"
			if [[ ("$video_status" = "READY" || "$video_status" = "FAILED") \
				&& ("$job_status" = "COMPLETED" || "$job_status" = "DEAD") ]]; then
				SIGNAL_FINAL_STATE="$state"
				SIGNAL_TERMINAL_STATUS="$video_status"
				SIGNAL_FINAL_JOB_STATUS="$job_status"
				SIGNAL_FINAL_NODE="$node"
				SIGNAL_FINAL_ATTEMPT="$attempt"
				SIGNAL_TERMINAL_AT=$(now_ms)
				return
			fi
		fi
		current=$(now_ms)
		((current - started < TIMEOUT_SEC * 1000)) || fail "videoId=$video_id 복구를 기다리다 시간 초과"
		sleep 0.5
	done
}

has_short_lease() {
	local target="$1" service="$2"
	"${SSH[@]}" "$target" "
main_pid=\$(systemctl show -p MainPID --value '$service')
[ \"\$main_pid\" != 0 ]
sudo grep -zFxq 'FILLMAP_VIDEO_ENCODING_JOB_LEASE_DURATION=PT10S' \"/proc/\$main_pid/environ\"
"
}

wait_service_health() {
	local target="$1" service="$2" port="$3" deadline
	deadline=$(($(date +%s) + 60))
	while (($(date +%s) < deadline)); do
		if "${SSH[@]}" "$target" "
main_pid=\$(systemctl show -p MainPID --value '$service' 2>/dev/null || true)
[ -n \"\$main_pid\" ] && [ \"\$main_pid\" != 0 ]
curl -fsS -o /dev/null http://127.0.0.1:'$port'/actuator/health
"; then
			return
		fi
		sleep 1
	done
	return 1
}

SIGNALLED_TARGET=""
SIGNALLED_SERVICE=""
cleanup_signalled_service() {
	if [[ -n "$SIGNALLED_TARGET" ]]; then
		echo "중단한 $SIGNALLED_SERVICE 재기동" >&2
		"${SSH[@]}" "$SIGNALLED_TARGET" "sudo systemctl start '$SIGNALLED_SERVICE'" \
			|| echo "경고: $SIGNALLED_SERVICE 자동 재기동 실패" >&2
	fi
}

run_signal_test() {
	local video_id grid_id confirmed_at target service port immediate_state
	local immediate_video_status immediate_job_status immediate_node immediate_attempt
	local notification_count elapsed

	echo "결과 디렉터리: $RESULT_DIR"
	echo "입력: ${FILES[0]}  ${SHAS[0]}  ${DURATIONS[0]}초  ${SIZES[0]}B"
	remote_snapshot "$BE_SSH" fillmap-dev 8080 "$RESULT_DIR/be-before.txt"
	remote_snapshot "$AI_SSH" fillmap-encoding-worker 8081 "$RESULT_DIR/ai-before.txt"
	if [[ "$MODE" = "signal-kill" ]]; then
		has_short_lease "$BE_SSH" fillmap-dev \
			|| fail "fillmap-dev의 lease-duration이 PT10S가 아니다"
		has_short_lease "$AI_SSH" fillmap-encoding-worker \
			|| fail "fillmap-encoding-worker의 lease-duration이 PT10S가 아니다"
	fi
	queue_snapshot > "$RESULT_DIR/queue-before.txt"

	TOKEN=$(token)
	upload_one "$MODE" 1 "${FILES[0]}" "${DURATIONS[0]}" "${SIZES[0]}" \
		> "$RESULT_DIR/upload.tsv"
	IFS=$'\t' read -r video_id grid_id confirmed_at < "$RESULT_DIR/upload.tsv"
	wait_for_claim "$video_id"
	printf '%s\n' "$SIGNAL_INITIAL_STATE" > "$RESULT_DIR/initial-job.txt"

	case "$SIGNAL_INITIAL_NODE" in
		be)
			target="$BE_SSH"
			service=fillmap-dev
			port=8080
			;;
		ai)
			target="$AI_SSH"
			service=fillmap-encoding-worker
			port=8081
			;;
	esac
	SIGNALLED_TARGET="$target"
	SIGNALLED_SERVICE="$service"
	trap cleanup_signalled_service EXIT

	echo "$MODE: videoId=$video_id node=$SIGNAL_INITIAL_NODE attempt=$SIGNAL_INITIAL_ATTEMPT"
	if [[ "$MODE" = "signal-term" ]]; then
		"${SSH[@]}" "$target" "sudo systemctl stop '$service'"
		immediate_state=$(job_state "$video_id")
	else
		"${SSH[@]}" "$target" "sudo systemctl kill --kill-who=main --signal=KILL '$service'"
		immediate_state=$(job_state "$video_id")
		"${SSH[@]}" "$target" "sudo systemctl stop '$service'"
	fi
	printf '%s\n' "$immediate_state" > "$RESULT_DIR/immediate-job.txt"
	IFS='|' read -r immediate_video_status immediate_job_status immediate_node immediate_attempt \
		<<< "$immediate_state"

	valid_immediate_signal_state "$MODE" "$SIGNAL_INITIAL_NODE" "$SIGNAL_INITIAL_ATTEMPT" \
		"$immediate_job_status" "$immediate_node" "$immediate_attempt" \
		|| fail "$MODE 직후 작업 상태가 예상과 다르다: $immediate_state"

	wait_for_terminal "$video_id"
	printf '%s\n' "$SIGNAL_FINAL_STATE" > "$RESULT_DIR/final-job.txt"
	"${SSH[@]}" "$target" "sudo systemctl start '$service'"
	wait_service_health "$target" "$service" "$port" \
		|| fail "$service가 60초 안에 재기동되지 않았다"
	SIGNALLED_TARGET=""
	SIGNALLED_SERVICE=""
	trap - EXIT

	[[ "$SIGNAL_TERMINAL_STATUS" = "READY" && "$SIGNAL_FINAL_JOB_STATUS" = "COMPLETED" ]] \
		|| fail "복구 뒤 정상 종결되지 않았다: $SIGNAL_FINAL_STATE"
	[[ "$SIGNAL_FINAL_NODE" != "$SIGNAL_INITIAL_NODE" ]] \
		|| fail "중단하지 않은 노드가 작업을 회수하지 않았다: $SIGNAL_FINAL_STATE"
	if [[ "$MODE" = "signal-term" ]]; then
		[[ "$SIGNAL_FINAL_ATTEMPT" -eq "$SIGNAL_INITIAL_ATTEMPT" ]] \
			|| fail "SIGTERM이 시도 횟수를 소비했다: $SIGNAL_FINAL_STATE"
	else
		[[ "$SIGNAL_FINAL_ATTEMPT" -gt "$SIGNAL_INITIAL_ATTEMPT" ]] \
			|| fail "SIGKILL 뒤 임대 만료 재선점이 발생하지 않았다: $SIGNAL_FINAL_STATE"
	fi
	notification_count=$(db_query "
SELECT count(*) FROM notifications WHERE event_key LIKE 'VIDEO:${video_id}:%';")
	[[ "$notification_count" = "1" ]] || fail "videoId=$video_id 알림이 정확히 한 건이 아니다: $notification_count"
	elapsed=$((SIGNAL_TERMINAL_AT - confirmed_at))
	append_csv "$MODE" "${FILES[0]}" "$video_id" "$SIGNAL_INITIAL_NODE" "$SIGNAL_INITIAL_ATTEMPT" \
		"$immediate_job_status" "$immediate_node" "$immediate_attempt" "$SIGNAL_TERMINAL_STATUS" \
		"$SIGNAL_FINAL_JOB_STATUS" "$SIGNAL_FINAL_NODE" "$SIGNAL_FINAL_ATTEMPT" "$elapsed" "$notification_count"
	queue_snapshot > "$RESULT_DIR/queue-after.txt"
	remote_snapshot "$BE_SSH" fillmap-dev 8080 "$RESULT_DIR/be-after.txt"
	remote_snapshot "$AI_SSH" fillmap-encoding-worker 8081 "$RESULT_DIR/ai-after.txt"
	echo "완료: $RESULT_CSV"
	if [[ "$MODE" = "signal-kill" ]]; then
		echo "lease-duration을 두 노드 모두 PT35M로 복구하고 재시작한다." >&2
	fi
}

if [[ "$MODE" != "baseline" ]]; then
	run_signal_test
	exit 0
fi

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
