/*
 * MSG-596 · 도감 요약 조회 부하 벤치마크 (k6)
 * ---------------------------------------------------------------------------
 * GET /api/collections/summary 를 한 사용자 토큰으로 고정 도착률로 때린다.
 * 사용자별 지표 6개 중 visitedRegionCount(영상 ⨝ 격자 DISTINCT)가 영상 수에 비례하므로,
 * 더미 규모가 다른 사용자(OID)를 바꿔 가며 같은 시나리오를 반복한다.
 *
 * 준비:
 *   1) 더미를 유지 모드로 적재: docker exec -i fillmap-postgres psql -U user -d fillmap -v keep=1 < scripts/bench-msg596.sql
 *   2) 앱을 local 프로파일로 기동 (개발용 /api/auth/dev/social-login 이 local·dev 에서만 열린다)
 *   3) 끝나면 scripts/bench-msg596-cleanup.sql 로 더미를 지운다
 *
 * 실행:
 *   OID=b596-3 k6 run -e SCENARIO=smoke load-test/k6/collection-summary-benchmark.js
 *   OID=b596-3 RATE=50 DURATION=1m k6 run -e SCENARIO=load load-test/k6/collection-summary-benchmark.js
 *   (env: BASE_URL, OID, SCENARIO, RATE, DURATION, LABEL. OID = b596-0(1천) b596-1(1만) b596-2(10만) b596-3(격자 40만))
 *
 * 대용량 사다리(개선 전후 같은 명령으로 상한 비교) — 보통 사용자 100명(b596-4~103, 384격자)을 돌려가며 100→3,000 rps 로 올린다.
 *   USERS=100 LABEL=before k6 run -e SCENARIO=ramp --summary-export=ramp-before.json load-test/k6/collection-summary-benchmark.js
 *   (env: USERS = 로그인해 둘 사용자 수, STAGES = "rps:초,rps:초,..." 기본 100:40,300:40,600:40,1000:40,1500:40,2000:40,3000:40)
 *   무너지는 계단에서 dropped_iterations 가 서고 VU 가 maxVUs(2000)까지 불어난다 — 그 계단이 상한이다.
 *   HEAVY_OID=b596-3 HEAVY_PCT=5 를 붙이면 요청의 5% 가 격자 40만 사용자로 나간다(헤비 사용자 혼합).
 *
 * 토큰은 setup() 이 개발용 소셜 로그인 모의 엔드포인트로 받는다 — 더미 사용자가 KAKAO+oid 로 심겨 있어
 * 같은 oid 로 부르면 그 사용자로 로그인된다(find-or-create). TOKEN 을 직접 주면 그걸 쓴다.
 * ---------------------------------------------------------------------------
 */

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const OID = __ENV.OID || 'b596-3';
const SCENARIO = (__ENV.SCENARIO || 'smoke').toLowerCase();
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '1m';
const LABEL = __ENV.LABEL || '';
const USERS = Number(__ENV.USERS || 1);
// 헤비 사용자 섞기 — HEAVY_OID(예: b596-3, 격자 40만) 요청을 전체의 HEAVY_PCT% 만큼 끼운다. 보통 사용자만으론 이 API 가
// 2,000 rps 를 받아(09-16 실측, p50 1.5ms) 병목이 안 보인다. 격자가 많은 사용자에서 플래너가 grids Seq Scan 으로 바뀌는 게
// 실제 비용이므로, 소수의 헤비 사용자가 풀을 잡아 전체를 막는 그림을 이 두 값으로 만든다.
const HEAVY_OID = __ENV.HEAVY_OID || '';
const HEAVY_PCT = Number(__ENV.HEAVY_PCT || 0);
const STAGES = (__ENV.STAGES || '100:40,300:40,600:40,1000:40,1500:40,2000:40,3000:40')
	.split(',').map((s) => { const [r, d] = s.split(':'); return { target: Number(r), duration: `${d}s` }; });

const latency = new Trend('summary_latency', true);
const failRate = new Rate('summary_failed');

