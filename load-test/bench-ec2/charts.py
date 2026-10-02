import json, csv, datetime as dt, re
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter
E='load-test/evidence/2026-09-29'; OUT='docs/reports/assets/2026-09-29'
C=['#2a78d6','#eb6834','#1baf7a','#eda100']; INK='#0b0b0b'; INK2='#52514e'; GRID='#e6e5e1'; SURF='#fcfcfb'
plt.rcParams.update({'font.family':['AppleGothic','Apple SD Gothic Neo','DejaVu Sans'],'axes.unicode_minus':False,
 'figure.facecolor':SURF,'axes.facecolor':SURF,'axes.edgecolor':GRID,'axes.grid':True,'grid.color':GRID,'grid.linewidth':0.8,
 'axes.spines.top':False,'axes.spines.right':False,'axes.spines.left':False,'text.color':INK,'axes.labelcolor':INK2,'xtick.color':INK2,'ytick.color':INK2,'font.size':11})
def m(name):
    d=json.load(open(f'{E}/{name}.summary.json'))['metrics']; lat=next(k for k in d if k.endswith('_latency') and '{' not in k); return d, d[lat]
def save(fig,n): fig.tight_layout(); fig.savefig(f'{OUT}/{n}.png',dpi=160); plt.close(fig); print('wrote',n)
def fmt_ms(v,_): return f'{v/1000:.1f}s' if v>=1000 else f'{v:.0f}ms'

# 1 overview: local vs cloud ceiling
fig,ax=plt.subplots(figsize=(9,3.6)); apis=['뷰포트 격자 조회','핫구역 조회','도감 요약 (헤비 5%)']; loc=[286,2000,1000]; cld=[90,800,70]; lbl=['90','800','60~80']
y=range(len(apis)); h=0.36
ax.barh([i+h/2 for i in y],loc,h,color=C[0],label='노트북 (로컬)'); ax.barh([i-h/2 for i in y],cld,h,color=C[1],label='t3.small (이번)')
for i,(a,b,l) in enumerate(zip(loc,cld,lbl)): ax.text(a*1.06,i+h/2,f'{a:,} rps',va='center',color=INK2,fontsize=10); ax.text(b*1.06,i-h/2,f'{l} rps',va='center',color=INK2,fontsize=10)
ax.set_xscale('log'); ax.set_xlim(30,6000); ax.set_yticks(list(y)); ax.set_yticklabels(apis); ax.set_xlabel('실패 없이 처리한 상한 (rps, 로그 눈금)'); ax.legend(frameon=False,loc='lower right'); ax.set_title('같은 코드, 노트북 vs dev 사양 처리량 상한',loc='left',fontsize=12,color=INK)
ax.grid(axis='y',visible=False); save(fig,'overview-ceiling')

# 2 viewport latency by scenario
runs=[('40 VU×3','viewport-s1-40vu'),('100 VU×3 웜','viewport-s1-rerun'),('100 VU×3 콜드','viewport-s1'),('300 rps 고정','viewport-s3-300rps'),('100→1,000 rps','viewport-s4-stress')]
fig,ax=plt.subplots(figsize=(9,4)); x=range(len(runs)); w=0.26
for j,(p,c) in enumerate([('med',C[0]),('p(95)',C[1]),('p(99)',C[2])]):
    vals=[m(r)[1][p] for _,r in runs]; ax.bar([i+(j-1)*w for i in x],vals,w,color=c,label={'med':'p50','p(95)':'p95','p(99)':'p99'}[p])
    for i,v in zip(x,vals): ax.text(i+(j-1)*w,v*1.08,fmt_ms(v,0),ha='center',fontsize=8,color=INK2)
ax.axhline(96.5,color=C[3],ls='--',lw=1.5); ax.text(len(runs)-0.5,105,'로컬 40 VU p95 96.5ms',ha='right',fontsize=9,color=INK2)
ax.set_yscale('log'); ax.yaxis.set_major_formatter(FuncFormatter(fmt_ms)); ax.set_xticks(list(x)); ax.set_xticklabels([n for n,_ in runs]); ax.set_ylabel('응답 지연'); ax.legend(frameon=False,ncol=3,loc='upper left'); ax.set_title('GET /api/grids 지연 — 시나리오별 (t3.small)',loc='left',fontsize=12,color=INK); ax.grid(axis='x',visible=False); save(fig,'viewport-latency')

# timeline helper
rows=list(csv.DictReader(open(f'{E}/app-box-samples.csv')))
def P(t): return dt.datetime.strptime(t,'%Y-%m-%dT%H:%M:%SZ')
def pct(s): return float(s.strip('%')) if s and s.strip() else None
tl=open(f'{E}/timeline.log').read(); wins={}
for mm in re.finditer(r'=== (\S+) START (\S+) ===\n=== (\S+) END \2',tl): wins[mm.group(2)]=(P(mm.group(1)),P(mm.group(3)))
def timeline(names,title,out,extra=None):
    a=min(wins[n][0] for n in names)-dt.timedelta(seconds=30); b=max(wins[n][1] for n in names)+dt.timedelta(seconds=30)
    a=max(a,P(rows[0]['ts'])-dt.timedelta(seconds=10)); sel=[r for r in rows if a<=P(r['ts'])<=b]; t=[P(r['ts']) for r in sel]
    fig,ax=plt.subplots(figsize=(9,3.8))
    ax.plot(t,[pct(r['pg_cpu']) for r in sel],color=C[0],lw=2,label='PostgreSQL 컨테이너 CPU')
    ax.plot(t,[pct(r['api_cpu']) for r in sel],color=C[1],lw=2,label='앱 컨테이너 CPU')
    if extra: ax.plot(t,[float(r[extra[0]])*extra[2] for r in sel],color=C[2],lw=2,ls=':',label=extra[1])
    for n in names: s,e=wins[n]; ax.axvspan(s,e,color=GRID,alpha=0.6,lw=0); ax.text(s+(e-s)/2,185,n.replace('viewport-','').replace('collection-summary-',''),ha='center',fontsize=9,color=INK2)
    ax.axhline(200,color=INK2,lw=0.8,ls='--'); ax.text(t[0],203,'2 vCPU = 200%',fontsize=8,color=INK2)
    ax.set_ylim(0,220); ax.set_ylabel('CPU (%)'); ax.xaxis.set_major_formatter(matplotlib.dates.DateFormatter('%H:%M',tz=dt.timezone.utc)); ax.set_xlabel('UTC'); ax.legend(frameon=False,loc='upper center',bbox_to_anchor=(0.5,-0.22),ncol=3,fontsize=9); ax.set_title(title,loc='left',fontsize=12,color=INK); ax.grid(axis='x',visible=False); save(fig,out)
