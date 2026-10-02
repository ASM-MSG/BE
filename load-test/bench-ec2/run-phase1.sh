#!/bin/bash
# MSG-609 1단계: 뷰포트(s1·s3·s4) + 핫구역(expiry·cap). 결과는 ~/results/<name>.summary.json + k6 stdout .log
set -u
APP=http://10.0.1.106:8080
TOKEN=$(cat ~/bench.token)
R=~/results; mkdir -p $R; cd ~
STATS='avg,min,med,p(90),p(95),p(99),p(99.9),max'
sample() { # 앱 지표 5초 샘플러 — CPU·힙·히카리 대기·요청 수
  while true; do
    ts=$(date -u +%FT%TZ)
    cpu=$(curl -s --max-time 2 $APP/actuator/metrics/system.cpu.usage | jq -r '.measurements[0].value // "NA"')
    pcpu=$(curl -s --max-time 2 $APP/actuator/metrics/process.cpu.usage | jq -r '.measurements[0].value // "NA"')
    heap=$(curl -s --max-time 2 "$APP/actuator/metrics/jvm.memory.used?tag=area:heap" | jq -r '.measurements[0].value // "NA"')
    pend=$(curl -s --max-time 2 $APP/actuator/metrics/hikaricp.connections.pending | jq -r '.measurements[0].value // "NA"')
    act=$(curl -s --max-time 2 $APP/actuator/metrics/hikaricp.connections.active | jq -r '.measurements[0].value // "NA"')
    echo "$ts,$cpu,$pcpu,$heap,$pend,$act" >> $R/app-samples.csv
    sleep 5
  done
}
echo "ts,system_cpu,process_cpu,heap_used,hikari_pending,hikari_active" > $R/app-samples.csv
sample & SP=$!
run() { name=$1; shift; echo "=== $(date -u +%FT%TZ) START $name ===" | tee -a $R/timeline.log
  "$@" --summary-trend-stats="$STATS" --summary-export=$R/$name.summary.json > $R/$name.log 2>&1; rc=$?
  echo "=== $(date -u +%FT%TZ) END $name rc=$rc ===" | tee -a $R/timeline.log; sleep 60; }
export TOKEN
run viewport-s1 k6 run -e BASE_URL=$APP -e SCENARIO=s1 -e VUS=100 k6/viewport-ab-benchmark.js
run viewport-s3-300rps k6 run -e BASE_URL=$APP -e SCENARIO=s3 -e RATE=300 k6/viewport-ab-benchmark.js
run viewport-s4-stress k6 run -e BASE_URL=$APP -e SCENARIO=s4 k6/viewport-ab-benchmark.js
run hotzone-expiry-200rps k6 run -e BASE_URL=$APP -e SCENARIO=expiry -e RATE=200 -e DURATION=3m k6/hotzone-benchmark.js
run hotzone-cap k6 run -e BASE_URL=$APP -e SCENARIO=cap k6/hotzone-benchmark.js
kill $SP; echo "PHASE1 DONE" | tee -a $R/timeline.log
