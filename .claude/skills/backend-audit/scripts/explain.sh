#!/usr/bin/env bash
# 로컬 PostgreSQL에서 EXPLAIN (ANALYZE, BUFFERS) 실행. capture-sql.sh가 뽑은 실행문을 넣는다.
# $1 파라미터는 실제 값으로 바꿔 넣어야 한다 (로그의 DETAIL: parameters 줄에 값이 있다).
# 사용: explain.sh "SELECT ... WHERE grid_id = '19443_9582'"   또는   explain.sh < query.sql
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$(git rev-parse --show-toplevel)"
# 워크트리에서 돌려도 같은 컨테이너를 잡도록 compose 프로젝트 이름이 아니라 라벨로 찾는다 (pg-container.sh).
PG=$("$HERE/pg-container.sh") || exit 2
sql=${1:-$(cat)}
docker exec -i "$PG" psql -U user -d fillmap -c "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql"
echo
echo "# 읽는 법: 'Seq Scan on <큰 테이블>'이 WHERE 있는 조회에 보이면 인덱스 미사용. 'Index Scan using <idx>'면 사용."
echo "# 로컬은 행 수가 적어 플래너가 일부러 Seq Scan을 고를 수 있다 — 행 수(rows=)를 같이 적고, dev DB 실측이 필요하면 그렇게 표기한다."