timeline(['viewport-s3-300rps','viewport-s4-stress'],'뷰포트 부하 중 앱 박스 CPU — DB 가 먼저 한 코어를 채운다','viewport-cpu')

# 3 hotzone: latency vs offered rps + cpu
pts=[(200,'hotzone-expiry-200rps'),(400,'hotzone-scale-400rps'),(600,'hotzone-scale-600rps'),(800,'hotzone-scale-800rps')]
fig,(ax,ax2)=plt.subplots(1,2,figsize=(10,3.8),gridspec_kw={'width_ratios':[3,2]})
xs=[p for p,_ in pts]
for p,lab,c in [('med','p50',C[0]),('p(95)','p95',C[1]),('p(99)','p99',C[2]),('p(99.9)','p99.9',C[3])]:
    ys=[m(n)[1][p] for _,n in pts]; ax.plot(xs,ys,marker='o',ms=6,lw=2,color=c,label=lab); ax.text(xs[-1]+15,ys[-1],f'{lab} {ys[-1]:.0f}ms',fontsize=8,color=INK2,va='center')
cap=m('hotzone-cap')[1]; ax.plot([929],[cap['p(95)']],marker='D',ms=8,color=C[1],ls='none'); ax.annotate('램프 2,000 목표 → 실제 929 rps\np95 1.25s (포화)',(929,cap['p(95)']),xytext=(430,250),fontsize=8,color=INK2,arrowprops=dict(arrowstyle='-',color=INK2,lw=0.8))
ax.set_yscale('log'); ax.yaxis.set_major_formatter(FuncFormatter(fmt_ms)); ax.set_xlim(150,1000); ax.set_xlabel('제공 부하 (rps)'); ax.set_ylabel('응답 지연'); ax.legend(frameon=False,ncol=4,fontsize=9,loc='upper left'); ax.set_title('GET /api/hotzones 지연 vs 부하 (t3.small)',loc='left',fontsize=12,color=INK); ax.grid(axis='x',visible=False)
ws=open(f'{E}/app-box-window-stats.txt').read(); cpu={}
for n in ['hotzone-expiry-200rps','hotzone-scale-400rps','hotzone-scale-600rps','hotzone-scale-800rps','hotzone-cap']:
    mm=re.search(n+r'\s+n=\s*\d+ api_cpu avg\s+(\d+)% max\s+(\d+)%',ws); cpu[n]=(int(mm.group(1)),int(mm.group(2)))
labels=['200','400','600','800','929(cap)']; ax2.bar(labels,[cpu[n][0] for n in cpu],color=C[1],label='평균'); ax2.plot(labels,[cpu[n][1] for n in cpu],'o',color=INK,ms=5,label='최대')
ax2.axhline(200,color=INK2,lw=0.8,ls='--'); ax2.set_ylim(0,220); ax2.set_ylabel('앱 컨테이너 CPU (%)'); ax2.set_xlabel('rps'); ax2.legend(frameon=False,fontsize=9); ax2.set_title('앱 CPU — 병목 위치',loc='left',fontsize=11,color=INK); ax2.grid(axis='x',visible=False)
save(fig,'hotzone-rps')

# 4 collection: stages + timeline
fig,ax=plt.subplots(figsize=(9,3.6)); st=['1 (→50)','2 (→100)','3 (→200)','4 (→400)','5 (→600)','6 (→800)','7 (→1,000)']; off=[50,100,200,400,600,800,1000]; ach=[38,60,83,None,None,None,None]
x=range(7); ax.bar([i-0.2 for i in x],off,0.4,color=GRID,edgecolor=INK2,label='목표 rps'); ax.bar([i+0.2 for i in x],[a or 0 for a in ach],0.4,color=C[1],label='실제 처리 rps (서버 측정)')
for i,a in zip(x,ach):
    ax.text(i+0.2,(a or 0)+12,f'{a}' if a else '관측 유실\n(포화)',ha='center',fontsize=8,color=INK2)
ax.text(0.4,620,'2계단 중반부터 풀 대기 190\n톰캣 200 스레드 전부 대기',fontsize=9,color=INK2)
ax.set_xticks(list(x)); ax.set_xticklabels(st,fontsize=9); ax.set_ylabel('rps'); ax.legend(frameon=False,loc='upper left'); ax.set_title('GET /api/collections/summary 램프 — 계단별 목표 vs 실제 (헤비 5%)',loc='left',fontsize=12,color=INK); ax.grid(axis='x',visible=False); save(fig,'collection-stages')
timeline(['collection-summary-ramp'],'도감 요약 램프 중 앱 박스 CPU — DB 가 두 코어를 다 쓴다','collection-cpu')
