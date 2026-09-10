/*
 * MSG-183/184 · 핫구역 조회 부하 벤치마크 (k6)
 * ---------------------------------------------------------------------------
 * GET /api/hotzones 를 시나리오별로 때린다. 측정하려는 것은 셋이다.
 *
 *   1) 캐시 만료 주기의 지연 — HotZoneServiceImpl 은 hotzone:top 을 30s TTL 로 캐시하고,
 *      만료되면 8버킷 ZUNIONSTORE 로 다시 채운다.
 *      주의: 이건 캐시 스탬피드(여러 요청의 중복 재계산)가 아니다. 재계산은
 *      EXISTS+ZUNIONSTORE+EXPIRE 를 한 덩어리로 묶은 Lua 라 Redis 가 원자적으로 실행하고,
 *      뒤이은 요청은 EXISTS=1 을 보고 건너뛴다. 실측으로도 90초/200RPS(18,001 요청)에서
 *      ZUNIONSTORE 는 3회(=만료 횟수)만 실행됐다.
 *      실제로 보이는 건 재계산 1회가 Redis 를 수십 ms 붙잡는 동안 뒤에 줄선 요청이
 *      밀리는 head-of-line 지연이다. → expiry 시나리오로 고정 RPS 를 유지하며 시간축에서 본다.
 *
 *   2) 한계 RPS — 어디까지 버티는지. cap 시나리오(ramping-arrival-rate, 최고 도착률 60s 유지).
 *
 *   3) 규모 감도 — seed-hotzone.sh 로 격자 수를 바꿔가며 같은 시나리오를 반복한다.
 *      상위 K(50) 캡 때문에 응답 크기는 규모와 무관하다 — 커지는 건 ZUNIONSTORE 비용뿐.
 *
 * 실행:
 *   TOKEN="<jwt>" k6 run -e SCENARIO=smoke load-test/k6/hotzone-benchmark.js
 *   TOKEN=... k6 run -e SCENARIO=expiry --out json=result.json load-test/k6/hotzone-benchmark.js
 *   TOKEN=... k6 run -e SCENARIO=cap load-test/k6/hotzone-benchmark.js
 *   (env: BASE_URL, TOKEN, SCENARIO, RATE, DURATION, LABEL)
 *
 * 시간축 분석은 analyze-expiry.py 가 --out json 결과를 받아서 한다.
 * ---------------------------------------------------------------------------
 */

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const SCENARIO = (__ENV.SCENARIO || 'smoke').toLowerCase();
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '2m';
const LABEL = __ENV.LABEL || '';

// seed-hotzone.sh 가 격자를 뿌린 범위 — 뷰포트가 이 밖이면 필터에서 다 걸러져 빈 응답이 된다.
const SEOUL = { minLat: 37.45, maxLat: 37.65, minLng: 126.85, maxLng: 127.15 };
const SPAN = { lat: [0.02, 0.06], lng: [0.025, 0.075] };

const latency = new Trend('hotzone_latency', true);
const failRate = new Rate('hotzone_failed');
const zonesReturned = new Counter('hotzone_zones_returned');
// 빈 응답은 Rate 로 잡는다 — Counter 면 "몇 건"만 알 뿐 비율에 threshold 를 걸 수 없어서,
// 뷰포트가 시드 범위를 벗어나 전부 빈 응답이 와도 green 으로 끝난다(측정이 무의미한데 통과).
const emptyRate = new Rate('hotzone_empty_rate');

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

function scenarioSpec() {
	switch (SCENARIO) {
		case 'smoke':
			return { executor: 'per-vu-iterations', vus: 1, iterations: 10, maxDuration: '30s' };

		// 만료 주기 관측용. 고정 도착률이라 VU 가 밀려도 요청 발생 시각이 흔들리지 않는다.
		// 지연이 캐시 만료에서 왔는지 부하 자체에서 왔는지 섞이지 않게 하는 조건이다.
		case 'expiry':
			return {
				executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
				preAllocatedVUs: Math.max(50, Math.ceil(RATE / 4)), maxVUs: 600,
			};

		// 전 구간을 한 번에 훑어 꺾이는 지점을 찾는다.
		// 마지막에 최고 도착률을 60초 유지한다 — 상승만 하고 바로 내려오면 "N RPS 를 버텼다"고
		// 말할 근거가 없다. 그 구간에 머무른 시간이 있어야 지표가 정상 상태를 반영한다.
		case 'cap':
			return {
				executor: 'ramping-arrival-rate', startRate: 100, timeUnit: '1s',
				preAllocatedVUs: 100, maxVUs: 1500, stages: [
					{ target: 250, duration: '30s' }, { target: 500, duration: '30s' },
					{ target: 1000, duration: '30s' }, { target: 2000, duration: '30s' },
					{ target: 2000, duration: '60s' },
					{ target: 0, duration: '10s' },
				],
			};

		// 규모 감도용 — 짧고 일정하게, 격자 수만 바꿔 반복 비교한다.
		case 'scale':
			return {
				executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '45s',
				preAllocatedVUs: Math.max(50, Math.ceil(RATE / 4)), maxVUs: 600,
			};

		default:
			throw new Error(`알 수 없는 SCENARIO=${SCENARIO} (smoke|expiry|cap|scale)`);
	}
}