function scenarioSpec() {
	switch (SCENARIO) {
		case 'smoke':
			return { executor: 'per-vu-iterations', vus: 1, iterations: 10, maxDuration: '30s' };
		// 고정 도착률 — VU 가 밀려도 요청 발생 시각이 흔들리지 않아 지연이 부하 그 자체에서 왔는지 본다.
		case 'load':
			return {
				executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
				preAllocatedVUs: Math.max(20, Math.ceil(RATE / 2)), maxVUs: 600,
			};
		// 계단식 도착률 — 상한 탐색. 각 계단은 목표에 즉시 붙지 않고 duration 동안 선형으로 오른다.
		case 'ramp':
			return {
				executor: 'ramping-arrival-rate', startRate: STAGES[0].target, timeUnit: '1s', stages: STAGES,
				preAllocatedVUs: 200, maxVUs: 2000,
			};
		default:
			throw new Error(`알 수 없는 SCENARIO=${SCENARIO} (smoke|load|ramp)`);
	}
}

export const options = {
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
	scenarios: { summary: { ...scenarioSpec(), exec: 'hitSummary' } },
	thresholds: {
		// 합격선은 스펙(docs/spec/MSG-596.md)과 같은 "실패 0·dropped 0" 이다. 여유를 두면 실제로는 못 버틴
		// 계단을 통과로 찍는다(Codex P2). ramp 회차는 붕괴 계단을 찾는 게 목적이라 실패 종료가 정상이고
		// 결과는 --summary-export 와 계단표(scripts/bench-msg596-stages.py)로 읽는다.
		summary_failed: ['rate==0'],
		// 목표 도착률을 못 맞추면 k6 가 iteration 을 버린다 — 목표 RPS 미달인 실행을 통과로 치지 않는다.
		dropped_iterations: ['count==0'],
	},
};

function login(oid) {
	const res = http.post(`${BASE_URL}/api/auth/dev/social-login`,
		JSON.stringify({ provider: 'KAKAO', oid }),
		{ headers: { 'Content-Type': 'application/json' } });
	if (res.status !== 200) {
		throw new Error(`dev social-login 실패 oid=${oid} status=${res.status} body=${res.body}`);
	}
	const token = JSON.parse(res.body).data.accessToken;
	if (!token) {
		throw new Error('accessToken 없음 — 응답 형식 확인');
	}
	return token;
}

// USERS>1 이면 보통 사용자 b596-4.. 를 USERS 명 로그인해 iteration 마다 돌려 쓴다(한 사용자만 때리면 같은 행만 캐시에 남아 실측이 후해진다).
// USERS>1 이 TOKEN 보다 먼저다 — k6 __ENV 는 셸의 export 변수도 읽어서, 다른 실험에서 export 해 둔 만료 토큰이
// 조용히 끼어들면 전 요청이 401 로 떨어진다(09-16 실측: 28만 건 전부 401). TOKEN 은 단일 사용자 모드에서만 쓴다.
export function setup() {
	const heavy = HEAVY_OID ? login(HEAVY_OID) : null;
	if (USERS > 1) {
		return { heavy, tokens: Array.from({ length: USERS }, (_, i) => login(`b596-${4 + i}`)) };
	}
	if (__ENV.TOKEN) {
		return { tokens: [__ENV.TOKEN] };
	}
	return { tokens: [login(OID)] };
}

export function hitSummary(data) {
	const isHeavy = data.heavy && Math.random() * 100 < HEAVY_PCT;
	const token = isHeavy ? data.heavy : data.tokens[(__VU * 7919 + __ITER) % data.tokens.length];
	const res = http.get(`${BASE_URL}/api/collections/summary`, {
		headers: { Authorization: `Bearer ${token}` },
		tags: { name: 'summary', label: LABEL, oid: OID, heavy: isHeavy ? 'y' : 'n' },
	});
	latency.add(res.timings.duration, { heavy: isHeavy ? 'y' : 'n' });
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
}
