#!/usr/bin/env bash
# 로컬 개발용 postgres 컨테이너 이름 하나를 찾는다. 다른 스크립트가 $(…/pg-container.sh)로 쓴다.
#
# 이름 부분 일치(name=fillmap-postgres)는 서버 스택의 fillmap-postgres-dev/-prod까지 잡는다 —
# 거기에 log_min_duration_statement=0을 걸면 바인드 파라미터(토큰·PII)가 운영 로그에 남는다.
# 이름 정확 일치도 안 된다: compose가 재생성 중 컨테이너를 "<id>_fillmap-postgres"로 바꿔 둔 채
# 남기는 경우가 있어(2026-09-08 로컬 실측) 정확 일치는 조용히 0건이 된다.
# 그래서 compose 라벨로 고른다 — service=postgres 이고 config 파일이 레포의 docker-compose.yml인
# 것. 서버 스택은 docker-compose.server.yml이라 여기서 갈린다. 후보가 정확히 하나가 아니면 실패한다.
set -uo pipefail
found=()
for c in $(docker ps --filter label=com.docker.compose.service=postgres --format '{{.Names}}'); do
  files=$(docker inspect "$c" --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}')
  IFS=',' read -ra arr <<< "$files"
  for f in "${arr[@]}"; do
    case "$f" in */docker-compose.yml) found+=("$c"); break;; esac
  done
done
case "${#found[@]}" in
  1) case "${found[0]}" in *-dev|*-prod) echo "dev/prod 컨테이너(${found[0]})는 대상이 아니다" >&2; exit 2;; esac
     echo "${found[0]}";;
  0) echo "로컬 postgres 컨테이너가 없다 — docker compose up -d postgres" >&2; exit 2;;
  *) echo "로컬 postgres 후보가 여럿이다: ${found[*]} — 하나만 남기고 다시" >&2; exit 2;;
esac
