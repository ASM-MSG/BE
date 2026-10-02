"""MSG-609 영상 인코딩 규모 회차(encoding-scale.json) → 그래프 2장. 실행: <venv>/bin/python load-test/bench-ec2/encoding-story-charts.py"""
import json, statistics
import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
E = 'load-test/evidence/2026-09-30/encoding-scale.json'; OUT = 'docs/reports/assets/2026-09-30'
C = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100']; INK = '#0b0b0b'; INK2 = '#52514e'; GRID = '#e6e5e1'; SURF = '#fcfcfb'
plt.rcParams.update({'font.family': ['AppleGothic', 'Apple SD Gothic Neo', 'DejaVu Sans'], 'axes.unicode_minus': False,
	'figure.facecolor': SURF, 'axes.facecolor': SURF, 'axes.edgecolor': GRID, 'axes.grid': True, 'grid.color': GRID,
	'axes.spines.top': False, 'axes.spines.right': False, 'axes.spines.left': False, 'text.color': INK,
	'axes.labelcolor': INK2, 'xtick.color': INK2, 'ytick.color': INK2, 'font.size': 11})
d = json.load(open(E)); rounds = d['rounds']
concs = sorted({r['concurrency'] for r in rounds})
def wall(nodes, c): return [r['wall_seconds'] for r in rounds if r['nodes'] == nodes and r['concurrency'] == c]
def ready(nodes, c): return [i['ready_ms'] / 1000 for r in rounds if r['nodes'] == nodes and r['concurrency'] == c for i in r['items']]

# 1 전체 완료 시간 (회차 평균, 점은 개별 회차)
fig, ax = plt.subplots(figsize=(9, 4)); w = 0.36
for j, (n, lab, c) in enumerate([(1, '1노드 (dev API 서버만)', C[0]), (2, '2노드 (dev + AI 서버 워커)', C[1])]):
	xs = [i + (j - 0.5) * w for i in range(len(concs))]; means = [statistics.mean(wall(n, k)) for k in concs]
	ax.bar(xs, means, w, color=c, label=lab)
	for x, k, m in zip(xs, concs, means):
		for v in wall(n, k): ax.plot(x, v, 'o', color=INK, ms=4)
		ax.text(x, m + 4, f'{m:.0f}s', ha='center', fontsize=9, color=INK2)
ax.set_xticks(range(len(concs))); ax.set_xticklabels([f'동시 {k}건' for k in concs]); ax.set_ylabel('전부 READY 까지 (초)')
ax.legend(frameon=False, loc='upper left'); ax.set_title('동시 업로드 수에 따른 전체 완료 시간 — 1노드 vs 2노드 (ABBA 2회, 점 = 회차)', loc='left', fontsize=12, color=INK); ax.grid(axis='x', visible=False)
fig.tight_layout(); fig.savefig(f'{OUT}/encoding-wall.png', dpi=160); plt.close(fig); print('wrote encoding-wall')

# 2 영상 한 편이 기다린 시간 (확정→READY) 분포: 평균과 최대
fig, ax = plt.subplots(figsize=(9, 4))
for n, lab, c in [(1, '1노드', C[0]), (2, '2노드', C[1])]:
	mean = [statistics.mean(ready(n, k)) for k in concs]; mx = [max(ready(n, k)) for k in concs]
	ax.plot(concs, mean, marker='o', lw=2, color=c, label=f'{lab} 평균'); ax.plot(concs, mx, marker='s', lw=2, ls='--', color=c, label=f'{lab} 최대(가장 늦은 영상)')
	for k, m, x in zip(concs, mean, mx): ax.text(k + 0.15, x, f'{x:.0f}s', fontsize=8, color=INK2, va='center')
ax.axhline(30, color=C[3], ls=':', lw=1.5); ax.text(concs[0], 32, 'NFR-003 목표 30초 (단독 기준)', fontsize=9, color=INK2)
ax.set_xticks(concs); ax.set_xlabel('동시 업로드 수'); ax.set_ylabel('업로드 확정 → READY (초)'); ax.legend(frameon=False, ncol=2, fontsize=9, loc='upper left')
ax.set_title('영상 한 편이 기다린 시간 — 동시 수가 늘면 선형으로 는다', loc='left', fontsize=12, color=INK); ax.grid(axis='x', visible=False)
fig.tight_layout(); fig.savefig(f'{OUT}/encoding-ready.png', dpi=160); plt.close(fig); print('wrote encoding-ready')

for n in (1, 2):
	for k in concs:
		ws = wall(n, k); rs = ready(n, k)
		print(f'nodes={n} conc={k:2d} wall mean {statistics.mean(ws):6.1f}s ({min(ws):.1f}~{max(ws):.1f})  ready mean {statistics.mean(rs):6.1f}s max {max(rs):6.1f}s  n={len(rs)}')
