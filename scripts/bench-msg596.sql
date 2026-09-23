-- ============================================================================
-- MSG-596 도감 요약 — 방문 행정동 수(visitedRegionCount) 규모별 실측 하네스
--
-- 재현 방법 (로컬 공유 PostGIS 컨테이너 fillmap-postgres 기동 상태에서):
--   docker exec -i fillmap-postgres psql -U user -d fillmap < scripts/bench-msg596.sql
--   (반복 수: 아래 :iters. 배경 사용자 규모: :bg_users × :bg_videos_per_user)
--
-- 두 모드:
--   - 롤백 모드(기본, keep=0): [전체가 단일 트랜잭션] 규모별 더미 적재 → ANALYZE → EXPLAIN(ANALYZE,BUFFERS)
--     → p50/p95 하네스 → 등가성 검사 → ROLLBACK 전량 원복 → 통계 원복(ANALYZE) → 원상복구 검증.
--   - 유지 모드(keep=1): 같은 측정을 한 뒤 COMMIT 으로 더미를 남긴다 — k6 로 실제 API 를 때리는 단계용
--     (load-test/k6/collection-summary-benchmark.js). 끝나면 반드시 scripts/bench-msg596-cleanup.sql 로 지운다.
--     통합 테스트는 이 DB 를 공유하므로 더미가 남아 있는 동안 돌리면 깨진다.
--       docker exec -i fillmap-postgres psql -U user -d fillmap -v keep=1 < scripts/bench-msg596.sql
--   벤치 사용자는 KAKAO + oid 'b596-{n}' 으로 심는다 — 개발용 POST /api/auth/dev/social-login 이 같은
--   (provider, oid) 를 찾아 그 사용자의 JWT 를 내주므로 비밀번호 없이 로그인된다.
--
-- 더미 형상:
--   - 규모 사용자 4명: 영상 1천 / 1만 / 10만 건(격자는 영상 5건당 1개) + 격자 40만 개 사용자(격자당 영상
--     :big_videos_per_grid 건, 기본 2 → 영상 80만). 사용자별 전용 격자 범위.
--   - 배경 사용자 :bg_users 명 × :bg_videos_per_user 건 — 같은 테이블에 남의 영상이 있어야
--     user_id 인덱스 선택도가 현실과 비슷해진다(비어 있는 테이블에선 어떤 계획이든 빠르다).
--   - 격자 region_code 는 실제 regions 코드 3,558개 전부를 순환(빌린 값, FK 충족). 좌표는 서해 공해상.
--   - 영상 status: 25건당 1건 DELETED, 40건당 1건 BLINDED, 나머지 ACTIVE — 필터가 의미를 갖게.
--   - user_grids 는 "삭제 안 된 영상이 1건 이상인 (user, grid)" 로 파생(점령 롤백 규칙과 동일 형상).
--
-- 공유 DB 가드레일:
--   - regions 는 읽기만. 벤치 행은 격리 식별자(b596_*, B596xxxx, @bench.local)로만 생성.
--   - 트랜잭션 안에서 ANALYZE 한다 — pg_statistic 은 ROLLBACK 으로 돌아가지만 pg_class.reltuples 는
--     inplace 갱신이라 남는다. 그래서 ROLLBACK 직후 같은 테이블을 다시 ANALYZE 해 실제 값으로 되돌린다.
--   - 알려진 잔류: users/videos id 시퀀스 전진(surrogate key 갭, 무해).
--
-- ponytail: 격자를 사용자 간 공유하지 않는다 — 측정 대상이 "한 사용자의 집계"라 남의 격자 겹침은
--   계획에 영향 없고, 공유하면 적재 SQL 이 두 배 길어진다. 격자 겹침이 필요한 실험이면 그때 붙인다.
-- ============================================================================
\set ON_ERROR_STOP on
\timing off
\set iters 30
\set bg_users 100
\set bg_videos_per_user 2000
\set big_grids 400000
\set big_videos_per_grid 2
\if :{?keep}
\else
\set keep 0
\endif

-- ============================================================================
-- 섹션 1 — 더미 적재. 이 BEGIN 이 스크립트 전체를 연다.
-- ============================================================================
BEGIN;

CREATE TEMP TABLE b596_codes AS
SELECT region_code, (row_number() OVER (ORDER BY region_code) - 1) AS rn
FROM regions ORDER BY region_code;
SELECT count(*) AS n_codes FROM b596_codes \gset

