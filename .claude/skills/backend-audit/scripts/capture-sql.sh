#!/usr/bin/env bash
# DB 쪽에서 실제 실행된 SQL을 잡는다 — Hibernate 로그가 아니라 PostgreSQL log_min_duration_statement=0.
# 이유: 소스의 @Query와 실제 실행문이 다를 수 있고(파라미터 바인딩·페이징·배치), N+1은
# 소스에서 안 보이고 실행 횟수에서 보인다. ALTER SYSTEM + reload라 컨테이너 재시작이 없다.
#
# 사용: capture-sql.sh <라벨> -- <실행할 명령>
#   capture-sql.sh grid-viewport -- ./gradlew test --tests 'com.msg.fillmap.grid.*GridQueryServiceIntegrationTest'
#   capture-sql.sh event-list -- curl -s 'http://localhost:8080/api/events?...'
# 출력: 실행문 목록(중복 접기 + 실행 횟수 + 최대 소요ms) → stdout, 원본 로그 → /tmp/backend-audit/<라벨>.log
set -uo pipefail
label=${1:?라벨}; shift; [ "${1:-}" = "--" ] && shift
cd "$(git rev-parse --show-toplevel)"
# 워크트리에서 돌려도 같은 컨테이너를 잡도록 compose 프로젝트가 아니라 컨테이너 이름으로 찾는다.
PG=$(docker ps --filter name=fillmap-postgres --format '{{.Names}}' | head -1); [ -n "$PG" ] || { echo "fillmap-postgres 컨테이너가 없다 — docker compose up -d postgres" >&2; exit 2; }
out=/tmp/backend-audit; mkdir -p "$out"
psql() { docker exec -i "$PG" psql -U user -d fillmap -Atq -c "$1"; }

for st in "ALTER SYSTEM SET log_min_duration_statement=0" "SELECT pg_reload_conf()"; do psql "$st" >/dev/null; done
marker="backend-audit:$label:$(date +%s)"
psql "SELECT '$marker';" >/dev/null
"$@"; rc=$?
for st in "ALTER SYSTEM RESET log_min_duration_statement" "SELECT pg_reload_conf()"; do psql "$st" >/dev/null; done

docker logs "$PG" --since 30m 2>/dev/null | sed -n "/$marker/,\$p" > "$out/$label.log"
echo "## 실행 SQL — $label (명령 종료코드 $rc, 원본: $out/$label.log)"
# 'statement:' 와 'duration: X ms  execute <name>: SQL' 두 형식을 SQL 본문 기준으로 합친다.
grep -oE '(statement|execute [^:]+): .*' "$out/$label.log" \
  | sed -E 's/^(statement|execute [^:]+): //' \
  | grep -viE "^(SET|SHOW|COMMIT|BEGIN|ROLLBACK|SELECT '|ALTER SYSTEM|SELECT pg_reload)" \
  | sed -E 's/[[:space:]]+/ /g' | sort | uniq -c | sort -rn \
  | awk '{n=$1; $1=""; printf "- ×%s  %s\n", n, substr($0,2,300)}'
echo
echo "## 소요 시간 상위 10 (ms)"
grep -oE 'duration: [0-9.]+ ms  (statement|execute [^:]+): .{0,160}' "$out/$label.log" | grep -viE "ALTER SYSTEM|pg_reload|backend-audit:" | sed -E 's/duration: ([0-9.]+) ms  (statement|execute [^:]+): /\1\t/' | sort -rn | head -10 | awk -F'\t' '{printf "- %s ms  %s\n",$1,$2}'
