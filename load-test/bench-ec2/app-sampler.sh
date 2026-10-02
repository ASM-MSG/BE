#!/bin/bash
# 앱 박스 자원 샘플러(5초): loadavg, 컨테이너 CPU%/메모리, PG active 세션, 가용 메모리
OUT=~/app-samples.csv
echo "ts,load1,api_cpu,api_mem,pg_cpu,pg_mem,redis_cpu,pg_active,pg_total,mem_avail_mb" > $OUT
while true; do
  ts=$(date -u +%FT%TZ); l1=$(cut -d' ' -f1 /proc/loadavg)
  st=$(docker stats --no-stream --format "{{.Name}} {{.CPUPerc}} {{.MemUsage}}" 2>/dev/null)
  api=$(echo "$st" | awk '$1=="fillmap-api"{print $2","$3}'); pg=$(echo "$st" | awk '$1=="fillmap-postgres"{print $2","$3}'); rd=$(echo "$st" | awk '$1=="fillmap-local-redis"{print $2}')
  pga=$(docker exec fillmap-postgres psql -U user -d fillmap -tAc "select count(*) filter (where state='active')||','||count(*) from pg_stat_activity where datname='fillmap'" 2>/dev/null)
  ma=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
  echo "$ts,$l1,$api,$pg,$rd,$pga,$ma" >> $OUT
  sleep 5
done