-- n=0,1,2 는 규모 사용자(1천·1만·10만, 격자는 영상 5건당 1개), n=3 은 격자 40만 사용자, n>=4 는 배경 사용자.
CREATE TEMP TABLE b596_plan AS
SELECT n,
       CASE n WHEN 0 THEN 1000 WHEN 1 THEN 10000 WHEN 2 THEN 100000
              WHEN 3 THEN :big_grids * :big_videos_per_grid ELSE :bg_videos_per_user END AS n_videos,
       CASE n WHEN 0 THEN 200 WHEN 1 THEN 2000 WHEN 2 THEN 20000
              WHEN 3 THEN :big_grids ELSE :bg_videos_per_user / 5 END AS n_grids
FROM generate_series(0, :bg_users + 3) n;
ALTER TABLE b596_plan ADD COLUMN grid_offset bigint, ADD COLUMN user_id bigint;
UPDATE b596_plan p SET grid_offset = s.off
FROM (SELECT n, COALESCE(sum(n_grids) OVER (ORDER BY n ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS off
      FROM b596_plan) s
WHERE p.n = s.n;

INSERT INTO users (provider, oid, email, nickname, grid_color, role, email_verified, friend_code)
SELECT 'KAKAO', 'b596-' || n, 'b596-' || n || '@bench.local', 'b596-' || n, 'BLUE', 'USER', TRUE,
       'B596' || lpad(n::text, 4, '0')
FROM b596_plan;
UPDATE b596_plan p SET user_id = u.id FROM users u WHERE u.email = 'b596-' || p.n || '@bench.local';

-- 격자: 전역 번호 g 로 유일 (grid_y, grid_x). 서해 공해상 좌표.
INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom, region_code)
SELECT 'b596_' || g,
       900000 + g / 1000, 900000 + g % 1000,
       ST_SetSRID(ST_MakePoint(124.0 + (g % 1000) * 0.001, 34.0 + (g / 1000) * 0.001), 4326)::geography,
       ST_SetSRID(ST_MakeEnvelope(124.0 + (g % 1000) * 0.001, 34.0 + (g / 1000) * 0.001,
                                  124.0 + (g % 1000) * 0.001 + 0.0009, 34.0 + (g / 1000) * 0.001 + 0.0009,
                                  4326), 4326)::geography,
       (SELECT region_code FROM b596_codes WHERE rn = g % :n_codes)
FROM generate_series(0, (SELECT sum(n_grids) FROM b596_plan) - 1) g;

-- 영상: 사용자별 전용 격자 범위를 순환. created_at 을 분 단위로 벌려 인덱스 순서가 섞이게.
-- visibility 는 PRIVATE — 전역 노출 경로(격자 대표 영상·전역 목록·탐색 집계)가 PUBLIC 한정이라 더미가 남에게
-- 안 보인다. 측정 대상(visitedRegionCount)은 visibility 를 안 보므로 수치엔 영향 없다. 운영 DB 재현용.
INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, created_at, status, processing_status, visibility)
SELECT p.user_id,
       'b596_' || (p.grid_offset + (i % p.n_grids)),
       ST_SetSRID(ST_MakePoint(124.5, 35.0), 4326)::geography,
       10,
       now() - (i || ' minutes')::interval,
       now() - (i || ' minutes')::interval,
       CASE WHEN i % 25 = 0 THEN 'DELETED' WHEN i % 40 = 0 THEN 'BLINDED' ELSE 'ACTIVE' END,
       'READY', 'PRIVATE'
FROM b596_plan p, LATERAL generate_series(1, p.n_videos) i;

-- 점령 롤백 규칙과 같은 형상: 삭제 안 된 영상이 1건 이상인 (user, grid) 만 존재, video_count 는 그 수.
INSERT INTO user_grids (user_id, grid_id, first_collected_at, last_uploaded_at, video_count)
SELECT v.user_id, v.grid_id, min(v.created_at), max(v.created_at), count(*)
FROM videos v JOIN b596_plan p ON p.user_id = v.user_id
WHERE v.status <> 'DELETED'
GROUP BY v.user_id, v.grid_id;

ANALYZE users; ANALYZE grids; ANALYZE videos; ANALYZE user_grids;

\echo '### 적재 형상'
SELECT p.n, p.user_id, p.n_videos, p.n_grids,
       (SELECT count(*) FROM user_grids ug WHERE ug.user_id = p.user_id) AS user_grids_rows
