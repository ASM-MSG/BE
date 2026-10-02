import csv, sys, re, datetime as dt
tl = open(sys.argv[1]).read(); rows = list(csv.DictReader(open(sys.argv[2])))
def p(t): return dt.datetime.strptime(t, '%Y-%m-%dT%H:%M:%SZ')
wins = {}
for m in re.finditer(r'=== (\S+) START (\S+) ===\n=== (\S+) END \2', tl): wins[m.group(2)] = (p(m.group(1)), p(m.group(3)))
def num(s): return float(s.strip('%')) if s and s not in ('',) else None
for name,(a,b) in wins.items():
    sel = [r for r in rows if a <= p(r['ts']) <= b]
    if not sel: print(name, 'no samples'); continue
    f = lambda k: [num(r[k]) for r in sel if r.get(k)]
    api, pg, l1, ma, act = f('api_cpu'), f('pg_cpu'), f('load1'), f('mem_avail_mb'), f('pg_active')
    print(f"{name:26s} n={len(sel):3d} api_cpu avg {sum(api)/len(api):5.0f}% max {max(api):4.0f}% | pg_cpu avg {sum(pg)/len(pg):4.0f}% max {max(pg):4.0f}% | load1 max {max(l1):5.1f} | pg_active max {max(act):3.0f} | mem_avail min {min(ma):4.0f}MB")
