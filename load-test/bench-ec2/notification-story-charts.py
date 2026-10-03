"""MSG-609 알림 발송 구조 비교(notification-story) → 그래프 3장. 실행: <venv>/bin/python load-test/bench-ec2/notification-story-charts.py"""
import json
import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter
E = 'load-test/evidence/2026-09-30/notification-story'; OUT = 'docs/reports/assets/2026-09-30'
C = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100']; INK = '#0b0b0b'; INK2 = '#52514e'; GRID = '#e6e5e1'; SURF = '#fcfcfb'
plt.rcParams.update({'font.family': ['AppleGothic', 'Apple SD Gothic Neo', 'DejaVu Sans'], 'axes.unicode_minus': False,
	'figure.facecolor': SURF, 'axes.facecolor': SURF, 'axes.edgecolor': GRID, 'axes.grid': True, 'grid.color': GRID,
	'axes.spines.top': False, 'axes.spines.right': False, 'axes.spines.left': False, 'text.color': INK,
	'axes.labelcolor': INK2, 'xtick.color': INK2, 'ytick.color': INK2, 'font.size': 11})
s = json.load(open(f'{E}/summary.json'))['modes']
arms = [('sync', '① 동기 직접 발송'), ('db_poll', '② DB 폴링 릴레이\n(1s/100)'), ('outbox_kafka', '③ outbox+Kafka\n(현재, 릴레이 5s/100)'), ('outbox_kafka_1s', "③' 릴레이 1초\n(보조)")]
col = {'sync': C[3], 'db_poll': C[0], 'outbox_kafka': C[1], 'outbox_kafka_1s': C[2]}
def fmt(v, _): return f'{v/1000:.0f}s' if v >= 1000 else f'{v:.0f}ms'

# 1 W1: 요청 p95 vs 생성→발송 p95
fig, (a, b) = plt.subplots(1, 2, figsize=(10, 3.9))
labels = [l for _, l in arms]
req = [s[m]['w1']['request_p95_ms'] for m, _ in arms]; e2e = [s[m]['w1']['e2e_p95_ms'] for m, _ in arms]
a.bar(labels, req, color=[col[m] for m, _ in arms]); a.set_ylabel('요청 응답 p95 (ms)'); a.set_title('W1 · 알림 1건 요청 2,000건, 50 rps — 요청이 얼마나 기다리나', loc='left', fontsize=10, color=INK)
for i, v in enumerate(req): a.text(i, v + 1, f'{v:.1f}ms', ha='center', fontsize=9, color=INK2)
a.tick_params(axis='x', labelsize=8); a.grid(axis='x', visible=False); a.set_ylim(0, 60)
b.bar(labels, e2e, color=[col[m] for m, _ in arms]); b.set_yscale('log'); b.yaxis.set_major_formatter(FuncFormatter(fmt)); b.set_ylabel('생성 → 단말 발송 p95 (로그)')
for i, v in enumerate(e2e): b.text(i, v * 1.25, fmt(v, 0), ha='center', fontsize=9, color=INK2)
b.set_title('같은 W1 — 알림이 실제로 나가기까지 (50 rps 유입 > 폴러/릴레이 상한)', loc='left', fontsize=10, color=INK); b.tick_params(axis='x', labelsize=8); b.grid(axis='x', visible=False); b.set_ylim(10, 200000)
fig.tight_layout(); fig.savefig(f'{OUT}/notif-w1.png', dpi=160); plt.close(fig); print('wrote notif-w1')

# 2 W2 버스트 소화 시간 + W3 유실·요청 실패
fig, (a, b) = plt.subplots(1, 2, figsize=(10, 3.9), gridspec_kw={'width_ratios': [3, 2]})
burst = [s[m]['w2']['burst_complete_s'] for m, _ in arms]; thr = [s[m]['w2']['throughput_per_s'] for m, _ in arms]
a.bar(labels, burst, color=[col[m] for m, _ in arms])
for i, (v, t) in enumerate(zip(burst, thr)): a.text(i, v + 8, f'{v:.0f}초\n{t:.1f}건/초', ha='center', fontsize=9, color=INK2)
a.set_ylabel('1만 건 전부 SENT 까지 (초)'); a.set_ylim(0, 620); a.set_title('W2 · 행사 시작 팬아웃 1만 건 — 발송 스레드 1개 + 폴링 상수가 상한', loc='left', fontsize=10, color=INK); a.tick_params(axis='x', labelsize=8); a.grid(axis='x', visible=False)
w3 = {m: json.load(open(f'{E}/w3-{m}-0.json')) for m, _ in arms[:3]}
lost = [w3['sync']['fcm']['rejected'], 0, 0]; failed_req = [998, 0, 0]
x = range(3); b.bar([i - 0.2 for i in x], lost, 0.4, color=C[3], label='끝내 못 보낸 알림'); b.bar([i + 0.2 for i in x], failed_req, 0.4, color='#e34948', label='사용자 요청 실패(5xx)')
for i, (l, f) in enumerate(zip(lost, failed_req)): b.text(i - 0.2, l + 30, f'{l:,}', ha='center', fontsize=9, color=INK2); b.text(i + 0.2, f + 30, f'{f:,}', ha='center', fontsize=9, color=INK2)
b.set_xticks(list(x)); b.set_xticklabels([l for _, l in arms[:3]], fontsize=8); b.set_ylim(0, 1900); b.set_ylabel('건'); b.legend(frameon=False, fontsize=8, loc='upper right'); b.set_title('W3 · FCM 20초 장애 — 무엇을 잃나', loc='left', fontsize=10, color=INK); b.grid(axis='x', visible=False)
fig.tight_layout(); fig.savefig(f'{OUT}/notif-w2w3.png', dpi=160); plt.close(fig); print('wrote notif-w2w3')

# 3 W3 타임라인: SENT 누적 (②③) + 장애 창
fig, ax = plt.subplots(figsize=(9, 3.8))
for m, lab in arms[1:3]:
	tl = w3[m]['timeline']; ax.plot([p['t_s'] for p in tl], [p['SENT'] for p in tl], lw=2, color=col[m], label=lab.replace('\n', ' '))
o = w3['db_poll']['outage']; ax.axvspan(o['started_at_s'], o['started_at_s'] + o['seconds'], color='#e34948', alpha=0.15, lw=0); ax.text(o['started_at_s'] + 1, 10600, 'FCM 503 (20초)', fontsize=9, color=INK2)
for m, lab in arms[1:3]:
	f = w3[m]['fcm']; rec = f['first_ok_after_outage_wall'] - f['outage_end_wall']; ax.text(60, 9000 if m == 'db_poll' else 8200, f"{lab.splitlines()[0]} 복구 후 첫 성공 {rec:.2f}초", fontsize=9, color=col[m])
ax.set_xlabel('버스트 생성 후 경과 (초)'); ax.set_ylabel('SENT 누적 (건)'); ax.set_ylim(0, 11800); ax.legend(frameon=False, loc='lower right', fontsize=9)
ax.set_title('W3 · 장애 동안 행이 남아 있으니 복구 뒤 전부 나간다 (①은 장애 중 1,508건이 DEAD)', loc='left', fontsize=11, color=INK); ax.grid(axis='x', visible=False)
fig.tight_layout(); fig.savefig(f'{OUT}/notif-w3-timeline.png', dpi=160); plt.close(fig); print('wrote notif-w3-timeline')
