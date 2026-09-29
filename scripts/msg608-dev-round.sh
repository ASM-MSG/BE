#!/usr/bin/env bash
# MSG-608 dev 회차 스크립트. 사용: ~/msg608-round.sh on|off [jitlog]   (ITER=1000 env 로 반복 횟수 조정,
#   EXTRA="KEY=V[;KEY2=V2]" 로 env 줄을 더한다 — 예: EXTRA="SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30")
# env 파일 끝의 "# MSG-608" 블록을 갈아 끼우고 API 컨테이너를 재생성한다. 원본은 fillmap-dev.env.bak-msg608 에 보관.
set -euo pipefail
TAG=${TAG:-msg608-b15fd3a-wt1}
ENV=~/fillmap-dev.env
[[ -f ~/fillmap-dev.env.bak-msg608 ]] || cp "$ENV" ~/fillmap-dev.env.bak-msg608
sed -i '/^# MSG-608/,$d' "$ENV"
{
	echo "# MSG-608 temp block ($(date -u +%FT%TZ) mode=$1 ${2:-})"
	echo LOGGING_LEVEL_ORG_HIBERNATE_SQL=INFO
	echo SPRING_JPA_SHOW_SQL=false
	echo SPRING_JPA_PROPERTIES_HIBERNATE_FORMAT_SQL=false
	echo SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true
	if [[ $1 == on ]]; then echo FILLMAP_WARMUP_ENABLED=true; else echo FILLMAP_WARMUP_ENABLED=false; fi
	echo "FILLMAP_WARMUP_ITERATIONS=${ITER:-1000}"
	echo FILLMAP_WARMUP_GRID_USER_ID=586
	if [[ ${2:-} == jitlog ]]; then
		echo "JAVA_TOOL_OPTIONS=-Xlog:class+load:file=/tmp/classload.log -Xlog:jit+compilation=debug:file=/tmp/jit.log"
	fi
	if [[ -n ${EXTRA:-} ]]; then tr ';' '\n' <<< "$EXTRA"; fi
} >> "$ENV"
cd ~
t0=$(date +%s)
# --force-recreate: env 줄이 같은 회차를 반복하면 compose 가 재생성을 건너뛴다(블록 헤더의 시각은 주석이라 diff 에 안 잡힘)
TAG=$TAG docker compose -f docker-compose.app.yml up -d --force-recreate --wait api
echo "healthy after $(( $(date +%s) - t0 ))s at $(date -u +%T)Z  $(docker inspect fillmap-api --format '{{.State.Health.Status}} {{.Config.Image}}')"
docker logs fillmap-api 2>&1 | grep -E '워밍업|Started MsgbeApplication' | tail -3
