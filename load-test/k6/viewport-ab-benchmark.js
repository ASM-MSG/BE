/*
 * MSG-73/128 · 격자 색칠 viewport 조회 — 쿼리 전략 A/B 부하 벤치마크 (k6)
 * ---------------------------------------------------------------------------
 * 같은 엔드포인트(GET /api/grids?...&strategy=A|B)를 두 전략으로 부하 비교한다.
 *   A = 정수 범위 스캔  (btree uq_grids_yx)
 *   B = GIST 공간 쿼리  (GiST idx_grids_bbox + ST_Intersects)
 *
 * 시나리오를 SCENARIO 로 선택한다(각 시나리오는 strategy A → B 를 순차로 돌려 간섭 없이 비교):
 *   s0  스모크            per-vu-iterations  1 VU × 5
 *   s1  동시 사용자 스냅샷  per-vu-iterations  VUS 명 × 3회 (기본 100)   ← "100명 동시 3번씩"
 *   s2  지속 부하(증가)     ramping-vus        0→50→100→150 VU
 *   s3  목표 RPS 고정(개방)  constant-arrival-rate  RATE req/s, 2분 (기본 300)
 *   s4  스트레스           ramping-arrival-rate   100→1000 req/s
 *
 * 실행:
 *   TOKEN="<jwt>" k6 run -e SCENARIO=s1 load-test/k6/viewport-ab-benchmark.js
 *   TOKEN=... k6 run -e SCENARIO=s3 -e RATE=200 --out experimental-prometheus-rw ...
 *   (env: BASE_URL, TOKEN, SCENARIO, VUS, RATE)
 * ---------------------------------------------------------------------------
 */

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// ── 설정 ────────────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const SCENARIO = (__ENV.SCENARIO || 's1').toLowerCase();
const VUS = Number(__ENV.VUS || 100);
const RATE = Number(__ENV.RATE || 300);

const SEOUL = { minLat: 37.42, maxLat: 37.70, minLng: 126.76, maxLng: 127.18 };
const SPAN = { lat: [0.02, 0.06], lng: [0.025, 0.075] };

// ── 전략별 커스텀 메트릭 (strategy 태그로 분리) ─────────────────────────────
const latency = new Trend('viewport_latency', true);
const failRate = new Rate('viewport_failed');
const cells = new Counter('viewport_cells_returned');

function rnd(min, max) {
	return Math.random() * (max - min) + min;
}

function randomViewport() {
	const latSpan = rnd(SPAN.lat[0], SPAN.lat[1]);
	const lngSpan = rnd(SPAN.lng[0], SPAN.lng[1]);
	const swLat = rnd(SEOUL.minLat, SEOUL.maxLat - latSpan);
	const swLng = rnd(SEOUL.minLng, SEOUL.maxLng - lngSpan);
	return { swLat, swLng, neLat: swLat + latSpan, neLng: swLng + lngSpan };
}

// ── 시나리오 정의: executor 옵션 + 예상 지속(초, B startTime 계산용) ─────────
function scenarioSpec() {
	switch (SCENARIO) {
		case 's0':
			return { opts: { executor: 'per-vu-iterations', vus: 1, iterations: 5, maxDuration: '30s' }, dur: 30 };
		case 's1':
			return { opts: { executor: 'per-vu-iterations', vus: VUS, iterations: 3, maxDuration: '60s' }, dur: 60 };
		case 's2':
			return {
				opts: {
					executor: 'ramping-vus', startVUs: 0, stages: [
						{ target: 50, duration: '20s' }, { target: 100, duration: '20s' },
						{ target: 150, duration: '30s' }, { target: 0, duration: '10s' },
					],
				}, dur: 80,
			};
		case 's3':
			return {
				opts: {
					executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '2m',
					preAllocatedVUs: Math.max(50, VUS), maxVUs: 600,
				}, dur: 120,
			};
		case 's4':
			return {
				opts: {
					executor: 'ramping-arrival-rate', startRate: 100, timeUnit: '1s',
					preAllocatedVUs: 100, maxVUs: 1000, stages: [
						{ target: 200, duration: '20s' }, { target: 500, duration: '20s' },
						{ target: 1000, duration: '20s' }, { target: 0, duration: '10s' },
					],
				}, dur: 70,
			};
		default:
			throw new Error(`알 수 없는 SCENARIO=${SCENARIO} (s0~s4)`);
	}
}

const spec = scenarioSpec();
const GAP = 5; // A 종료 후 B 시작까지 간격(초)

export const options = {
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
	scenarios: {
		strategy_a: { ...spec.opts, exec: 'hitViewport', env: { STRATEGY: 'A' }, tags: { strategy: 'A' }, startTime: '0s' },
		strategy_b: { ...spec.opts, exec: 'hitViewport', env: { STRATEGY: 'B' }, tags: { strategy: 'B' }, startTime: `${spec.dur + GAP}s` },
	},
	thresholds: {
		'viewport_latency{strategy:A}': ['p(95)<150', 'p(99)<300'],
		'viewport_latency{strategy:B}': ['p(95)<300', 'p(99)<600'],
		'viewport_failed': ['rate<0.01'],
		'http_req_failed': ['rate<0.01'],
	},
};

// ── VU 로직 ─────────────────────────────────────────────────────────────────
export function hitViewport() {
	const strategy = __ENV.STRATEGY;
	const vp = randomViewport();
	const url =
		`${BASE_URL}/api/grids?swLat=${vp.swLat.toFixed(6)}&swLng=${vp.swLng.toFixed(6)}` +
		`&neLat=${vp.neLat.toFixed(6)}&neLng=${vp.neLng.toFixed(6)}&strategy=${strategy}`;

	const res = http.get(url, {
		headers: TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {},
		tags: { strategy, name: 'viewport' },
	});

	const tag = { strategy };
	latency.add(res.timings.duration, tag);

	const ok = check(res, {
		'status 200': (r) => r.status === 200,
		'body is JSON array': (r) => {
			try {
				return Array.isArray(JSON.parse(r.body).body);
			} catch (_) {
				return false;
			}
		},
	});

	failRate.add(!ok, tag);
	if (ok) {
		cells.add((JSON.parse(res.body).body || []).length, tag);
	}
}

// ── 요약: A vs B 비교표 ──────────────────────────────────────────────────────
export function handleSummary(data) {
	const m = data.metrics;
	const pick = (stat, strat) => {
		const sub = m[`viewport_latency{strategy:${strat}}`];
		const v = sub && sub.values ? sub.values[stat] : undefined;
		return v === undefined ? '   —   ' : v.toFixed(2);
	};
	const row = (label, stat) =>
		`  ${label.padEnd(16)} A=${pick(stat, 'A').padStart(9)}   B=${pick(stat, 'B').padStart(9)}`;

	const report = [
		'',
		'════════════════════════════════════════════════════════',
		`  MSG-73 viewport 전략 A(정수범위) vs B(GIST)  ·  시나리오 ${SCENARIO}`,
		'════════════════════════════════════════════════════════',
		row('지연 avg (ms)', 'avg'),
		row('지연 p95 (ms)', 'p(95)'),
		row('지연 p99 (ms)', 'p(99)'),
		row('지연 max (ms)', 'max'),
		'────────────────────────────────────────────────────────',
		`  전체 요청 ${m.http_reqs ? m.http_reqs.values.count : '?'} · 실패율 ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) : '?'}%`,
		'  → 낮은 전략을 기본 경로로 채택',
		'════════════════════════════════════════════════════════',
		'',
	].join('\n');

	return { stdout: report + '\n' + textSummary(data, { indent: '  ', enableColors: true }) };
}