export const options = {
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
	scenarios: { hotzone: { ...scenarioSpec(), exec: 'hitHotZones' } },
	thresholds: {
		// 캐시가 잘 듣는 API 라 평시는 한 자릿수 ms 를 기대한다. 재계산이 Redis 를 막는 순간
		// 그 뒤에 줄선 요청들 때문에 p99 가 먼저 깨진다.
		hotzone_latency: ['p(95)<50', 'p(99)<200'],
		hotzone_failed: ['rate<0.01'],
		// 대부분이 빈 응답이면 지연 수치가 "핫구역을 실제로 담아 보낸 응답"을 재지 못한다.
		// 시드 범위와 뷰포트가 어긋난 실행을 green 으로 넘기지 않으려는 가드다.
		hotzone_empty_rate: ['rate<0.9'],
		// 목표 도착률을 못 맞추면 k6 가 iteration 을 버린다. 이걸 안 보면 "실패 0%" 인데
		// 실제로는 목표 RPS 에 도달조차 못 한 실행을 성공으로 읽게 된다.
		dropped_iterations: ['count<1000'],
	},
};

export function hitHotZones() {
	const vp = randomViewport();
	const url = `${BASE_URL}/api/hotzones?swLat=${vp.swLat.toFixed(6)}&swLng=${vp.swLng.toFixed(6)}`
		+ `&neLat=${vp.neLat.toFixed(6)}&neLng=${vp.neLng.toFixed(6)}`;

	const res = http.get(url, {
		headers: TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {},
		tags: { name: 'hotzones' },
	});

	latency.add(res.timings.duration);

	const ok = check(res, {
		'status 200': (r) => r.status === 200,
		'developCode 200': (r) => {
			try {
				return JSON.parse(r.body).developCode === 200;
			} catch (_) {
				return false;
			}
		},
	});

	failRate.add(!ok);
	if (ok) {
		const zones = JSON.parse(res.body).data.hotZones || [];
		zonesReturned.add(zones.length);
		emptyRate.add(zones.length === 0);
	}
}

export function handleSummary(data) {
	const m = data.metrics;
	const v = (name, stat) => {
		const metric = m[name];
		return metric && metric.values[stat] !== undefined ? metric.values[stat].toFixed(2) : '—';
	};
	const count = (name) => (m[name] ? m[name].values.count : 0);

	const reqs = count('http_reqs');
	const zones = count('hotzone_zones_returned');
	const dropped = count('dropped_iterations');
	const emptyPct = m.hotzone_empty_rate ? (m.hotzone_empty_rate.values.rate * 100).toFixed(1) : '?';

	// 달성 RPS 는 실행 전체 평균이라 ramping 시나리오에서는 오해를 부른다
	// (상승 구간이 평균을 끌어내려 "722 RPS 에서 꺾였다"로 잘못 읽힌다). 그래서 명시해 둔다.
	const rpsNote = SCENARIO === 'cap' ? '  (ramping 전체 평균 — 최고 도달 구간은 시간축으로 확인할 것)' : '';

	const report = [
		'',
		'══════════════════════════════════════════════════════',
		`  핫구역 조회 부하 · 시나리오 ${SCENARIO}${LABEL ? ` · ${LABEL}` : ''}`,
		'══════════════════════════════════════════════════════',
		`  지연 avg      ${v('hotzone_latency', 'avg').padStart(9)} ms`,
		`  지연 med      ${v('hotzone_latency', 'med').padStart(9)} ms`,
		`  지연 p95      ${v('hotzone_latency', 'p(95)').padStart(9)} ms`,
		`  지연 p99      ${v('hotzone_latency', 'p(99)').padStart(9)} ms`,
		`  지연 max      ${v('hotzone_latency', 'max').padStart(9)} ms`,
		'──────────────────────────────────────────────────────',
		`  요청 ${reqs}건 · 실패율 ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) : '?'}%`,
		`  달성 RPS ${m.http_reqs ? m.http_reqs.values.rate.toFixed(1) : '?'}${rpsNote}`,
		`  버려진 iteration ${dropped}건 ${dropped > 0 ? '(목표 도착률 미달 구간이 있다)' : ''}`,
		`  반환 핫구역 평균 ${reqs ? (zones / reqs).toFixed(1) : '?'}개 · 빈 응답 ${emptyPct}%`,
		'══════════════════════════════════════════════════════',
		'',
	].join('\n');

	return { stdout: report };
}
