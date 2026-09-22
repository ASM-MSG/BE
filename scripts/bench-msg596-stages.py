#!/usr/bin/env python3
"""MSG-596 ramp 회차의 계단별 실측표 — Prometheus 에서 앱·Hikari·DB·JVM 지표를 40초 계단 단위로 뽑는다.
  [PG_CID=<컨테이너 id 앞 8자리>] scripts/bench-msg596-stages.py <start ISO/epoch> [stage_sec=40] [stages=7]
"""
import os, sys, json, urllib.request, urllib.parse
from datetime import datetime, timezone
PROM = os.environ.get('PROM', 'http://localhost:9090')
# fillmap-postgres 컨테이너 id 앞자리 — 컨테이너를 다시 만들면 바뀐다(`docker ps --no-trunc -f name=fillmap-postgres -q | cut -c1-8`).
# 안 맞으면 db_cpu 가 nan 으로 조용히 빠지므로 대시보드 변수와 같은 값을 PG_CID 로 넘긴다.
PG_CID = os.environ.get('PG_CID', '17323a16')
a = sys.argv[1]
start = float(a) if a.replace('.', '').isdigit() else datetime.fromisoformat(a.replace('Z', '+00:00')).timestamp()
stage = int(sys.argv[2]) if len(sys.argv) > 2 else 40
n = int(sys.argv[3]) if len(sys.argv) > 3 else 7
U = 'uri="/api/collections/summary"'
Q = {
 'rps':   (f'sum(rate(http_server_requests_seconds_count{{{U}}}[10s]))', 'avg'),
 'p50ms': (f'histogram_quantile(0.5, sum(rate(http_server_requests_seconds_bucket{{{U}}}[10s])) by (le))*1000', 'max'),
 'p95ms': (f'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{{{U}}}[10s])) by (le))*1000', 'max'),
 'p99ms': (f'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{{{U}}}[10s])) by (le))*1000', 'max'),
 'hk_act': ('hikaricp_connections_active', 'max'),
 'hk_pend': ('hikaricp_connections_pending', 'max'),
 'acq_ms': ('hikaricp_connections_acquire_seconds_max*1000', 'max'),
 'use_ms': ('hikaricp_connections_usage_seconds_max*1000', 'max'),
 'tomcat': ('tomcat_threads_busy_threads', 'max'),
 'app_cpu': ('process_cpu_usage*10', 'max'),
 'db_cpu': (f'rate(container_cpu_usage_seconds_total{{id=~"/docker/{PG_CID}.*"}}[2m])', 'max'),
 'tup_ret/s': ('rate(pg_stat_database_tup_returned{datname="fillmap"}[10s])', 'max'),
 'tmp_MB/s': ('rate(pg_stat_database_temp_bytes{datname="fillmap"}[10s])/1e6', 'max'),
 'gc_ms/s': ('sum(rate(jvm_gc_pause_seconds_sum[10s]))*1000', 'max'),
 'heap_MB': ('sum(jvm_memory_used_bytes{area="heap"})/1e6', 'max'),
}
def rng(q, s, e):
	u = f'{PROM}/api/v1/query_range?' + urllib.parse.urlencode({'query': q, 'start': s, 'end': e, 'step': 5})
	r = json.load(urllib.request.urlopen(u))['data']['result']
	return [float(v[1]) for v in r[0]['values'] if v[1] not in ('NaN', '+Inf')] if r else []
print('stage(s)   ' + ' '.join(f'{k:>9}' for k in Q))
for i in range(n):
	s, e = start + i * stage, start + (i + 1) * stage
	row = []
	for k, (q, agg) in Q.items():
		xs = rng(q, s, e)
		v = (max(xs) if agg == 'max' else sum(xs) / len(xs)) if xs else float('nan')
		row.append(f'{v:9.1f}')
	print(f'{i*stage:>3}-{(i+1)*stage:<4}  ' + ' '.join(row))
