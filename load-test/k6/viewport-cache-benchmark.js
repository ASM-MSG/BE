/*
 * 뷰포트 점령 격자 조회 — 캐시 전략 비교 부하 (GET /api/grids)
 * ---------------------------------------------------------------------------
 * 같은 부하를 캐시 전략(none·local·redis·two-level)과 인스턴스 수(1·4)만 바꿔 반복한다.
 * 전략은 서버 환경변수(VIEWPORT_CACHE_MODE)가 정하고 이 스크립트는 모른다 — LABEL 로만 적는다.
 *
 * 부하 모델: 사용자 세션. 뷰포트 조회는 개인 도감(내 점령 격자)이라 캐시가 사용자별이다.
 *   - VU 마다 사용자 한 명(TOKENS_FILE 의 토큰)을 맡는다.
 *   - 세션 = 서울 안 임의 뷰포트에서 시작해 STEPS 번 팬(한 변의 PAN 비율만큼 이동)하고 끝난다.
 *   - 그다음 새 세션. 팬 사이에 sleep 은 없다 — 도착률은 executor 가 고정한다.
 *   이웃한 팬끼리 스냅 블록을 공유하므로 캐시 적중이 생기고, 세션이 바뀌면 다른 곳을 본다.
 *
 * 실행:
 *   TOKENS_FILE=load-test/results/tokens.json k6 run -e RATE=300 -e DURATION=3m -e LABEL=local-x4 \
 *     --summary-export=out.json load-test/k6/viewport-cache-benchmark.js
 *   (env: BASE_URL, TOKENS_FILE, RATE, DURATION, LABEL, STEPS(기본 12), PAN(기본 0.35), SIZE(기본 1000))
 * ---------------------------------------------------------------------------
 */

import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 300);
const DURATION = __ENV.DURATION || '3m';
const LABEL = __ENV.LABEL || '';
const STEPS = Number(__ENV.STEPS || 12);
const PAN = Number(__ENV.PAN || 0.35);
const SIZE = Number(__ENV.SIZE || 1000);

const tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS_FILE || '../results/tokens.json')));

// seed-viewport-bench.sql 이 격자를 채운 범위 — 이 밖이면 결과가 비어 캐시와 무관하게 빨라진다.
const SEOUL = { minLat: 37.45, maxLat: 37.65, minLng: 126.85, maxLng: 127.15 };
const SPAN = { lat: [0.02, 0.06], lng: [0.025, 0.075] };

const latency = new Trend('viewport_latency', true);
const failRate = new Rate('viewport_failed');
const cells = new Counter('viewport_cells_returned');
const emptyRate = new Rate('viewport_empty_rate');

function rnd(min, max) {
	return Math.random() * (max - min) + min;
}

function clamp(v, min, max) {
	return Math.min(max, Math.max(min, v));
}

function newSession() {
	const latSpan = rnd(SPAN.lat[0], SPAN.lat[1]);
	const lngSpan = rnd(SPAN.lng[0], SPAN.lng[1]);
	return {
		latSpan, lngSpan, left: STEPS,
		swLat: rnd(SEOUL.minLat, SEOUL.maxLat - latSpan),
		swLng: rnd(SEOUL.minLng, SEOUL.maxLng - lngSpan),
	};
}

function pan(s) {
	// 네 방향 중 하나로 한 변의 PAN 비율만큼. 상자 밖으로 나가면 잘라 붙인다.
	const dir = Math.floor(Math.random() * 4);
	if (dir === 0) s.swLat = clamp(s.swLat + s.latSpan * PAN, SEOUL.minLat, SEOUL.maxLat - s.latSpan);
	if (dir === 1) s.swLat = clamp(s.swLat - s.latSpan * PAN, SEOUL.minLat, SEOUL.maxLat - s.latSpan);
	if (dir === 2) s.swLng = clamp(s.swLng + s.lngSpan * PAN, SEOUL.minLng, SEOUL.maxLng - s.lngSpan);
	if (dir === 3) s.swLng = clamp(s.swLng - s.lngSpan * PAN, SEOUL.minLng, SEOUL.maxLng - s.lngSpan);
	s.left--;
}

// 워밍업 30초 + 본 측정. 매 run 이 새 JVM(1 CPU) 이라 첫 몇 초는 JIT·풀 초기화로 느리고, 그 백로그가
// p95 를 통째로 끌어올린다(실측: 150 rps 1분에서 med 4.9ms 인데 p95 814ms). 그래서 지연 통계는 measure
// 위상만 본다. 콜드 스타트의 DB 조회 폭주는 k6 가 아니라 서버 카운터 시계열(metrics.csv)로 따로 본다.
const WARMUP = __ENV.WARMUP || '30s';
const arrival = (duration) => ({
	executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration,
	preAllocatedVUs: Math.max(50, Math.ceil(RATE / 3)), maxVUs: 1200, exec: 'hitViewport',
});

