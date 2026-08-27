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

  # 두 노드 lease-duration=PT1M 적용·재시작 뒤 실행하고, 끝나면 PT35M로 복구한다.
  ALLOW_SERVICE_SIGNALS=true BASE_URL=https://api.fillmap.kr \
  SSH_KEY_PATH=~/.ssh/fillmap-key-soma.pem \
  ./load-test/measure-msg494.sh signal-kill video.mp4

  # AI job이 PROCESSING인 BLURRING 영상과 새 영상 두 개를 겹쳐 처리한다.
  BASE_URL=https://api.fillmap.kr SSH_KEY_PATH=~/.ssh/fillmap-key-soma.pem \
  ./load-test/measure-msg494.sh blur 123 video-1.mp4 video-2.mp4

선택 환경변수:
  BE_SSH       기본 ubuntu@52.79.187.34
  AI_SSH       기본 ubuntu@52.78.158.240
  BENCH_OID    기본 msg494-bench
  LAT, LNG     기본 37.5665, 126.9780
  TIMEOUT_SEC  종결 대기, 기본 180(블러 300)
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

is_terminal_state() {
	[[ ("$1" = "READY" || "$1" = "FAILED") \
		&& ("$2" = "COMPLETED" || "$2" = "DEAD") ]]
}

default_timeout_sec() {
	if [[ "$1" = "blur" ]]; then
		echo 300
	else
		echo 180
	fi
}

