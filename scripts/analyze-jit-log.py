#!/usr/bin/env python3
"""-Xlog:jit+compilation=debug 로그를 접는다. 인자: jit.log [부하 시작 uptime초] [벌점 종료 uptime초, 기본 시작+16]
출력: (1) 시간대별 tier3/tier4 컴파일 건수, (2) 부하 시작 뒤 tier4로 올라간 fillmap 메서드 상위, (3) tier3 최초 컴파일이 몰린 패키지."""
import re, sys, collections
path = sys.argv[1]; t0 = float(sys.argv[2]) if len(sys.argv) > 2 else None; t1 = float(sys.argv[3]) if len(sys.argv) > 3 else (t0 + 16 if t0 else None)
pat = re.compile(r'^\[(\d+\.\d+)s\]\[debug\]\[jit,compilation\]\s+(\d+)\s+([%sb!n ]*)\s*(\d)\s+(\S+)')
rows = []
for line in open(path, errors='replace'):
    m = pat.match(line)
    if not m: continue
    t, cid, flags, tier, meth = float(m.group(1)), int(m.group(2)), m.group(3), int(m.group(4)), m.group(5)
    rows.append((t, tier, meth, 'n' in flags))
print(f"총 컴파일 {len(rows)}건, 마지막 시각 {rows[-1][0]:.1f}s" if rows else "빈 로그")
def bucket(t): return int(t // 5) * 5
hist = collections.defaultdict(lambda: [0, 0])
for t, tier, _, _ in rows:
    if tier == 3: hist[bucket(t)][0] += 1
    if tier == 4: hist[bucket(t)][1] += 1
print("\n5초 구간 | tier3 | tier4" + ("   (부하 시작 %.0fs)" % t0 if t0 else ""))
for b in sorted(hist):
    mark = " <- 부하" if t0 and t0 <= b < (t1 or 1e9) else ""
    print(f"{b:6d}s | {hist[b][0]:5d} | {hist[b][1]:5d}{mark}")
if t0:
    win = [r for r in rows if t0 <= r[0] < t1]
    print(f"\n부하 창 [{t0:.0f},{t1:.0f})s: 컴파일 {len(win)}건, tier4 {sum(1 for r in win if r[1]==4)}건")
    fm = collections.Counter(r[2] for r in win if r[1] == 4 and 'fillmap' in r[2])
    print("부하 창 tier4 fillmap 메서드:", len(fm))
    for m_, c in fm.most_common(25): print("  ", m_)
    t3 = [r for r in win if r[1] == 3]
    pk = collections.Counter()
    for r in t3:
        m_ = r[2]
        key = ('fillmap.' + m_.split('com.msg.fillmap.')[1].split('.')[0]) if 'com.msg.fillmap.' in m_ else m_.split('.')[0] + '.' + m_.split('.')[1] if m_.count('.') > 1 else m_
        pk[key] += 1
    print("부하 창 tier3 최초 컴파일 패키지 상위:")
    for k, c in pk.most_common(15): print(f"   {c:5d}  {k}")
