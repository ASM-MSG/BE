#!/usr/bin/env python3
"""k6 --out json 결과를 캐시 TTL 주기로 접어 만료 시점의 지연이 실재하는지 본다.

k6 기본 요약은 전체 구간 하나로 뭉뚱그린 백분위만 준다. 캐시 만료는 30초에 한 번,
수십 밀리초 동안만 일어나므로 그 요약에서는 p99 가 조금 높은 것으로만 보이고 원인이
캐시인지 부하인지 구분되지 않는다. 측정 시각을 TTL 로 나눈 나머지(위상)로 묶으면
캐시가 원인일 때만 특정 위상에 지연이 몰린다.

무엇을 관측하는가:
    캐시 만료 직후의 재계산 1회가 Redis 를 붙잡는 동안 뒤에 줄선 요청이 밀리는 지연이다.
    "여러 요청이 동시에 중복 재계산하는 캐시 스탬피드"가 아니다 — 운영 코드는
    EXISTS+ZUNIONSTORE+EXPIRE 를 한 덩어리 Lua 로 실행하고 Redis 는 그걸 원자적으로
    처리하므로, 뒤이은 요청은 EXISTS=1 을 보고 재계산을 건너뛴다. 실제 재계산 횟수는
    `redis-cli INFO commandstats` 의 cmdstat_zunionstore 로 직접 확인할 것.

판정이 조심스러운 이유:
    "가장 느린 위상 하나를 사후에 고른 뒤 나머지와 비교"하는 방식은 캐시와 무관한
    단발 정지(GC·스케줄링)에도 큰 배율을 낸다. 그래서 배율만으로 결론짓지 않고,
    TTL 주기마다 같은 위상이 반복되는지(재현성)를 함께 본다. 단발 이벤트는 한 주기에만
    나타나므로 여기서 걸러진다.

사용: k6 run --out json=r.json ... && ./load-test/analyze-expiry.py r.json [TTL초]
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from datetime import datetime

DEFAULT_TTL = 30           # HotZoneServiceImpl.TOP_TTL_SECONDS
MIN_CYCLES = 4             # 이보다 짧게 돌린 실행은 주기성을 말할 표본이 안 된다
MIN_SAMPLES_PER_PHASE = 30 # 위상별 표본이 이보다 적으면 그 위상의 p99 는 사실상 최대값이다
RATIO_THRESHOLD = 3.0      # 배율 문턱. 이것만으로는 결론을 내지 않는다
PHASE_TOLERANCE = 1        # 주기 간 최악 위상이 이 범위 안이면 "같은 위상"으로 본다

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
			points.append((parse_time(data["time"]), data["value"]))
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
	ttl = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_TTL

	points = load_points(sys.argv[1])
	if not points:
		print("hotzone_latency 포인트가 없습니다 — k6 를 --out json 으로 돌렸는지 확인하세요.")
		sys.exit(1)

	start = min(ts for ts, _ in points)
	seconds = defaultdict(list)
	for ts, value in points:
		seconds[int((ts - start).total_seconds())].append(value)

	span = max(seconds) + 1
	cycles = span // ttl
	all_values = [v for _, v in points]

	print()
	print("═" * 64)
	print(f"  총 {len(points)}건 · {span}초 · TTL {ttl}초 주기 {cycles}회")
	print(f"  중앙값 {statistics.median(all_values):.2f}ms · p99 {percentile(all_values, 99):.2f}ms"
		f" · max {max(all_values):.2f}ms")
	print("═" * 64)

	# ── 위상별 집계 ─────────────────────────────────────────────────────
	phases = defaultdict(list)
	for second, values in seconds.items():
		phases[second % ttl].extend(values)

	phase_stats = [(p, statistics.median(v), percentile(v, 99), max(v), len(v))
		for p, v in sorted(phases.items())]
	worst = max(phase_stats, key=lambda s: s[2])
	median_p99 = statistics.median([s[2] for s in phase_stats])
	ratio = worst[2] / median_p99 if median_p99 else 0
	min_samples = min(s[4] for s in phase_stats)

	print(f"  {'위상':>4} {'건수':>6} {'med':>8} {'p99':>9} {'max':>9}")
	print("─" * 64)
	for phase, med, p99, peak, n in phase_stats:
		mark = "  ← 최악" if phase == worst[0] else ""
		print(f"  {phase:>4} {n:>6} {med:>8.2f} {p99:>9.2f} {peak:>9.2f}{mark}")

	# ── 재현성: 주기마다 최악 위상이 같은가 ──────────────────────────────
	# 단발 GC 나 스케줄링 정지는 한 주기에만 나타나므로 여기서 걸러진다.
	per_cycle_worst = []
	for cycle in range(cycles):
		window = {s % ttl: seconds[s] for s in seconds if cycle * ttl <= s < (cycle + 1) * ttl}
		if not window:
			continue
		per_cycle_worst.append(max(window.items(), key=lambda kv: max(kv[1]))[0])

	def near(a, b):
		diff = abs(a - b) % ttl
		return min(diff, ttl - diff) <= PHASE_TOLERANCE

	agree = sum(1 for p in per_cycle_worst if near(p, worst[0]))
	agreement = agree / len(per_cycle_worst) if per_cycle_worst else 0

	print("═" * 64)
	print(f"  최악 위상 {worst[0]}초: p99 {worst[2]:.2f}ms · 위상 p99 중앙값 {median_p99:.2f}ms"
		f" · 배율 {ratio:.2f}")
	print(f"  주기별 최악 위상: {per_cycle_worst}")
	print(f"  최악 위상 재현율: {agree}/{len(per_cycle_worst)} 주기 ({agreement * 100:.0f}%)")
	print()

	# ── 판정 ────────────────────────────────────────────────────────────
	# 셋을 모두 만족해야 "주기적"이라고 말한다. 하나라도 빠지면 증거 부족이다.
	problems = []
	if cycles < MIN_CYCLES:
		problems.append(f"주기 {cycles}회로 부족하다 (최소 {MIN_CYCLES}회 — 더 길게 돌릴 것)")
	if min_samples < MIN_SAMPLES_PER_PHASE:
		problems.append(f"위상당 최소 표본 {min_samples}건으로 부족하다 "
			f"(최소 {MIN_SAMPLES_PER_PHASE}건 — RPS 를 올리거나 더 길게 돌릴 것)")
	if ratio < RATIO_THRESHOLD:
		problems.append(f"배율 {ratio:.2f}로 문턱({RATIO_THRESHOLD}) 미만이다")
	if agreement < 0.5:
		problems.append(f"재현율 {agreement * 100:.0f}%로 낮다 — 단발 이벤트일 가능성이 크다")

	if problems:
		print("  판정: 증거 부족")
		for p in problems:
			print(f"    · {p}")
		print()
		print("  이 출력만으로 캐시 만료가 원인이라고 말하지 말 것.")
	else:
		print(f"  판정: 위상 {worst[0]}초에 지연이 주기적으로 몰린다")
		print(f"    · 배율 {ratio:.2f} · 재현율 {agreement * 100:.0f}% · 주기 {cycles}회")
		print()
		print("  캐시 만료 시점의 재계산이 유력한 원인이다. 다만 이 도구는 상관만 본다.")
		print("  실제 재계산 횟수는 아래로 직접 확인할 것 (만료 횟수와 같아야 정상):")
		print("    docker exec fillmap-local-redis redis-cli INFO commandstats | grep zunionstore")
	print("═" * 64)


if __name__ == "__main__":
	main()
