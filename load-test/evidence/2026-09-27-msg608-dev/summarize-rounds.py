#!/usr/bin/env python3
"""m608 회차 요약표: k6 요약 JSON(하네스 SUMMARY_JSON 형식)에서 p95/p99·버림·카나리 배율 + 원시 JSON에서 '벌점 지속(초)' = 핫구역 초별 p95가 300ms를 넘는 마지막 초."""
import json, glob, re, collections, datetime, os
S='/private/tmp/claude-501/-Users-kang-projects-BE/977bcd0f-3f70-47b6-94fc-ccf7acbf8458/scratchpad'
def penalty_secs(raw):
    by=collections.defaultdict(list); t0=None
    for line in open(raw):
        d=json.loads(line)
        if d.get('type')!='Point' or d['metric']!='http_req_duration' or d['data']['tags'].get('name')!='hotzone_agg': continue
        t=re.sub(r'\.(\d+)',lambda m:'.'+m.group(1)[:6].ljust(6,'0'),d['data']['time'])
        ts=datetime.datetime.fromisoformat(t).timestamp(); t0=ts if t0 is None else min(t0,ts); by[int(ts)].append(d['data']['value'])
    def p95(v): v=sorted(v); return v[min(len(v)-1,int(len(v)*.95))]
    over=[s-int(t0) for s in sorted(by) if p95(by[s])>300 and s-int(t0)<20]  # 첫 20초 안에서
    return (max(over)+1) if over else 0
rows=[]
for summ in sorted(glob.glob(f'{S}/m608-r*-summary.json')):
    tag=os.path.basename(summ).replace('-summary.json','').replace('m608-','')
    j=json.load(open(summ)); m=j.get('k6',{}).get('metrics',{}); c=j.get('cluster',{})
    def tr(name,stat):
        k=f'http_req_duration{{name:{name}}}'
        return m.get(k,{}).get('values',{}).get(stat) or m.get(k,{}).get(stat)
    dropped=c.get('dropped',0)
    raw=summ.replace('-summary.json','-raw.json')
    rows.append((tag, tr('mission_agg','p(95)'), tr('mission_agg','p(99)'), tr('hotzone_agg','p(95)'), tr('hotzone_agg','p(99)'), tr('canary','p(95)'), dropped, penalty_secs(raw) if os.path.exists(raw) else None))
print(f"{'회차':<16}{'미션p95':>8}{'p99':>7}{'핫구역p95':>9}{'p99':>7}{'카나리p95':>9}{'버림':>6}{'벌점초':>7}")
for r in rows:
    print(f"{r[0]:<16}{r[1] or 0:8.0f}{r[2] or 0:7.0f}{r[3] or 0:9.0f}{r[4] or 0:7.0f}{r[5] or 0:9.0f}{r[6]:6.0f}{r[7] if r[7] is not None else -1:7d}")
