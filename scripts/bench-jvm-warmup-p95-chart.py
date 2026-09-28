#!/usr/bin/env python3
"""k6 --out json 원시 결과 여러 회차를 초 단위 p95로 접어 전/후 비교 선 차트(PNG)를 만든다.

사용: python3 scripts/bench-jvm-warmup-p95-chart.py out.png 라벨=raw.json [라벨=raw.json ...]
  예: python3 scripts/bench-jvm-warmup-p95-chart.py p95.png "전(워밍업 끔)=r1.json" "후 N=1,000=r5.json" "후 N=2,000=r6.json"
대상 지표는 핫구역 집계(tag name=hotzone_agg) http_req_duration. matplotlib 필요(venv 권장).
"""
import collections, datetime, json, os, re, sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

TARGET, GOAL_MS = "hotzone_agg", 300
COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]  # dataviz 기본 범주 팔레트 1~4 (validate_palette.js 통과)


def fold(raw):
	by, t0 = collections.defaultdict(list), None
	for line in open(raw):
		if '"http_req_duration"' not in line:
			continue
		d = json.loads(line)
		if d.get("type") != "Point" or d["data"]["tags"].get("name") != TARGET:
			continue
		iso = re.sub(r"\.(\d+)", lambda m: "." + m.group(1)[:6].ljust(6, "0"), d["data"]["time"]).replace("Z", "+00:00")
		ts = datetime.datetime.fromisoformat(iso).timestamp()
		t0 = ts if t0 is None else min(t0, ts)
		by[int(ts)].append(d["data"]["value"])
	def p95(v):
		v = sorted(v)
		return v[min(len(v) - 1, int(len(v) * 0.95))]
	return [(sec - int(t0), p95(v)) for sec, v in sorted(by.items())]


out, series = sys.argv[1], [a.rsplit("=", 1) for a in sys.argv[2:]]
plt.rcParams["font.family"] = ["Apple SD Gothic Neo", "AppleGothic", "sans-serif"]
plt.rcParams["axes.unicode_minus"] = False
folded = [(label, fold(raw)) for label, raw in series]

NO_ZOOM = bool(os.environ.get("NO_ZOOM"))  # 1이면 전체 축 한 장만(시리즈가 많아 확대 패널이 어지러울 때)
if NO_ZOOM:
	fig, top = plt.subplots(figsize=(9, 5), dpi=200)
	axes = (top,)
else:
	fig, (top, zoom) = plt.subplots(2, 1, figsize=(9, 7.2), dpi=200, sharex=True, gridspec_kw={"height_ratios": [3, 2], "hspace": 0.18})
	axes = (top, zoom)
fig.patch.set_facecolor("#fcfcfb")
for ax in axes:
	ax.set_facecolor("#fcfcfb")
	for (label, pts), color in zip(folded, COLORS):
		xs, ys = zip(*pts)
		ax.plot(xs, ys, color=color, linewidth=2, label=label, solid_capstyle="round")
	ax.axhline(GOAL_MS, color="#8a8984", linewidth=1, linestyle=(0, (4, 3)))
	ax.set_xlim(0, 61)
	ax.grid(axis="y", color="#e6e5e0", linewidth=0.8); ax.set_axisbelow(True)
	for s in ("top", "right"): ax.spines[s].set_visible(False)
	for s in ("left", "bottom"): ax.spines[s].set_color("#c9c8c2")
	ax.tick_params(colors="#52514e", labelsize=8.5)
	ax.set_ylabel("핫구역 집계 p95 (ms)", fontsize=9, color="#52514e")
top.set_ylim(0, None)
top.legend(frameon=False, fontsize=9, loc="upper right")
(top if NO_ZOOM else zoom).annotate(f"목표 p95 {GOAL_MS}ms", (20, GOAL_MS), xytext=(0, 5), textcoords="offset points", fontsize=8.5, color="#52514e")
# 직접 라벨: 전 선은 위 패널, 후 두 선은 확대 패널
lab0, pts0 = folded[0]
x0, y0 = pts0[20]
top.annotate(f"{lab0.split(' (')[0]}: 약 35초 동안 목표 위", (x0, y0), xytext=(10, 18), textcoords="offset points", fontsize=9, color="#52514e",
	arrowprops=dict(arrowstyle="-", color="#c9c8c2", lw=0.8))
if NO_ZOOM:
	top.set_xlabel("부하 시작 뒤 경과 (초)", fontsize=9.5, color="#52514e")
	# 직접 라벨 위치(초, 오프셋)는 LABEL_AT="7,9;12,-14;12,-16" 처럼 env 로 준다(시리즈 2번째부터 순서대로)
	spots = [tuple(map(int, t.split(","))) for t in os.environ.get("LABEL_AT", "").split(";") if t]
	for (label, pts), color, (sec, dy) in zip(folded[1:], COLORS[1:], spots):
		x, y = pts[sec]
		top.annotate(label, (x, y), xytext=(8, dy), textcoords="offset points", fontsize=9, color="#52514e",
			arrowprops=dict(arrowstyle="-", color="#c9c8c2", lw=0.8))
	fig.subplots_adjust(top=0.84, bottom=0.11, left=0.09, right=0.98)
	fig.text(0.07, 0.965, os.environ.get("TITLE", "재기동 직후 10배 부하 — JIT 워밍업 전후 초별 p95"), fontsize=12, color="#0b0b0b", va="top")
	fig.text(0.07, 0.925, "dev t3.small(2 vCPU) · k6 줌아웃 집계 2종 150 req/s 60초 + 카나리 5/s · healthy 직후 시작 · 2026-09-27",
		fontsize=8.5, color="#52514e", va="top")
	fig.savefig(out, facecolor=fig.get_facecolor())
	print(out)
	sys.exit()
zoom.set_ylim(0, 800)
zoom.set_title("아래 800ms 확대", loc="left", fontsize=9, color="#52514e", pad=4)
zoom.set_xlabel("부하 시작 뒤 경과 (초)", fontsize=9.5, color="#52514e")
for (label, pts), color, idx, off in zip(folded[1:], COLORS[1:], (0, 6, 12), ((18, 8), (12, -26), (14, 10))):
	x, y = pts[idx]
	zoom.annotate(label.split(" (")[0], (x, y), xytext=off, textcoords="offset points", fontsize=9, color="#52514e",
		arrowprops=dict(arrowstyle="-", color="#c9c8c2", lw=0.8))
# 매분 :02초 멈춤(워밍업과 무관) — 후 회차의 50초대 스파이크
zoom.text(38, 735, "50초대 스파이크 = 매분 :02초 멈춤 (워밍업과 무관, 별도 티켓)", fontsize=8.5, color="#52514e")
fig.text(0.07, 0.965, os.environ.get("TITLE", "재기동 직후 10배 부하 — JIT 워밍업 전후 초별 p95"), fontsize=12, color="#0b0b0b", va="top")
fig.text(0.07, 0.935, "dev t3.small(2 vCPU) · k6 줌아웃 집계 2종 150 req/s 60초 + 카나리 5/s · healthy 직후 시작 · 2026-09-27",
	fontsize=8.5, color="#52514e", va="top")
fig.subplots_adjust(top=0.88, bottom=0.08, left=0.09, right=0.98)
fig.savefig(out, facecolor=fig.get_facecolor())
print(out)
