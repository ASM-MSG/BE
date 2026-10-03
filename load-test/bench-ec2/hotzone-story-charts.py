import json, matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter
E='load-test/evidence/2026-09-29/hotzone-story'; OUT='docs/reports/assets/2026-09-29'
C=['#2a78d6','#eb6834','#1baf7a','#eda100']; INK='#0b0b0b'; INK2='#52514e'; GRID='#e6e5e1'; SURF='#fcfcfb'
plt.rcParams.update({'font.family':['AppleGothic','Apple SD Gothic Neo','DejaVu Sans'],'axes.unicode_minus':False,'figure.facecolor':SURF,'axes.facecolor':SURF,'axes.edgecolor':GRID,'axes.grid':True,'grid.color':GRID,'axes.spines.top':False,'axes.spines.right':False,'axes.spines.left':False,'text.color':INK,'axes.labelcolor':INK2,'xtick.color':INK2,'ytick.color':INK2,'font.size':11})
def fmt(v,_): return f'{v/1000:.1f}s' if v>=1000 else (f'{v:.0f}ms' if v>=1 else f'{v:.2f}ms')
arms=[('sql','① DB 집계\n(GROUP BY)'),('redis_naive','② Redis 원시 신호\n(버킷 없음)'),('redis','③ Redis 6h 버킷\n(ZUNIONSTORE)'),('redis_cache','④ 버킷 + 30초 캐시\n(현재 구현)')]
s1=json.load(open(f'{E}/scale-100000.json')); s2=json.load(open(f'{E}/scale-1000000.json'))
# 1 micro latency
fig,ax=plt.subplots(figsize=(9,4)); x=range(4); w=0.36
for j,(d,lab,c) in enumerate([(s1,'신호 10만 건',C[0]),(s2,'신호 100만 건',C[1])]):
    ys=[d['micro'][a]['p50_ms'] for a,_ in arms]; ax.bar([i+(j-0.5)*w for i in x],ys,w,color=c,label=lab)
    for i,v in zip(x,ys): ax.text(i+(j-0.5)*w,v*1.15,fmt(v,0),ha='center',fontsize=9,color=INK2)
ax.set_yscale('log'); ax.yaxis.set_major_formatter(FuncFormatter(fmt)); ax.set_xticks(list(x)); ax.set_xticklabels([l for _,l in arms],fontsize=9); ax.set_ylabel('단건 조회 p50 (로그)'); ax.legend(frameon=False); ax.set_title('전국 상위 50개 구하기 — 저장 구조별 단건 지연',loc='left',fontsize=12,color=INK); ax.grid(axis='x',visible=False)
fig.tight_layout(); fig.savefig(f'{OUT}/hotzone-story-latency.png',dpi=160); plt.close(fig)
# 2 memory at 1M
fig,ax=plt.subplots(figsize=(9,3.2)); mem=[s2['sql_table_and_indexes_bytes']/1e6, s2['redis_naive_events_bytes']/1e6, s2['redis_selected_8_bucket_bytes']/1e6]; labels=['① DB 테이블+인덱스 (72h)','② Redis 원시 신호 (72h)','③ Redis 8버킷 (48h 창)']
ax.barh(labels[::-1],mem[::-1],color=[C[2],C[1],C[0]]); 
for i,v in enumerate(mem[::-1]): ax.text(v+2,i,f'{v:.0f} MB',va='center',color=INK2)
ax.set_xlabel('저장 크기 (MB) — 신호 100만 건'); ax.set_xlim(0,150); ax.set_title('같은 신호 100만 건을 담는 데 드는 메모리',loc='left',fontsize=12,color=INK); ax.grid(axis='y',visible=False)
fig.tight_layout(); fig.savefig(f'{OUT}/hotzone-story-memory.png',dpi=160); plt.close(fig)
# 3 load: success ratio + p95
sm={d['mode']:d for d in json.load(open(f'{E}/summary.json'))}
fig,(ax,ax2)=plt.subplots(1,2,figsize=(10,3.8))
succ=[sm[a]['success']/sm[a]['offered']*100 for a,_ in arms]; ax.bar([l for _,l in arms],succ,color=[C[3],C[1],C[0],C[2]])
for i,v in enumerate(succ): ax.text(i,v+2,f'{v:.1f}%',ha='center',color=INK2)
ax.set_ylim(0,110); ax.set_ylabel('성공률 (%)'); ax.set_title('100 rps × 33초 × 3회 — 제때 처리한 비율',loc='left',fontsize=11,color=INK); ax.tick_params(axis='x',labelsize=8); ax.grid(axis='x',visible=False)
p95=[sm[a]['p95_ms'] for a,_ in arms]; ax2.bar([l for _,l in arms],p95,color=[C[3],C[1],C[0],C[2]])
for i,v in enumerate(p95): ax2.text(i,v*1.2,fmt(v,0),ha='center',color=INK2,fontsize=9)
ax2.set_yscale('log'); ax2.yaxis.set_major_formatter(FuncFormatter(fmt)); ax2.set_ylabel('성공 요청 p95 (로그)'); ax2.set_title('성공한 요청의 대기 포함 p95',loc='left',fontsize=11,color=INK); ax2.tick_params(axis='x',labelsize=8); ax2.grid(axis='x',visible=False)
fig.tight_layout(); fig.savefig(f'{OUT}/hotzone-story-load.png',dpi=160); plt.close(fig)
print(json.dumps({a:{k:sm[a][k] for k in ('offered','success','errors','p95_ms','p99_ms','expiry_p99_ms','pg_cpu_seconds','redis_cpu_seconds')} for a,_ in arms},ensure_ascii=False,indent=0))
