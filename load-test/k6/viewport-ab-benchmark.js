/*
 * MSG-73 · 격자 색칠 viewport 조회 — 쿼리 전략 A/B 부하 벤치마크 (k6)
 * ---------------------------------------------------------------------------
 * 같은 엔드포인트(GET /api/grids)를 두 전략으로 부하 비교한다.
 *   A = 정수 범위 스캔  : WHERE grid_y BETWEEN .. AND grid_x BETWEEN ..  (btree uq_grids_yx)
 *   B = GIST 공간 쿼리  : ST_Intersects(bbox_geom, ST_MakeEnvelope(...))  (GiST idx_grids_bbox)
 *
 * 두 시나리오를 "순차"로 돌려 서로 자원 간섭 없이 A→B를 측정하고,
 * 전략별 지연(p95/p99)·처리량·에러율을 커스텀 메트릭으로 분리 수집한 뒤
 * handleSummary에서 A vs B 비교표를 출력한다.
 *
 * 실행:
 *   # 1) 앱 기동(localhost:8080) + PostGIS(5432)에 대량 시드(grid-dev의 시드 헬퍼) 선행
 *   # 2) 로그인 JWT를 환경변수로 주입
 *   TOKEN="<bearer-jwt>" k6 run load-test/k6/viewport-ab-benchmark.js
 *
 *   # 옵션: BASE_URL, VUS(최대 가상 유저), DURATION(각 단계 지속)
 *   BASE_URL=http://localhost:8080 VUS=100 TOKEN=... k6 run load-test/k6/viewport-ab-benchmark.js
 * ---------------------------------------------------------------------------
 */

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// ── 설정 ────────────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const MAX_VUS = Number(__ENV.VUS || 100);
const STAGE = __ENV.DURATION || '30s';

// 서울 대략 경계 — 실사용 뷰포트를 이 안에서 무작위로 만든다.
const SEOUL = { minLat: 37.42, maxLat: 37.70, minLng: 126.76, maxLng: 127.18 };
// 지도 한 화면(줌)에 해당하는 뷰포트 폭(도). 대략 2~6km.
const SPAN = { lat: [0.02, 0.06], lng: [0.025, 0.075] };

// ── 전략별 커스텀 메트릭 ────────────────────────────────────────────────────
const latency = new Trend('viewport_latency', true); // strategy 태그로 분리
const failRate = new Rate('viewport_failed');
const emptyRate = new Rate('viewport_empty'); // 점령 격자 0개 응답 비율(시드 편중 감지용)
const cells = new Counter('viewport_cells_returned');

function rnd(min, max) {
	return Math.random() * (max - min) + min;
}

// 서울 안에서 임의의 뷰포트 bbox 생성 (남서·북동)
function randomViewport() {
	const latSpan = rnd(SPAN.lat[0], SPAN.lat[1]);
	const lngSpan = rnd(SPAN.lng[0], SPAN.lng[1]);
	const swLat = rnd(SEOUL.minLat, SEOUL.maxLat - latSpan);
	const swLng = rnd(SEOUL.minLng, SEOUL.maxLng - lngSpan);
	return { swLat, swLng, neLat: swLat + latSpan, neLng: swLng + lngSpan };
}

// ── 시나리오: A → B 순차, 각각 ramp-up→sustain→ramp-down ──────────────────────
const rampStages = [
	{ target: Math.ceil(MAX_VUS / 2), duration: '15s' }, // 워밍업
	{ target: MAX_VUS, duration: STAGE },                 // 피크 부하
	{ target: 0, duration: '10s' },                       // 쿨다운
];

export const options = {
	scenarios: {
		strategy_a: {
			executor: 'ramping-vus',
			exec: 'hitViewport',
			env: { STRATEGY: 'A' },
			tags: { strategy: 'A' },
			startVUs: 0,
			stages: rampStages,
		},
		strategy_b: {
			executor: 'ramping-vus',
			exec: 'hitViewport',
			env: { STRATEGY: 'B' },
			tags: { strategy: 'B' },
			startVUs: 0,
			stages: rampStages,
			startTime: '60s', // A 시나리오(약 55s)가 끝난 뒤 시작 → 간섭 제거
		},
	},
	thresholds: {
		// 전략별 개별 임계치 — 포트폴리오에서 "SLO 기반 판정"으로 보이게
		'viewport_latency{strategy:A}': ['p(95)<150', 'p(99)<300'],
		'viewport_latency{strategy:B}': ['p(95)<150', 'p(99)<300'],
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
		tags: { strategy, name: 'viewport' }, // URL이 매번 달라도 하나의 지표로 집계
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
		const arr = JSON.parse(res.body).body || [];
		cells.add(arr.length, tag);
		emptyRate.add(arr.length === 0, tag);
	}
}

// ── 요약: A vs B 비교표 ──────────────────────────────────────────────────────
export function handleSummary(data) {
	const m = data.metrics;
	const pick = (name, stat, strat) => {
		// k6는 서브메트릭을 "metric{strategy:A}" 키로 노출
		const sub = m[`${name}{strategy:${strat}}`];
		const v = sub && sub.values ? sub.values[stat] : undefined;
		return v === undefined ? '  —  ' : v.toFixed(2);
	};
	const row = (label, name, stat) =>
		`  ${label.padEnd(22)} A=${pick(name, stat, 'A').padStart(8)}   B=${pick(name, stat, 'B').padStart(8)}`;

	const report = [
		'',
		'════════════════════════════════════════════════════════',
		'  MSG-73 viewport 쿼리 전략 A(정수범위) vs B(GIST) 비교',
		'════════════════════════════════════════════════════════',
		row('지연 p95 (ms)', 'viewport_latency', 'p(95)'),
		row('지연 p99 (ms)', 'viewport_latency', 'p(99)'),
		row('지연 avg (ms)', 'viewport_latency', 'avg'),
		row('지연 max (ms)', 'viewport_latency', 'max'),
		row('실패율', 'viewport_failed', 'rate'),
		row('빈 응답율', 'viewport_empty', 'rate'),
		'════════════════════════════════════════════════════════',
		'  판정 기준: p95/p99 지연 · 실패율 · (별도)EXPLAIN 인덱스 사용',
		'  → 낮은 전략을 기본 경로로 채택, docs/MSG-73.md 작업 로그에 기록',
		'════════════════════════════════════════════════════════',
		'',
	].join('\n');

	return {
		stdout: report + '\n' + textSummary(data, { indent: '  ', enableColors: true }),
		'load-test/k6/summary.json': JSON.stringify(data, null, 2),
	};
}
