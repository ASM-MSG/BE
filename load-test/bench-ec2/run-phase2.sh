#!/bin/bash
# MSG-609 2단계: 뷰포트 s1 재실행(체크 수정본) + 도감 요약 ramp. 사전에 앱 박스에서 bench-msg596.sql keep=1 적재 필요.
set -u
APP=http://10.0.1.106:8080
TOKEN=$(cat ~/bench.token); export TOKEN
R=~/results; mkdir -p $R; cd ~
STATS='avg,min,med,p(90),p(95),p(99),p(99.9),max'
run() { name=$1; shift; echo "=== $(date -u +%FT%TZ) START $name ===" | tee -a $R/timeline.log
  "$@" --summary-trend-stats="$STATS" --summary-export=$R/$name.summary.json > $R/$name.log 2>&1; rc=$?
  echo "=== $(date -u +%FT%TZ) END $name rc=$rc ===" | tee -a $R/timeline.log; sleep 60; }
case ${1:-all} in
  s1) run viewport-s1-rerun k6 run -e BASE_URL=$APP -e SCENARIO=s1 -e VUS=100 k6/viewport-ab-benchmark.js ;;
  s1-40) run viewport-s1-40vu k6 run -e BASE_URL=$APP -e SCENARIO=s1 -e VUS=40 k6/viewport-ab-benchmark.js ;;
  summary)
    # MSG-596 원 회차와 같은 계단(50→1,000 rps 7계단×40s), 보통 사용자 100명 + 헤비(b596-3) 5%
    run collection-summary-ramp env USERS=100 HEAVY_OID=b596-3 HEAVY_PCT=5 LABEL=cloud STAGES=50:40,100:40,200:40,400:40,600:40,800:40,1000:40 \
      k6 run -e BASE_URL=$APP -e SCENARIO=ramp k6/collection-summary-benchmark.js ;;
  summary-smoke) run collection-summary-smoke env OID=b596-3 k6 run -e BASE_URL=$APP -e SCENARIO=smoke k6/collection-summary-benchmark.js ;;
esac
echo "PHASE2 $1 DONE" | tee -a $R/timeline.log
