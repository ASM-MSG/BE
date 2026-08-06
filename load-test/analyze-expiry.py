#!/usr/bin/env python3
"""k6 --out json 결과를 초 단위로 재집계해 캐시 만료 주기의 지연 스파이크를 찾는다.

k6 기본 요약은 전체 구간 하나로 뭉뚱그린 백분위만 준다. 스탬피드는 30초에 한 번,
수십 ms 동안만 일어나는 현상이라 그 요약에서는 p99 가 조금 높은 것으로만 보이고
원인이 캐시 만료인지 부하 자체인지 구분되지 않는다. 초 단위로 쪼개면 주기가 드러난다.

사용: k6 run --out json=r.json ... && ./load-test/analyze-stampede.py r.json
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from datetime import datetime

TTL_SECONDS = 30  # HotZoneServiceImpl.TOP_TTL_SECONDS

# k6 는 소수부 뒤 0 을 떼고 내보낸다("...:43.99836+09:00"). 3.11 미만 fromisoformat 은
# 마이크로초를 3자리 또는 6자리로만 받으므로 6자리로 채워 준다.
FRACTION = re.compile(r"\.(\d{1,6})(?=[+\-Z])")


def parse_time(raw):
	padded = FRACTION.sub(lambda m: "." + m.group(1).ljust(6, "0"), raw)
	return datetime.fromisoformat(padded.replace("Z", "+00:00"))


def load_points(path):
	"""hotzone_latency Point 를 (초 단위 시각, 지연ms) 로 뽑는다."""
	points = []
	with open(path) as f:
		for line in f:
			line = line.strip()
			if not line:
				continue
			try:
				row = json.loads(line)
			except json.JSONDecodeError:
				continue
			if row.get("type") != "Point" or row.get("metric") != "hotzone_latency":
				continue
			data = row["data"]
			ts = parse_time(data["time"])
			points.append((ts, data["value"]))
	return points


def percentile(values, p):
	if not values:
		return 0.0
	ordered = sorted(values)
	index = min(int(len(ordered) * p / 100), len(ordered) - 1)
	return ordered[index]


def main():
	if len(sys.argv) < 2:
		print(__doc__)
		sys.exit(1)

	points = load_points(sys.argv[1])
	if not points:
		print("hotzone_latency 포인트가 없습니다 — k6 를 --out json 으로 돌렸는지 확인하세요.")
		sys.exit(1)

	start = min(ts for ts, _ in points)
	buckets = defaultdict(list)
	for ts, value in points:
		buckets[int((ts - start).total_seconds())].append(value)

	all_values = [v for _, v in points]
	baseline_med = statistics.median(all_values)
	overall_p99 = percentile(all_values, 99)

	print()
	print("═" * 62)
	print(f"  총 {len(points)}건 · 중앙값 {baseline_med:.2f}ms · p99 {overall_p99:.2f}ms"
		f" · max {max(all_values):.2f}ms")
	print("═" * 62)

	# ── 상위 스파이크 초 ────────────────────────────────────────────────
	# "몇 배 넘으면 스파이크"는 중앙값이 1ms대일 때 노이즈까지 다 잡는다.
	# 절대 순위로 상위 8초만 뽑아 그 시각이 TTL 주기에 걸리는지 본다.
	ranked = sorted(buckets.items(), key=lambda kv: max(kv[1]), reverse=True)[:8]
	print(f"  느린 초 상위 8   {'건수':>6} {'med':>8} {'p99':>8} {'max':>9}")
	print("─" * 62)
	for second, values in sorted(ranked):
		print(f"  {second:>4}초           {len(values):>6} {statistics.median(values):>8.2f}"
			f" {percentile(values, 99):>8.2f} {max(values):>9.2f}")

	# ── TTL 주기 위상 분석 (핵심 검정) ──────────────────────────────────
	# 스탬피드가 있다면 캐시가 만료되는 위상(0초 근처)에만 지연이 몰린다.
	# 부하나 GC 때문이면 위상과 무관하게 흩어진다. 이 대비가 원인을 가른다.
	phases = defaultdict(list)
	for second, values in buckets.items():
		phases[second % TTL_SECONDS].extend(values)

	phase_stats = [(p, statistics.median(v), percentile(v, 99), max(v), len(v))
		for p, v in sorted(phases.items())]
	worst_phase = max(phase_stats, key=lambda s: s[2])
	median_of_phase_p99 = statistics.median([s[2] for s in phase_stats])

	print()
	print("─" * 62)
	print(f"  TTL {TTL_SECONDS}초 주기 위상별 지연 (같은 위상끼리 묶음)")
	print("─" * 62)
	print(f"  {'위상':>4} {'건수':>6} {'med':>8} {'p99':>8} {'max':>9}")
	for phase, med, p99, peak, n in phase_stats:
		mark = "  ← 최악 위상" if phase == worst_phase[0] else ""
		print(f"  {phase:>4} {n:>6} {med:>8.2f} {p99:>8.2f} {peak:>9.2f}{mark}")

	print("═" * 62)
	ratio = worst_phase[2] / median_of_phase_p99 if median_of_phase_p99 else 0
	print(f"  최악 위상 {worst_phase[0]}초: p99 {worst_phase[2]:.2f}ms")
	print(f"  위상 p99 중앙값: {median_of_phase_p99:.2f}ms · 비율 {ratio:.2f}배")
	print()
	if ratio >= 2:
		print(f"  → 특정 위상에 지연이 몰린다. 캐시 만료 스탬피드가 실재한다.")
	else:
		print(f"  → 위상 간 차이가 없다({ratio:.2f}배). 이 부하·데이터 규모에서는")
		print(f"     캐시 만료가 지연으로 드러나지 않는다 — MSG-233 D4 의 '무해' 주장이 성립한다.")
	print("═" * 62)


if __name__ == "__main__":
	main()