FROM b596_plan p WHERE n <= 3 ORDER BY n;
SELECT count(*) AS videos_total, count(DISTINCT user_id) AS users_total FROM videos;

SELECT user_id AS uid_1k   FROM b596_plan WHERE n = 0 \gset
SELECT user_id AS uid_10k  FROM b596_plan WHERE n = 1 \gset
SELECT user_id AS uid_100k FROM b596_plan WHERE n = 2 \gset
SELECT user_id AS uid_400kg FROM b596_plan WHERE n = 3 \gset

-- ============================================================================
-- 섹션 2 — EXPLAIN (ANALYZE, BUFFERS): 현재 visitedRegionCount 서브쿼리 (UserGridRepository 원문 그대로)
-- ============================================================================
\echo '### (현재) visitedRegionCount — 영상 1천'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(DISTINCT g.region_code)::int
FROM videos v JOIN grids g ON g.grid_id = v.grid_id
WHERE v.user_id = :uid_1k AND v.status <> 'DELETED';

\echo '### (현재) visitedRegionCount — 영상 1만'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(DISTINCT g.region_code)::int
FROM videos v JOIN grids g ON g.grid_id = v.grid_id
WHERE v.user_id = :uid_10k AND v.status <> 'DELETED';

\echo '### (현재) visitedRegionCount — 영상 10만'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(DISTINCT g.region_code)::int
FROM videos v JOIN grids g ON g.grid_id = v.grid_id
WHERE v.user_id = :uid_100k AND v.status <> 'DELETED';

\echo '### (현재) visitedRegionCount — 격자 40만 (영상 80만)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(DISTINCT g.region_code)::int
FROM videos v JOIN grids g ON g.grid_id = v.grid_id
WHERE v.user_id = :uid_400kg AND v.status <> 'DELETED';

\echo '### (현재) 도감 요약 전체 문장 — 격자 40만 (지표 6개 중 어디가 무거운지)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT
	(SELECT COUNT(*)::int FROM user_grids WHERE user_id = :uid_400kg) AS "totalGridCount",
	(SELECT COALESCE(SUM(video_count), 0) FROM user_grids WHERE user_id = :uid_400kg) AS "totalVideoCount",
	(SELECT COUNT(DISTINCT g.region_code)::int
		FROM videos v JOIN grids g ON g.grid_id = v.grid_id
		WHERE v.user_id = :uid_400kg AND v.status <> 'DELETED') AS "visitedRegionCount",
	COALESCE((SELECT CASE
			WHEN s.last_recorded_date >= (statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date - 1
				THEN s.current_count ELSE 0 END
		FROM streaks s WHERE s.user_id = :uid_400kg), 0) AS "currentStreak",
	COALESCE((SELECT s.max_count FROM streaks s WHERE s.user_id = :uid_400kg), 0) AS "maxStreak",
	(SELECT COUNT(*)::int FROM user_badges WHERE user_id = :uid_400kg) AS "badgeCount";

-- ============================================================================
-- 섹션 3 — p50/p95 하네스. plpgsql 정적 SQL → 5회 뒤 generic plan 캐시(앱의 prepared statement 조건).
--   bench_current  : 현재 서브쿼리.
--   bench_candidate: 후보 쿼리 자리 — 지금은 현재와 동일. 개선안을 여기에 넣고 다시 돌린다.
--   두 함수의 결과값이 규모 4개 모두 같아야 한다(섹션 3 끝 등가성 검사).
-- ============================================================================
CREATE OR REPLACE FUNCTION bench_current(uid bigint) RETURNS int AS $$
	SELECT COUNT(DISTINCT g.region_code)::int
	FROM videos v JOIN grids g ON g.grid_id = v.grid_id
	WHERE v.user_id = uid AND v.status <> 'DELETED';
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION bench_candidate(uid bigint) RETURNS int AS $$
	-- ▼ 후보 쿼리를 여기에. (지금은 현재 쿼리 복사본)
	SELECT COUNT(DISTINCT g.region_code)::int
	FROM videos v JOIN grids g ON g.grid_id = v.grid_id
	WHERE v.user_id = uid AND v.status <> 'DELETED';
	-- ▲
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION bench_run(fn text, uid bigint, iters int)
RETURNS TABLE(samples bigint, p50_ms numeric, p95_ms numeric, p99_ms numeric, avg_ms numeric, max_ms numeric)
AS $$
DECLARE t0 timestamptz; d int; k int;
BEGIN
	DROP TABLE IF EXISTS _t; CREATE TEMP TABLE _t(ms float8);
	FOR k IN 1..5 LOOP EXECUTE format('SELECT %I($1)', fn) INTO d USING uid; END LOOP;   -- warmup
	FOR k IN 1..iters LOOP
		t0 := clock_timestamp();
		EXECUTE format('SELECT %I($1)', fn) INTO d USING uid;
		INSERT INTO _t VALUES (extract(epoch FROM clock_timestamp() - t0) * 1000);
	END LOOP;
	RETURN QUERY SELECT count(*),
		round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY ms)::numeric, 3),
		round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3),
		round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 3),
		round(avg(ms)::numeric, 3), round(max(ms)::numeric, 3) FROM _t;
