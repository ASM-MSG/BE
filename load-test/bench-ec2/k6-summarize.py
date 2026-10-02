import json, sys, glob, os
for f in sorted(glob.glob(sys.argv[1] + '/*.summary.json')):
    d = json.load(open(f)); m = d['metrics']; name = os.path.basename(f).replace('.summary.json','')
    def g(k, s):
        v = m.get(k, {})
        return v.get(s) if s in v else v.get('values', {}).get(s)
    lat = next((k for k in m if k.endswith('_latency') and '{' not in k), 'http_req_duration')
    reqs = g('http_reqs','count'); rate = g('http_reqs','rate'); drop = g('dropped_iterations','count') or 0
    fail = g('http_req_failed','value'); 
    print(f"{name}: reqs={reqs} rate={rate:.1f}/s dropped={drop} http_fail={fail}")
    print("   %s med=%.1f p90=%.1f p95=%.1f p99=%.1f p99.9=%.1f max=%.1f ms" % (lat, g(lat,'med'), g(lat,'p(90)'), g(lat,'p(95)'), g(lat,'p(99)'), g(lat,'p(99.9)') or -1, g(lat,'max')))
    for extra in ('hotzone_empty_rate','hotzone_failed','viewport_failed','summary_failed','hotzone_zones_returned','viewport_cells'):
        if extra in m: print(f"   {extra}={ {k:v for k,v in m[extra].items() if k in ('value','count','rate','avg','max')} }")
