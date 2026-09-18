#!/usr/bin/env python3
"""Local, isolated storage comparison; never an API/production benchmark.

Dependencies: psycopg[binary]==3.2.10 redis==6.4.0 (temporary venv).
See docs/reports/2026-09-17-hotzone-alternatives.md for setup and limitations.
"""
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import gzip
import hashlib
import json
import math
from pathlib import Path
import platform
import random
import re
import statistics
import subprocess
import threading
import time

import psycopg
import redis

ROOT = Path(__file__).resolve().parents[1]
DSN = "host=127.0.0.1 port=15438 dbname=hotzone_bench user=bench password=bench"
HOUR = 3600
WIDTH = 6 * HOUR
BASE = int(datetime(2026, 9, 17, tzinfo=timezone.utc).timestamp())
NOW = BASE + 3 * HOUR
FIRST = (NOW // WIDTH - 7) * WIDTH
KEYS = [f"compare:bucket:{i}" for i in range(NOW // WIDTH - 7, NOW // WIDTH + 1)]
SQL_CACHE_LOCK = threading.Lock()
SQL = """SELECT grid_id, count(*) AS score FROM signals
WHERE occurred_at >= %s AND occurred_at < %s
GROUP BY grid_id ORDER BY score DESC, grid_id COLLATE "C" DESC LIMIT 50"""


def source_script(filename, constant):
    source = (ROOT / "src/main/java/com/msg/fillmap/hotzone/service" / filename).read_text()
    if filename == "HotZoneServiceImpl.java":
        for declaration in (r"BUCKET_SECONDS\s*=\s*21600L", r"LOOKBACK_BUCKETS\s*=\s*8",
                            r"TOP_TTL_SECONDS\s*=\s*30L"):
            assert re.search(declaration, source), "Service constants changed; update benchmark assumptions"
    body = source.split(constant + " = new DefaultRedisScript<>(", 1)[1].split(", Long.class)", 1)[0]
    return "".join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', body))


ENSURE = source_script("HotZoneServiceImpl.java", "ENSURE_TOP_SCRIPT")
INCREMENT = source_script("HotScoreCommandServiceImpl.java", "INCREMENT_SCRIPT")


def pg():
    conn = psycopg.connect(DSN, autocommit=True)
    conn.execute("SET statement_timeout = '2s'")
    return conn


def rc():
    return redis.Redis(host="127.0.0.1", port=16388, decode_responses=True, socket_timeout=3)


def stamp(ts):
    return datetime.fromtimestamp(ts, timezone.utc)


def dump(out, name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def stats(values):
    return {"n": len(values), "p50_ms": percentile(values, .5),
            "p95_ms": percentile(values, .95), "p99_ms": percentile(values, .99)}


def median_present(values):
    present = [v for v in values if v is not None]
    return statistics.median(present) if present else None


def top(counts):
    return sorted(counts.items(), key=lambda x: (x[1], x[0]), reverse=True)[:50]


def seed(conn, r, count, grids):
    # Both engines consume the same append-only upload signals, with no lost events.
    conn.execute("SET statement_timeout = '60s'")
    conn.execute("DROP TABLE IF EXISTS signals")
    conn.execute("CREATE TABLE signals (id bigint PRIMARY KEY, grid_id varchar(20) NOT NULL, occurred_at timestamptz NOT NULL)")
    rng = random.Random(183)
    buckets = defaultdict(Counter)
    exact, aligned = Counter(), Counter()
    with conn.cursor().copy("COPY signals FROM STDIN") as copy:
        for i in range(count):
            grid = f"g{rng.randrange(100) if rng.random() < .2 else rng.randrange(grids):09d}"
            ts = NOW - rng.randrange(1, 72 * HOUR + 1)
            copy.write_row((i, grid, stamp(ts)))
            buckets[ts // WIDTH][grid] += 1
            if ts >= NOW - 48 * HOUR:
                exact[grid] += 1
            if ts >= FIRST:
                aligned[grid] += 1
    conn.execute("CREATE INDEX signals_time_grid ON signals (occurred_at) INCLUDE (grid_id)")
    conn.execute("VACUUM (ANALYZE) signals")
    conn.execute("SET statement_timeout = '2s'")
    old = list(r.scan_iter("compare:*"))
    if old:
        r.delete(*old)
    for bucket, counts in buckets.items():
        key = f"compare:bucket:{bucket}"
        r.zadd(key, dict(counts))
        r.expire(key, 54 * HOUR)
    assert [(g, int(s)) for g, s in conn.execute(SQL, (stamp(NOW - 48 * HOUR), stamp(NOW)))] == top(exact)
    assert [(g, int(s)) for g, s in conn.execute(SQL, (stamp(FIRST), stamp(NOW)))] == top(aligned)
    r.eval(ENSURE, 9, "compare:verify", *KEYS, 30)
    actual = [(g, int(s)) for g, s in r.zrevrange("compare:verify", 0, 49, withscores=True)]
    assert actual == top(aligned), (actual, top(aligned))
    return {"events_72h": count, "configured_grids": grids,
            "active_grids_45h": len(aligned), "events_48h": sum(exact.values()),
            "events_45h": sum(aligned.values()), "correctness": "SQL exact and bucket-aligned Redis match Python oracle",
            "sql_table_and_indexes_bytes": conn.execute("SELECT pg_total_relation_size('signals')").fetchone()[0],
            "redis_all_72h_bucket_bytes": sum(r.memory_usage(f"compare:bucket:{b}") for b in buckets),
            "redis_selected_8_bucket_bytes": sum(r.memory_usage(k) or 0 for k in KEYS),
            "redis_top_bytes": r.memory_usage("compare:verify"),
            "sql_plan": conn.execute("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + SQL,
                                     (stamp(NOW - 48 * HOUR), stamp(NOW))).fetchone()[0]}


def query(mode, conn, r, *, lower=NOW - 48 * HOUR):
    if mode == "sql":
        return list(conn.execute(SQL, (stamp(lower), stamp(NOW))))
    if mode == "sql_cache_lock":
        hit = r.get("compare:sqltop")
        if hit is not None:
            return json.loads(hit)
        # ponytail: one load-generator process; multi-instance callers need shared coordination.
        with SQL_CACHE_LOCK:
            return query("sql_cache", conn, r, lower=lower)
    if mode == "sql_cache":
        hit = r.get("compare:sqltop")
        if hit is not None:
            return json.loads(hit)
        # Minimal SQL alternative: GET/query/SET EX, no cross-process single-flight.
        rows = list(conn.execute(SQL, (stamp(lower), stamp(NOW))))
        r.set("compare:sqltop", json.dumps(rows), ex=30)
        return rows
    key = "compare:redistop" if mode == "redis_cache" else "compare:recompute"
    if mode == "redis":
        r.zunionstore(key, KEYS)
    else:
        r.eval(ENSURE, 9, key, *KEYS, 30)
    return r.zrevrange(key, 0, 49, withscores=True)


def micro(conn, r):
    result = {}
    for mode in ("sql", "sql_cache", "redis", "redis_cache"):
        for _ in range(5):
            query(mode, conn, r)
        samples = []
        for _ in range(60):
            start = time.perf_counter()
            query(mode, conn, r)
            samples.append((time.perf_counter() - start) * 1000)
        result[mode] = {**stats(samples), "raw_ms": samples}
    # Equal-window control separates temporal approximation from storage costs.
    samples = []
    for _ in range(60):
        start = time.perf_counter()
        query("sql", conn, r, lower=FIRST)
        samples.append((time.perf_counter() - start) * 1000)
    result["sql_aligned_45h"] = {**stats(samples), "raw_ms": samples}
    return result


def resources(conn, r):
    sql_cpu = subprocess.check_output(
        ["docker", "exec", "fillmap-hotzone-compare-pg", "cat", "/sys/fs/cgroup/cpu.stat"], text=True)
    return {"postgres_cpu": dict(line.split() for line in sql_cpu.splitlines()),
            "redis_cpu": r.info("cpu"), "redis_memory": r.info("memory"),
            "redis_commands": r.info("commandstats"),
            "sql_calls": conn.execute("SELECT seq_scan,idx_scan FROM pg_stat_user_tables WHERE relname='signals'").fetchone()}


def load(out, mode, round_id, duration, rate):
    r = rc()
    with pg() as conn:
        r.delete("compare:sqltop", "compare:redistop")
        query(mode, conn, r)  # Warm initial cache; natural TTL expiry at approximately 30s.
        before = resources(conn, r)
    local = threading.local()
    connections, rows, futures = [], [], []
    capacity = threading.BoundedSemaphore(32)
    start = time.perf_counter()

    def task(due, index):
        began = time.perf_counter()
        try:
            if not hasattr(local, "conn"):
                local.conn, local.redis = pg(), rc()
                connections.append(local.conn)
            data = query(mode, local.conn, local.redis)
            assert len(data) == 50
            error = None
        except Exception as exc:
            error = type(exc).__name__
        end = time.perf_counter()
        rows.append({"i": index, "scheduled_s": due - start, "service_ms": (end - began) * 1000,
                     "latency_ms": (end - due) * 1000, "error": error})
        capacity.release()

    with ThreadPoolExecutor(max_workers=16) as pool:
        for i in range(int(duration * rate)):
            due = start + i / rate
            time.sleep(max(0, due - time.perf_counter()))
            if capacity.acquire(blocking=False):
                futures.append(pool.submit(task, due, i))
            else:
                rows.append({"i": i, "scheduled_s": i / rate, "error": "dropped_capacity"})
        for future in futures:
            future.result()
    for connection in connections:
        connection.close()
    with pg() as conn:
        after = resources(conn, r)
    raw_file = f"load-{mode}-{round_id}.jsonl.gz"
    with gzip.open(out / raw_file, "wt") as f:
        for row in sorted(rows, key=lambda x: x["i"]):
            f.write(json.dumps(row) + "\n")
    ok = [row for row in rows if not row["error"]]
    result = {"mode": mode, "round": round_id, "duration_s": duration, "rate": rate,
              "offered": len(rows), "errors": dict(Counter(x["error"] for x in rows if x["error"])),
              "latency": stats([x["latency_ms"] for x in ok]),
              "service": stats([x["service_ms"] for x in ok]),
              "expiry_29_32s": stats([x["latency_ms"] for x in ok if 29 <= x["scheduled_s"] < 32]),
              "pg_cpu_seconds": (int(after["postgres_cpu"]["usage_usec"]) - int(before["postgres_cpu"]["usage_usec"])) / 1e6,
              "redis_cpu_seconds": sum(after["redis_cpu"][k] - before["redis_cpu"][k]
                                       for k in ("used_cpu_sys", "used_cpu_user")),
              "redis_command_calls": {k: v["calls"] - before["redis_commands"].get(k, {}).get("calls", 0)
                                      for k, v in after["redis_commands"].items()},
              "resources_before": before, "resources_after": after, "raw": raw_file}
    dump(out, f"load-{mode}-{round_id}.json", result)
    print(json.dumps({k: result[k] for k in ("mode", "round", "errors", "latency")}), flush=True)
    return result


def accuracy(verify_redis=False):
    results = []
    r = rc() if verify_redis else None
    for scenario in ("uniform", "steady_hotspots", "old_burst"):
        rng = random.Random(233)
        events = []
        for _ in range(100_000):
            grid = rng.randrange(1000)
            ts = BASE + WIDTH - rng.randrange(1, 72 * HOUR + 1)
            if scenario == "steady_hotspots" and rng.random() < .5:
                grid = rng.randrange(50)
            if scenario == "old_burst" and rng.random() < .35:
                grid = rng.randrange(50)
                ts = BASE - 47 * HOUR + rng.randrange(HOUR)
            events.append((f"g{grid:09d}", ts))
        for phase in (0, 3 * HOUR, WIDTH - 1):
            now = BASE + phase
            exact = Counter(g for g, ts in events if now - 48 * HOUR <= ts < now)
            for width in (HOUR, 3 * HOUR, WIDTH):
                lower = (now // width - 48 * HOUR // width + 1) * width
                approx = Counter(g for g, ts in events if lower <= ts < now)
                a, b = top(exact), top(approx)
                if r is not None:
                    groups = defaultdict(Counter)
                    for g, ts in events:
                        if lower <= ts < now:
                            groups[ts // width][g] += 1
                    keys = [f"compare:accuracy:{i}" for i in range(lower // width, now // width + 1)]
                    r.delete("compare:accuracy:top", *keys)
                    for bucket, counts in groups.items():
                        r.zadd(f"compare:accuracy:{bucket}", dict(counts))
                    r.eval(ENSURE, len(keys) + 1, "compare:accuracy:top", *keys, 30)
                    actual = [(g, int(s)) for g, s in r.zrevrange("compare:accuracy:top", 0, 49, withscores=True)]
                    assert actual == b
                    r.delete("compare:accuracy:top", *keys)
                a_ids, b_ids = {g for g, _ in a}, {g for g, _ in b}
                assert sum(approx.values()) <= sum(exact.values())
                # Tie-aware recall counts alternatives tied with the exact cutoff as acceptable.
                cutoff = a[-1][1]
                results.append({"scenario": scenario, "phase_seconds": phase, "bucket_hours": width / HOUR,
                                "covered_hours": (now - lower) / HOUR,
                                "exact_events": sum(exact.values()), "approx_events": sum(approx.values()),
                                "omitted_fraction": 1 - sum(approx.values()) / sum(exact.values()),
                                "top50_overlap": len(a_ids & b_ids) / 50,
                                "tie_aware_top50": sum(exact[g] >= cutoff for g in b_ids) / 50,
                                "exact_top50": a, "approx_top50": b})
    # Boundary fixture: 3 valid uploads 47h old qualify exactly but vanish from 8 buckets.
    fixture = {"now": BASE, "upload": BASE - 47 * HOUR, "count": 3}
    assert BASE - 48 * HOUR <= fixture["upload"] < BASE
    assert fixture["upload"] < (BASE // WIDTH - 7) * WIDTH
    return {"results": results, "threshold_boundary_fixture": fixture,
            "redis_verified_cases": len(results) if verify_redis else 0}


def writes(conn, r):
    conn.execute("CREATE TABLE IF NOT EXISTS write_signals (id bigserial PRIMARY KEY, grid_id text, occurred_at timestamptz)")
    result = {}
    for mode in ("sql_insert", "redis_increment", "sql_insert_then_redis"):
        samples = []
        for _ in range(500):
            start = time.perf_counter()
            if mode != "redis_increment":
                conn.execute("INSERT INTO write_signals(grid_id,occurred_at) VALUES ('g000000001',%s)", (stamp(NOW),))
            if mode != "sql_insert":
                r.eval(INCREMENT, 1, "compare:write", "g000000001", 54 * HOUR)
            samples.append((time.perf_counter() - start) * 1000)
        result[mode] = {**stats(samples), "raw_ms": samples}
    return result


def width_costs(conn, r):
    """Same 1M source rows, smaller buckets; compare memory and recomputation."""
    results = []
    for width in (HOUR, 3 * HOUR, WIDTH):
        prefix = f"compare:width:{width}:"
        conn.execute("SET statement_timeout = '60s'")
        grouped = conn.execute("""SELECT floor(extract(epoch FROM occurred_at) / %s)::bigint,
            grid_id, count(*) FROM signals GROUP BY 1,2""", (width,))
        buckets = defaultdict(dict)
        for bucket, grid, score in grouped:
            buckets[bucket][grid] = score
        conn.execute("SET statement_timeout = '2s'")
        for bucket, scores in buckets.items():
            r.zadd(prefix + str(bucket), scores)
            r.expire(prefix + str(bucket), 54 * HOUR)
        keys = [prefix + str(b) for b in range(NOW // width - 48 * HOUR // width + 1, NOW // width + 1)]
        samples = []
        for _ in range(60):
            r.delete(prefix + "top")
            start = time.perf_counter()
            r.eval(ENSURE, len(keys) + 1, prefix + "top", *keys, 30)
            r.zrevrange(prefix + "top", 0, 49, withscores=True)
            samples.append((time.perf_counter() - start) * 1000)
        lower = (NOW // width - 48 * HOUR // width + 1) * width
        assert [(g, int(s)) for g, s in r.zrevrange(prefix + "top", 0, 49, withscores=True)] == query("sql", conn, r, lower=lower)
        results.append({"bucket_hours": width / HOUR, "key_count": len(keys),
                        "covered_hours": (NOW - lower) / HOUR,
                        "selected_bucket_bytes": sum(r.memory_usage(k) or 0 for k in keys),
                        "rebuild": stats(samples), "raw_ms": samples})
        r.delete(*list(r.scan_iter(prefix + "*")))
    return results


def summarize(out):
    groups = defaultdict(list)
    for path in out.glob("load-*.json"):
        data = json.loads(path.read_text())
        if not isinstance(data, dict):
            continue
        with gzip.open(out / data["raw"], "rt") as f:
            rows = [json.loads(line) for line in f]
        assert sorted(x["i"] for x in rows) == list(range(data["offered"]))
        assert sum(not x["error"] for x in rows) == data["latency"]["n"]
        assert Counter(x["error"] for x in rows if x["error"]) == Counter(data["errors"])
        groups[(data["mode"], data["rate"])].append(data)
    result = []
    for (mode, rate), runs in sorted(groups.items()):
        result.append({"mode": mode, "rate": rate, "rounds": len(runs),
                       "offered": sum(d["offered"] for d in runs),
                       "success": sum(d["latency"]["n"] for d in runs),
                       "errors": dict(sum((Counter(d["errors"]) for d in runs), Counter())),
                       "p95_ms": median_present(d["latency"]["p95_ms"] for d in runs),
                       "p99_ms": median_present(d["latency"]["p99_ms"] for d in runs),
                       "expiry_p99_ms": median_present(d["expiry_29_32s"]["p99_ms"] for d in runs),
                       "pg_cpu_seconds": statistics.median(d["pg_cpu_seconds"] for d in runs),
                       "redis_cpu_seconds": statistics.median(d["redis_cpu_seconds"] for d in runs)})
    dump(out, "summary.json", result)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=ROOT / "load-test/evidence/2026-09-17/hotzone")
    parser.add_argument("--duration", type=int, default=33)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--rate", type=int, default=100)
    parser.add_argument("--skip-load", action="store_true")
    parser.add_argument("--extra-checks", action="store_true", help="Use already seeded 1M rows, after main run")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.extra_checks:
        with pg() as conn:
            assert conn.execute("SELECT count(*) FROM signals").fetchone()[0] == 1_000_000
            dump(args.out, "bucket-widths.json", width_costs(conn, rc()))
        dump(args.out, "accuracy.json", accuracy(verify_redis=True))
        load(args.out, "sql", "low10", 33, 10)
        for round_id in range(3):
            load(args.out, "sql_cache_lock", round_id, 33, 100)
        summarize(args.out)
        return
    dump(args.out, "metadata.json", {"platform": platform.platform(), "python": platform.python_version(),
         "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
         "started_utc": datetime.now(timezone.utc).isoformat(), "now": NOW, "sql": SQL,
         "ensure_script": ENSURE, "increment_script": INCREMENT,
         "source_sha256": {f: hashlib.sha256((ROOT / f).read_bytes()).hexdigest() for f in (
             "src/main/java/com/msg/fillmap/hotzone/service/HotZoneServiceImpl.java",
             "src/main/java/com/msg/fillmap/hotzone/service/HotScoreCommandServiceImpl.java")}})
    dump(args.out, "accuracy.json", accuracy())
    r = rc()
    with pg() as conn:
        for count, grids in ((100_000, 1000), (1_000_000, 10_000)):
            data = seed(conn, r, count, grids)
            data["micro"] = micro(conn, r)
            dump(args.out, f"scale-{count}.json", data)
            print(f"seed + correctness + micro passed: {count}", flush=True)
        dump(args.out, "writes.json", writes(conn, r))
    results = []
    if not args.skip_load:
        modes = ["sql", "sql_cache", "redis", "redis_cache"]
        for round_id in range(args.rounds):
            # Rotate/reverse order to reduce systematic order bias.
            order = modes if round_id == 0 else list(reversed(modes)) if round_id == 1 else modes[2:] + modes[:2]
            for mode in order:
                results.append(load(args.out, mode, round_id, args.duration, args.rate))
    dump(args.out, "load-summary.json", results)
    if results:
        summarize(args.out)


if __name__ == "__main__":
    main()