END $$ LANGUAGE plpgsql;

\echo '### 결과표 — 규모별 p50/p95 (ms). current = 현재 쿼리, candidate = 후보 자리'
SELECT s.label, s.arm, r.*
FROM (VALUES ('1k', 'current', :uid_1k), ('1k', 'candidate', :uid_1k),
             ('10k', 'current', :uid_10k), ('10k', 'candidate', :uid_10k),
             ('100k', 'current', :uid_100k), ('100k', 'candidate', :uid_100k),
             ('400k grids', 'current', :uid_400kg), ('400k grids', 'candidate', :uid_400kg)) AS s(label, arm, uid),
     LATERAL bench_run('bench_' || s.arm, s.uid, :iters) r
ORDER BY CASE s.label WHEN '1k' THEN 1 WHEN '10k' THEN 2 WHEN '100k' THEN 3 ELSE 4 END, s.arm DESC;

\echo '### 등가성 검사 — current 와 candidate 의 값이 규모 4개 전부 같아야 한다'
DO $$
DECLARE r record; bad int := 0;
BEGIN
	FOR r IN SELECT n, user_id, bench_current(user_id) AS cur, bench_candidate(user_id) AS cand
	         FROM b596_plan WHERE n <= 3 ORDER BY n LOOP
		RAISE NOTICE 'n=% uid=% current=% candidate=%', r.n, r.user_id, r.cur, r.cand;
		IF r.cur IS DISTINCT FROM r.cand THEN bad := bad + 1; END IF;
	END LOOP;
	IF bad > 0 THEN RAISE EXCEPTION '등가성 실패: % 건 불일치', bad; END IF;
	RAISE NOTICE '등가성 OK';
END $$;

-- ============================================================================
-- 섹션 4 — 원복(롤백 모드) 또는 유지(keep=1).
--   롤백: ROLLBACK 으로 행·임시테이블·함수 전부 되돌리고, reltuples 잔류는 재-ANALYZE 로 실제값 복원.
--   유지: 하네스 함수만 지우고 COMMIT. 더미 사용자 oid 를 출력한다(k6 OID 인자).
-- ============================================================================
\if :keep
DROP FUNCTION bench_run(text, bigint, int); DROP FUNCTION bench_current(bigint); DROP FUNCTION bench_candidate(bigint);
COMMIT;
\echo '### 유지 모드 — 더미가 남아 있다. k6 OID: b596-0(1천) b596-1(1만) b596-2(10만) b596-3(격자 40만)'
\echo '### 끝나면: docker exec -i fillmap-postgres psql -U user -d fillmap < scripts/bench-msg596-cleanup.sql'
\else
ROLLBACK;
ANALYZE users; ANALYZE grids; ANALYZE videos; ANALYZE user_grids;
\endif

\echo '### 원상복구 검증 (기대: 벤치 행 0, videos_reltuples = 실제 videos 수)'
SELECT (SELECT count(*) FROM users WHERE email LIKE 'b596-%') AS bench_users,
       (SELECT count(*) FROM grids WHERE grid_id LIKE 'b596\_%') AS bench_grids,
       (SELECT count(*) FROM videos) AS videos,
       (SELECT count(*) FROM user_grids) AS user_grids,
       (SELECT reltuples::bigint FROM pg_class WHERE relname = 'videos') AS videos_reltuples,
       (SELECT count(*) FROM pg_proc WHERE proname LIKE 'bench\_%') AS leftover_funcs;