self_test() {
	local value statuses
	value=$(printf '%s' '{"data":{"videoId":42}}' | json_field data.videoId)
	[[ "$value" = "42" ]] || fail "json_field 자체 점검 실패"
	statuses=$(printf '%s' \
		'{"data":[{"videoId":11,"processingStatus":"READY"},{"videoId":12,"processingStatus":"FAILED"}]}' \
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
	is_terminal_state READY COMPLETED || fail "종결 상태 자체 점검 실패"
	if is_terminal_state BLURRING COMPLETED; then
		fail "BLURRING을 종결 상태로 허용했다"
	fi
	[[ "$(default_timeout_sec baseline)" = "180" ]] || fail "기본 대기 시간 자체 점검 실패"
	[[ "$(default_timeout_sec blur)" = "300" ]] || fail "블러 대기 시간 자체 점검 실패"
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
elif [[ "${1:-}" = "blur" ]]; then
	MODE=blur
	shift
	[[ $# -eq 3 ]] || { usage >&2; exit 1; }
	BLURRING_VIDEO_ID="$1"
	shift
	[[ "$BLURRING_VIDEO_ID" =~ ^[0-9]+$ ]] || fail "BLURRING videoId는 숫자여야 한다"
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
TIMEOUT_SEC="${TIMEOUT_SEC:-$(default_timeout_sec "$MODE")}"
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
elif [[ "$MODE" = "blur" ]]; then
	FILES=("$1" "$2")
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
	printf '%s%s\n' \
		'round,input_file,sha256,video_id,grid_id,confirmed_at_ms,terminal_at_ms,elapsed_ms,' \
		'processing_status,job_status,claimed_by,attempt_count,notification_count,duplicate_notification_count' \
		> "$RESULT_CSV"
elif [[ "$MODE" = "blur" ]]; then
	printf '%s%s%s\n' \
		'role,input_file,video_id,observed_at_ms,terminal_at_ms,elapsed_ms,processing_status,job_status,' \
		'claimed_by,attempt_count,notification_count_before,notification_count_after,' \
		'notification_count_delta' > "$RESULT_CSV"
else
	printf '%s%s\n' \
		'signal,input_file,video_id,initial_node,initial_attempt,immediate_status,immediate_node,immediate_attempt,' \
		'terminal_status,final_job_status,final_node,final_attempt,elapsed_ms,notification_count' > "$RESULT_CSV"
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
			if is_terminal_state "$video_status" "$job_status"; then
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

video_states() {
	local ids_sql
	ids_sql=$(IFS=,; echo "$*")
	db_query "
SELECT v.id, v.processing_status, j.status, COALESCE(j.claimed_by, ''), j.attempt_count,
       COALESCE(v.ai_job_id, '')
FROM videos v
JOIN video_encoding_jobs j ON j.video_id = v.id AND j.original_s3_key = v.original_s3_key
WHERE v.id = ANY(ARRAY[$ids_sql]::bigint[])
ORDER BY array_position(ARRAY[$ids_sql]::bigint[], v.id);"
}

ai_job_status() {
	local job_id="$1"
	[[ "$job_id" =~ ^[A-Za-z0-9_-]+$ ]] || fail "AI jobId 형식이 올바르지 않다: $job_id"
	"${SSH[@]}" "$AI_SSH" "curl -fsS 'http://127.0.0.1:8000/jobs/$job_id'" | json_field status
}

video_notification_count() {
	db_query "SELECT count(*) FROM notifications WHERE event_key LIKE 'VIDEO:$1:%';"
}

poll_blur_videos() {
	local ids=("$1" "$2" "$3") terminal=("" "" "") terminal_at=("" "" "")
	local started current states line video_id video_status job_status node attempt ai_job_id index finished
	started=$(now_ms)
	while true; do
		states=$(video_states "${ids[@]}")
		current=$(now_ms)
		finished=0
		for index in 0 1 2; do
			line=$(printf '%s\n' "$states" | sed -n "$((index + 1))p")
			IFS='|' read -r video_id video_status job_status node attempt ai_job_id <<< "$line"
			[[ "$video_id" = "${ids[$index]}" ]] || fail "videoId=${ids[$index]} 작업 행을 찾지 못했다"
			if [[ -z "${terminal[$index]}" ]] && is_terminal_state "$video_status" "$job_status"; then
				terminal[$index]="$video_status"
				terminal_at[$index]="$current"
			fi
			[[ -n "${terminal[$index]}" ]] && finished=$((finished + 1))
		done
		[[ "$finished" -eq 3 ]] && break
		((current - started < TIMEOUT_SEC * 1000)) \
			|| fail "블러 경합 영상이 ${TIMEOUT_SEC}초 안에 종결되지 않았다"
		sleep 0.5
	done
	for index in 0 1 2; do
		BLUR_TERMINAL[$index]="${terminal[$index]}"
		BLUR_TERMINAL_AT[$index]="${terminal_at[$index]}"
	done
}

wait_for_blur_overlap() {
	local ids=("$1" "$2" "$3") started current states blur_line first_line second_line
	local blur_id blur_status blur_job blur_node blur_attempt blur_ai_job_id blur_ai_status
	local first_id first_status first_job first_node first_attempt first_ai_job_id
	local second_id second_status second_job second_node second_attempt second_ai_job_id
	started=$(now_ms)
	while true; do
		states=$(video_states "${ids[@]}")
		blur_line=$(printf '%s\n' "$states" | sed -n '1p')
		first_line=$(printf '%s\n' "$states" | sed -n '2p')
		second_line=$(printf '%s\n' "$states" | sed -n '3p')
		IFS='|' read -r blur_id blur_status blur_job blur_node blur_attempt blur_ai_job_id <<< "$blur_line"
		IFS='|' read -r first_id first_status first_job first_node first_attempt first_ai_job_id <<< "$first_line"
		IFS='|' read -r second_id second_status second_job second_node second_attempt second_ai_job_id <<< "$second_line"
		[[ "$blur_id" = "${ids[0]}" && "$first_id" = "${ids[1]}" && "$second_id" = "${ids[2]}" ]] \
			|| fail "블러 경합 대상의 작업 행을 찾지 못했다"
		[[ "$blur_status" = "BLURRING" ]] || fail "기존 블러 영상이 인코딩 경합 전에 종결됐다: $blur_status"
		if [[ "$first_job" = "PROCESSING" && "$second_job" = "PROCESSING" ]]; then
			blur_ai_status=$(ai_job_status "$blur_ai_job_id")
			[[ "$blur_ai_status" = "PROCESSING" ]] \
				|| fail "새 인코딩 두 건이 실행될 때 AI job이 PROCESSING이 아니다: $blur_ai_status"
			[[ ("$first_node" = "be" && "$second_node" = "ai") \
				|| ("$first_node" = "ai" && "$second_node" = "be") ]] \
				|| fail "새 인코딩 두 건이 BE와 AI에 하나씩 분배되지 않았다: $states"
			BLUR_OVERLAP_STATE="$states"$'\n'"AI|$blur_ai_job_id|$blur_ai_status"
			return
		fi
		[[ "$first_job" != "COMPLETED" && "$first_job" != "DEAD" \
			&& "$second_job" != "COMPLETED" && "$second_job" != "DEAD" ]] \
			|| fail "두 인코딩이 동시에 PROCESSING인 구간을 관측하지 못했다"
		current=$(now_ms)
		((current - started < TIMEOUT_SEC * 1000)) \
			|| fail "블러와 두 인코딩의 동시 실행을 기다리다 시간 초과"
		sleep 0.2
	done
}

has_test_lease() {
	local target="$1" service="$2"
	"${SSH[@]}" "$target" "
main_pid=\$(systemctl show -p MainPID --value '$service')
[ \"\$main_pid\" != 0 ]
sudo grep -zFxq 'FILLMAP_VIDEO_ENCODING_JOB_LEASE_DURATION=PT1M' \"/proc/\$main_pid/environ\"
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
		has_test_lease "$BE_SSH" fillmap-dev \
			|| fail "fillmap-dev의 lease-duration이 PT1M이 아니다"
		has_test_lease "$AI_SSH" fillmap-encoding-worker \
			|| fail "fillmap-encoding-worker의 lease-duration이 PT1M이 아니다"
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
	[[ "$notification_count" = "1" ]] \
		|| fail "videoId=$video_id 알림이 정확히 한 건이 아니다: $notification_count"
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

run_blur_test() {
	local initial_state initial_video_status initial_job_status initial_node initial_attempt observed_at
	local blur_ai_job_id blur_ai_status
	local final_states line video_id video_status job_status node attempt ai_job_id
	local notification_before notification_after notification_delta elapsed
	local upload_failed pid index
	local roles=("existing-blur" "new-1" "new-2") input_files=("" "${FILES[0]}" "${FILES[1]}")

	echo "결과 디렉터리: $RESULT_DIR"
	echo "기존 BLURRING videoId=$BLURRING_VIDEO_ID"
	remote_snapshot "$BE_SSH" fillmap-dev 8080 "$RESULT_DIR/be-before.txt"
	remote_snapshot "$AI_SSH" fillmap-encoding-worker 8081 "$RESULT_DIR/ai-before.txt"
	queue_snapshot > "$RESULT_DIR/queue-before.txt"
	initial_state=$(job_state "$BLURRING_VIDEO_ID")
	IFS='|' read -r initial_video_status initial_job_status initial_node initial_attempt <<< "$initial_state"
	[[ "$initial_video_status" = "BLURRING" && "$initial_job_status" = "COMPLETED" ]] \
		|| fail "videoId=$BLURRING_VIDEO_ID가 완료된 인코딩 뒤 BLURRING 상태가 아니다: $initial_state"
	blur_ai_job_id=$(db_query "SELECT COALESCE(ai_job_id, '') FROM videos WHERE id = $BLURRING_VIDEO_ID;")
	blur_ai_status=$(ai_job_status "$blur_ai_job_id")
	[[ "$blur_ai_status" = "PROCESSING" ]] \
		|| fail "videoId=$BLURRING_VIDEO_ID의 AI job이 PROCESSING이 아니다: $blur_ai_status"
	printf '%s\n' "$initial_state" "AI|$blur_ai_job_id|$blur_ai_status" > "$RESULT_DIR/blur-before.txt"
	observed_at=$(now_ms)
	BLUR_NOTIFICATION_BASELINE=("$(video_notification_count "$BLURRING_VIDEO_ID")")

	TOKEN=$(token)
	pids=()
	for index in 0 1; do
		upload_one blur "$((index + 1))" "${FILES[$index]}" "${DURATIONS[$index]}" "${SIZES[$index]}" \
			> "$RESULT_DIR/blur-upload-$((index + 1)).tsv" \
			2> "$RESULT_DIR/blur-upload-$((index + 1)).err" &
		pids+=("$!")
	done
	upload_failed=0
	for pid in "${pids[@]}"; do
		wait "$pid" || upload_failed=1
	done
	if [[ "$upload_failed" -ne 0 ]]; then
		cat "$RESULT_DIR"/blur-upload-*.err >&2
		fail "블러 경합용 업로드 실패"
	fi

	BLUR_VIDEO_IDS=("$BLURRING_VIDEO_ID")
	BLUR_OBSERVED_AT=("$observed_at")
	BLUR_GRID_IDS=()
	for index in 0 1; do
		IFS=$'\t' read -r video_id grid_id confirmed_at \
			< "$RESULT_DIR/blur-upload-$((index + 1)).tsv"
		BLUR_VIDEO_IDS+=("$video_id")
		BLUR_GRID_IDS+=("$grid_id")
		BLUR_OBSERVED_AT+=("$confirmed_at")
		BLUR_NOTIFICATION_BASELINE+=("$(video_notification_count "$video_id")")
	done
	[[ "${BLUR_GRID_IDS[0]}" = "${BLUR_GRID_IDS[1]}" ]] \
		|| fail "같은 좌표의 새 영상이 서로 다른 gridId를 반환했다"

	wait_for_blur_overlap "${BLUR_VIDEO_IDS[@]}"
	printf '%s\n' "$BLUR_OVERLAP_STATE" > "$RESULT_DIR/overlap-state.txt"
	BLUR_TERMINAL=("" "" "")
	BLUR_TERMINAL_AT=("" "" "")
	poll_blur_videos "${BLUR_VIDEO_IDS[@]}"
	final_states=$(video_states "${BLUR_VIDEO_IDS[@]}")
	printf '%s\n' "$final_states" > "$RESULT_DIR/final-states.txt"

	for index in 0 1 2; do
		line=$(printf '%s\n' "$final_states" | sed -n "$((index + 1))p")
		IFS='|' read -r video_id video_status job_status node attempt ai_job_id <<< "$line"
		[[ "$video_id" = "${BLUR_VIDEO_IDS[$index]}" && ("$node" = "be" || "$node" = "ai") ]] \
			|| fail "블러 경합 영상의 최종 작업 상태가 올바르지 않다: $line"
		notification_before="${BLUR_NOTIFICATION_BASELINE[$index]}"
		notification_after=$(video_notification_count "$video_id")
		notification_delta=$((notification_after - notification_before))
		[[ "$notification_delta" = "1" ]] \
			|| fail "videoId=$video_id의 이번 시도 알림 증가분이 1건이 아니다: $notification_delta"
		elapsed=$((BLUR_TERMINAL_AT[$index] - BLUR_OBSERVED_AT[$index]))
		append_csv "${roles[$index]}" "${input_files[$index]}" "$video_id" \
			"${BLUR_OBSERVED_AT[$index]}" "${BLUR_TERMINAL_AT[$index]}" "$elapsed" \
			"$video_status" "$job_status" "$node" "$attempt" "$notification_before" \
			"$notification_after" "$notification_delta"
		echo "  ${roles[$index]} videoId=$video_id $video_status ${elapsed}ms node=$node"
	done
	queue_snapshot > "$RESULT_DIR/queue-after.txt"
	remote_snapshot "$BE_SSH" fillmap-dev 8080 "$RESULT_DIR/be-after.txt"
	remote_snapshot "$AI_SSH" fillmap-encoding-worker 8081 "$RESULT_DIR/ai-after.txt"
	echo "완료: $RESULT_CSV"
}

if [[ "$MODE" = "blur" ]]; then
	run_blur_test
	exit 0
elif [[ "$MODE" != "baseline" ]]; then
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
			|| fail \
				"videoId=${VIDEO_IDS[$index]}의 최종 처리 노드가 비어 있거나 알 수 없는 값이다: $claimed_by"
		elapsed=$((ROUND_TERMINAL_AT[$index] - CONFIRMED_AT[$index]))
		duplicate_count=$((notification_count > 1 ? notification_count - 1 : 0))
		if ((elapsed > 30000)); then
			SLOW=1
		fi
		append_csv "$round" "${FILES[$index]}" "${SHAS[$index]}" "${VIDEO_IDS[$index]}" \
			"${GRID_IDS[$index]}" "${CONFIRMED_AT[$index]}" "${ROUND_TERMINAL_AT[$index]}" "$elapsed" \
			"${ROUND_TERMINAL[$index]}" "$job_status" "$claimed_by" "$attempt_count" \
			"$notification_count" "$duplicate_count"
		printf '  videoId=%s %s %sms node=%s attempt=%s notifications=%s\n' \
			"${VIDEO_IDS[$index]}" "${ROUND_TERMINAL[$index]}" "$elapsed" "$claimed_by" \
			"$attempt_count" "$notification_count"
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
[[ "$notification_anomalies" = "0" ]] \
	|| fail "알림이 정확히 한 건이 아닌 영상이 ${notification_anomalies}개다"

echo "완료: $RESULT_CSV"
if [[ "$SLOW" -eq 1 ]]; then
	echo "30초 초과 영상이 있다. 두 노드의 poll interval을 PT0.2S로 낮춘 뒤" \
		"같은 입력으로 다시 측정한다." >&2
	exit 2
fi