export const options = {
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
	scenarios: {
		warmup: { ...arrival(WARMUP), tags: { phase: 'warmup' } },
		measure: { ...arrival(DURATION), startTime: WARMUP, tags: { phase: 'measure' } },
	},
	thresholds: {
		// 하위 메트릭은 threshold 가 참조해야 요약에 나온다.
		'viewport_latency{phase:measure}': ['p(99)<10000'],
		'viewport_failed{phase:measure}': ['rate<0.01'],
		'http_reqs{phase:measure}': ['count>0'],
		viewport_empty_rate: ['rate<0.5'],
		'dropped_iterations{scenario:measure}': ['count<1000'],
	},
};

// VU 별 상태. k6 는 VU 마다 스크립트 스코프를 따로 두므로 모듈 변수가 곧 VU 상태다.
let session = null;
let token = null;

export function hitViewport() {
	if (token === null) token = tokens[(__VU - 1) % tokens.length];
	if (session === null || session.left <= 0) session = newSession();
	else pan(session);

	const url = `${BASE_URL}/api/grids?swLat=${session.swLat.toFixed(6)}&swLng=${session.swLng.toFixed(6)}`
		+ `&neLat=${(session.swLat + session.latSpan).toFixed(6)}&neLng=${(session.swLng + session.lngSpan).toFixed(6)}&size=${SIZE}`;
	const res = http.get(url, { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'viewport' } });

	latency.add(res.timings.duration);
	const ok = check(res, {
		'status 200': (r) => r.status === 200,
		'developCode 200': (r) => {
			try { return JSON.parse(r.body).developCode === 200; } catch (_) { return false; }
		},
	});
	failRate.add(!ok);
	if (ok) {
		const grids = JSON.parse(res.body).data.grids || [];
		cells.add(grids.length);
		emptyRate.add(grids.length === 0);
	}
}

export function handleSummary(data) {
	const m = data.metrics;
	const v = (name, stat) => (m[name] && m[name].values[stat] !== undefined ? m[name].values[stat] : null);
	const L = 'viewport_latency{phase:measure}';
	const reqsAll = m.http_reqs ? m.http_reqs.values.count : 0;
	const reqs = m['http_reqs{phase:measure}'] ? m['http_reqs{phase:measure}'].values.count : 0;
	const dropped = m['dropped_iterations{scenario:measure}'] ? m['dropped_iterations{scenario:measure}'].values.count : 0;
	const out = {
		label: LABEL, rate: RATE, warmup: WARMUP, duration: DURATION, reqs_all: reqsAll, reqs,
		achieved_rps: m['http_reqs{phase:measure}'] ? m['http_reqs{phase:measure}'].values.rate : null,
		failed_pct: m['viewport_failed{phase:measure}'] ? m['viewport_failed{phase:measure}'].values.rate * 100 : null,
		dropped,
		empty_pct: m.viewport_empty_rate ? m.viewport_empty_rate.values.rate * 100 : null,
		cells_per_req: reqsAll ? (m.viewport_cells_returned ? m.viewport_cells_returned.values.count / reqsAll : 0) : null,
		med_ms: v(L, 'med'), p95_ms: v(L, 'p(95)'), p99_ms: v(L, 'p(99)'), max_ms: v(L, 'max'), avg_ms: v(L, 'avg'),
		all_med_ms: v('viewport_latency', 'med'), all_p95_ms: v('viewport_latency', 'p(95)'), all_p99_ms: v('viewport_latency', 'p(99)'),
	};
	const f = (x, d = 2) => (x === null ? '—' : Number(x).toFixed(d));
	const report = [
		'',
		'══════════════════════════════════════════════════════',
		`  뷰포트 조회 부하 · ${LABEL} · ${RATE} rps · 워밍업 ${WARMUP} + 측정 ${DURATION} (아래는 측정 위상)`,
		'══════════════════════════════════════════════════════',
		`  지연 med ${f(out.med_ms)} ms · p95 ${f(out.p95_ms)} ms · p99 ${f(out.p99_ms)} ms · max ${f(out.max_ms)} ms`,
		`  요청 ${reqs}건 (워밍업 포함 ${reqsAll}) · 달성 ${f(out.achieved_rps, 1)} rps · 실패 ${f(out.failed_pct)}% · 버려진 iteration ${dropped}`,
		`  응답당 격자 ${f(out.cells_per_req, 1)}개 · 빈 응답 ${f(out.empty_pct, 1)}%`,
		'══════════════════════════════════════════════════════',
		'',
	].join('\n');
	const files = { stdout: report };
	if (__ENV.OUT) files[__ENV.OUT] = JSON.stringify(out, null, 2) + '\n';
	return files;
}
