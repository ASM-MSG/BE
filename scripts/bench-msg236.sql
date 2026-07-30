-- ============================================================================
-- MSG-236 [벤치] refreshRegionStats recompute: 구 ST_Covers LATERAL 2회 vs 신 equi-join
--
-- 목적: MSG-167 §D6 후속(MSG-236) — refreshRegionStats 의 두 ST_Covers LATERAL 을
--   grids.region_code equi-join 으로 치환한 것의 성능 효과를 채택 근거로 실측한다.
--   구 팔(HEAD)과 신 팔(feature/MSG-236-recompute-equi 현행)을 동일 (userId, gridId) 로
--   K=10/100/1,000(사용자 점령 격자 수 = numerator 루프 크기) 스윕하며 비교한다.
--
-- 재현 (로컬 공유 PostGIS 컨테이너 fillmap-postgres 기동 상태):
--   docker exec -i fillmap-postgres psql -U user -d fillmap < scripts/bench-msg236.sql
--
-- 전체가 단일 트랜잭션: 셋업 → 라벨 백필(시간 기록) → EXPLAIN(ANALYZE,BUFFERS)
--   → p50/p95/p99 하네스(K별) → 끝에서 ROLLBACK 전량 원복 → 검증.
-- 측정 대상: SELECT 부분(귀속·집계)만. INSERT ... ON CONFLICT(물질화)는 양팔 동일 → 타이밍 밖.
--
-- 공유 DB 가드레일:
--   - regions(3,558 실데이터)는 읽기만.
--   - 벤치 데이터는 격리 식별자(bench-msg236@bench.local, 서울 격자 블록)로만. 격리는 좌표 유일성이
--     아니라 ROLLBACK — 신 팔 equi 가 실 행정동 라벨을 읽어야 해 서해 공해상(bench164)을 못 쓴다.
--   - 원복: 합성 행·임시테이블·함수를 한 트랜잭션에 담고 마지막 ROLLBACK. 중간 실패해도
--     (ON_ERROR_STOP) 커밋된 게 없어 세션 종료 시 자동 롤백 → 잔류 불가.
--   - ponytail: ANALYZE 의도적 생략(bench-msg164 동일) — 트랜잭션 안 ANALYZE 는 reltuples 를
--     inplace 갱신해 ROLLBACK 으로도 안 지워진다 → 통계 잔류 원천 차단.
--   - 무해 잔류: users/grids id 시퀀스 전진(nextval 비트랜잭셔널). surrogate 갭이라 무해.
-- ============================================================================
\set ON_ERROR_STOP on
-- 하네스 반복 수(샘플수 = iters). 느리면 낮춰라(구 팔 K=1000 은 반복당 ≈1001 GIST 판정).
\set iters 300

-- ============================================================================
-- 섹션 1 — 전제 확인 + 격리 벤치 데이터 셋업. 이 BEGIN 이 스크립트 전체를 연다.
-- ============================================================================
BEGIN;

\echo '### 전제: regions 시딩 수 (기대 3,558)'
SELECT count(*) AS regions_seeded FROM regions;

-- 벤치 유저 1명 (LOCAL: chk_users_auth — password_hash NOT NULL, oid NULL)
INSERT INTO users (provider, email, password_hash, nickname, grid_color, role, email_verified)
VALUES ('LOCAL', 'bench-msg236@bench.local', 'x', 'bench-msg236', 'BLUE', 'USER', TRUE)
ON CONFLICT (email) DO NOTHING;

-- 서울 육지 격자 블록 32×32=1,024 (GridConstants LAT=0.0009, LNG=0.00115; README §3 축소판).
-- region_code 는 raw NULL 삽입 → 섹션 2 에서 V5 규칙 백필(프로덕션 upsertGrid 라벨 재현).
INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
SELECT y || '_' || x, y, x,
       ST_SetSRID(ST_MakePoint((x + 0.5) * 0.00115, (y + 0.5) * 0.0009), 4326)::geography,
       ST_MakeEnvelope(x * 0.00115, y * 0.0009, (x + 1) * 0.00115, (y + 1) * 0.0009, 4326)::geography
FROM generate_series(41700, 41731) AS y,
     generate_series(110400, 110431) AS x
ON CONFLICT (grid_id) DO NOTHING;

-- ============================================================================
-- 섹션 2 — 벤치 격자 한정 V5 규칙 라벨 백필(소요시간 1회) + 타겟 라벨 fail-fast + 변수 캡처.
--   전역 backfillGridRegionCodes() 대신 벤치 블록만 스코프해 공유 DB 오염을 막는다.
-- ============================================================================
\echo '### 라벨 백필: 벤치 격자 한정 V5 규칙(중심점 덮는 region_code 오름차순 첫 행정동), 소요시간 1회'
DO $$
DECLARE t0 timestamptz := clock_timestamp(); n bigint;
BEGIN
	UPDATE grids g SET region_code = (
		SELECT r.region_code FROM regions r
		WHERE ST_Covers(r.boundary_geom, g.center_geom)
		ORDER BY r.region_code
		LIMIT 1)
	WHERE g.grid_y BETWEEN 41700 AND 41731
	  AND g.grid_x BETWEEN 110400 AND 110431
	  AND g.region_code IS NULL;
	GET DIAGNOSTICS n = ROW_COUNT;
	RAISE NOTICE '라벨 백필 완료: % 행, % ms', n,
		round((extract(epoch FROM clock_timestamp() - t0) * 1000)::numeric, 2);
END $$;

-- 타겟 격자(블록 첫 셀)가 실제로 라벨됐는지 fail-fast — NULL 이면 양팔 no-op 이라 벤치 무의미.
DO $$
DECLARE rc text;
BEGIN
	SELECT region_code INTO rc FROM grids WHERE grid_id = '41700_110400';
	IF rc IS NULL THEN
		RAISE EXCEPTION '타겟 격자 41700_110400 라벨 NULL — 육지 좌표/regions 시딩 확인 필요';
	END IF;
	RAISE NOTICE '타겟 격자 41700_110400 → region_code=%', rc;
END $$;

SELECT id AS bench_user FROM users WHERE email = 'bench-msg236@bench.local' \gset
\set target_grid 41700_110400

-- ============================================================================
-- 섹션 3 — p50/p95/p99 하네스. 구 팔(bench_old, ST_Covers LATERAL 2회 — HEAD 인라인 보존),
--   신 팔(bench_new, equi-join — feature/MSG-236-recompute-equi 현행 본문).
--   warmup 5회(generic plan 캐시) 뒤 clock_timestamp 반복. 함수도 이 트랜잭션 소속 → ROLLBACK 으로 소멸.
-- ============================================================================
CREATE OR REPLACE FUNCTION bench_old(p_grid text, p_user bigint, iters int)
RETURNS TABLE(samples bigint, p50_ms numeric, p95_ms numeric, p99_ms numeric, avg_ms numeric, max_ms numeric)
AS $$
DECLARE t0 timestamptz; rc text; col bigint; tot int; k int;
BEGIN
	DROP TABLE IF EXISTS _t; CREATE TEMP TABLE _t(ms float8);
	FOR k IN 1..5 LOOP                                          -- warmup
		SELECT tr.region_code, cnt.collected, tr.total_grid_count INTO rc, col, tot
		FROM grids g
		JOIN LATERAL (
			SELECT r0.region_code, r0.total_grid_count
			FROM regions r0
			WHERE ST_Covers(r0.boundary_geom, g.center_geom)
			ORDER BY r0.region_code
			LIMIT 1
		) tr ON TRUE
		JOIN LATERAL (
			SELECT COUNT(*) AS collected
			FROM user_grids ug
			JOIN grids g2 ON g2.grid_id = ug.grid_id
			WHERE ug.user_id = p_user AND tr.region_code = (
				SELECT r2.region_code
				FROM regions r2
				WHERE ST_Covers(r2.boundary_geom, g2.center_geom)
				ORDER BY r2.region_code
				LIMIT 1
			)
		) cnt ON TRUE
		WHERE g.grid_id = p_grid;
	END LOOP;
	FOR k IN 1..iters LOOP
		t0 := clock_timestamp();
		SELECT tr.region_code, cnt.collected, tr.total_grid_count INTO rc, col, tot
		FROM grids g
		JOIN LATERAL (
			SELECT r0.region_code, r0.total_grid_count
			FROM regions r0
			WHERE ST_Covers(r0.boundary_geom, g.center_geom)
			ORDER BY r0.region_code
			LIMIT 1
		) tr ON TRUE
		JOIN LATERAL (
			SELECT COUNT(*) AS collected
			FROM user_grids ug
			JOIN grids g2 ON g2.grid_id = ug.grid_id
			WHERE ug.user_id = p_user AND tr.region_code = (
				SELECT r2.region_code
				FROM regions r2
				WHERE ST_Covers(r2.boundary_geom, g2.center_geom)
				ORDER BY r2.region_code
				LIMIT 1
			)
		) cnt ON TRUE
		WHERE g.grid_id = p_grid;
		INSERT INTO _t VALUES (extract(epoch FROM clock_timestamp() - t0) * 1000);
	END LOOP;
	RETURN QUERY SELECT count(*),
		round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(avg(ms)::numeric, 4), round(max(ms)::numeric, 4) FROM _t;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION bench_new(p_grid text, p_user bigint, iters int)
RETURNS TABLE(samples bigint, p50_ms numeric, p95_ms numeric, p99_ms numeric, avg_ms numeric, max_ms numeric)
AS $$
DECLARE t0 timestamptz; rc text; col bigint; tot int; k int;
BEGIN
	DROP TABLE IF EXISTS _t; CREATE TEMP TABLE _t(ms float8);
	FOR k IN 1..5 LOOP                                          -- warmup
		SELECT tr.region_code, cnt.collected, tr.total_grid_count INTO rc, col, tot
		FROM grids g
		JOIN regions tr ON tr.region_code = g.region_code
		JOIN LATERAL (
			SELECT COUNT(*) AS collected
			FROM user_grids ug
			JOIN grids g2 ON g2.grid_id = ug.grid_id
			WHERE ug.user_id = p_user
			  AND g2.region_code = tr.region_code
		) cnt ON TRUE
		WHERE g.grid_id = p_grid
		  AND g.region_code IS NOT NULL;
	END LOOP;
	FOR k IN 1..iters LOOP
		t0 := clock_timestamp();
		SELECT tr.region_code, cnt.collected, tr.total_grid_count INTO rc, col, tot
		FROM grids g
		JOIN regions tr ON tr.region_code = g.region_code
		JOIN LATERAL (
			SELECT COUNT(*) AS collected
			FROM user_grids ug
			JOIN grids g2 ON g2.grid_id = ug.grid_id
			WHERE ug.user_id = p_user
			  AND g2.region_code = tr.region_code
		) cnt ON TRUE
		WHERE g.grid_id = p_grid
		  AND g.region_code IS NOT NULL;
		INSERT INTO _t VALUES (extract(epoch FROM clock_timestamp() - t0) * 1000);
	END LOOP;
	RETURN QUERY SELECT count(*),
		round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(avg(ms)::numeric, 4), round(max(ms)::numeric, 4) FROM _t;
END $$ LANGUAGE plpgsql;

-- ============================================================================
-- 섹션 4 — K 스윕(10/100/1,000). 각 K 마다 user_grids 리셋+재시드(타겟 포함) 후 하네스.
--   EXPLAIN(ANALYZE,BUFFERS)·등가 스팟체크는 K=1000(최악)에서 각 1회.
-- ============================================================================
\echo '################### K = 10 ###################'
DELETE FROM user_grids WHERE user_id = :bench_user;
INSERT INTO user_grids (user_id, grid_id, video_count)
SELECT :bench_user, grid_id, 1
FROM grids
WHERE grid_y BETWEEN 41700 AND 41731 AND grid_x BETWEEN 110400 AND 110431
ORDER BY grid_y, grid_x
LIMIT 10
ON CONFLICT (user_id, grid_id) DO NOTHING;
\echo '### (구) p50/p95/p99/avg/max ms — ST_Covers LATERAL 2회 (K=10)'
SELECT * FROM bench_old(:'target_grid', :bench_user, :iters);
\echo '### (신) p50/p95/p99/avg/max ms — equi-join (K=10)'
SELECT * FROM bench_new(:'target_grid', :bench_user, :iters);

\echo '################### K = 100 ###################'
DELETE FROM user_grids WHERE user_id = :bench_user;
INSERT INTO user_grids (user_id, grid_id, video_count)
SELECT :bench_user, grid_id, 1
FROM grids
WHERE grid_y BETWEEN 41700 AND 41731 AND grid_x BETWEEN 110400 AND 110431
ORDER BY grid_y, grid_x
LIMIT 100
ON CONFLICT (user_id, grid_id) DO NOTHING;
\echo '### (구) p50/p95/p99/avg/max ms — ST_Covers LATERAL 2회 (K=100)'
SELECT * FROM bench_old(:'target_grid', :bench_user, :iters);
\echo '### (신) p50/p95/p99/avg/max ms — equi-join (K=100)'
SELECT * FROM bench_new(:'target_grid', :bench_user, :iters);

\echo '################### K = 1000 ###################'
DELETE FROM user_grids WHERE user_id = :bench_user;
INSERT INTO user_grids (user_id, grid_id, video_count)
SELECT :bench_user, grid_id, 1
FROM grids
WHERE grid_y BETWEEN 41700 AND 41731 AND grid_x BETWEEN 110400 AND 110431
ORDER BY grid_y, grid_x
LIMIT 1000
ON CONFLICT (user_id, grid_id) DO NOTHING;

\echo '### 등가 스팟체크(구=신 이어야: region_code·collected) — 실데이터 등가 (K=1000)'
SELECT 'OLD' AS arm, tr.region_code, cnt.collected
FROM grids g
JOIN LATERAL (
	SELECT r0.region_code, r0.total_grid_count
	FROM regions r0
	WHERE ST_Covers(r0.boundary_geom, g.center_geom)
	ORDER BY r0.region_code
	LIMIT 1
) tr ON TRUE
JOIN LATERAL (
	SELECT COUNT(*) AS collected
	FROM user_grids ug
	JOIN grids g2 ON g2.grid_id = ug.grid_id
	WHERE ug.user_id = :bench_user AND tr.region_code = (
		SELECT r2.region_code
		FROM regions r2
		WHERE ST_Covers(r2.boundary_geom, g2.center_geom)
		ORDER BY r2.region_code
		LIMIT 1
	)
) cnt ON TRUE
WHERE g.grid_id = :'target_grid'
UNION ALL
SELECT 'NEW', tr.region_code, cnt.collected
FROM grids g
JOIN regions tr ON tr.region_code = g.region_code
JOIN LATERAL (
	SELECT COUNT(*) AS collected
	FROM user_grids ug
	JOIN grids g2 ON g2.grid_id = ug.grid_id
	WHERE ug.user_id = :bench_user
	  AND g2.region_code = tr.region_code
) cnt ON TRUE
WHERE g.grid_id = :'target_grid'
  AND g.region_code IS NOT NULL;

\echo '### (구) EXPLAIN (ANALYZE, BUFFERS) — ST_Covers LATERAL 2회 (K=1000, 최악)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT tr.region_code, cnt.collected, tr.total_grid_count
FROM grids g
JOIN LATERAL (
	SELECT r0.region_code, r0.total_grid_count
	FROM regions r0
	WHERE ST_Covers(r0.boundary_geom, g.center_geom)
	ORDER BY r0.region_code
	LIMIT 1
) tr ON TRUE
JOIN LATERAL (
	SELECT COUNT(*) AS collected
	FROM user_grids ug
	JOIN grids g2 ON g2.grid_id = ug.grid_id
	WHERE ug.user_id = :bench_user AND tr.region_code = (
		SELECT r2.region_code
		FROM regions r2
		WHERE ST_Covers(r2.boundary_geom, g2.center_geom)
		ORDER BY r2.region_code
		LIMIT 1
	)
) cnt ON TRUE
WHERE g.grid_id = :'target_grid';

\echo '### (신) EXPLAIN (ANALYZE, BUFFERS) — equi-join (K=1000; GIST/ST_ 부재 확인)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT tr.region_code, cnt.collected, tr.total_grid_count
FROM grids g
JOIN regions tr ON tr.region_code = g.region_code
JOIN LATERAL (
	SELECT COUNT(*) AS collected
	FROM user_grids ug
	JOIN grids g2 ON g2.grid_id = ug.grid_id
	WHERE ug.user_id = :bench_user
	  AND g2.region_code = tr.region_code
) cnt ON TRUE
WHERE g.grid_id = :'target_grid'
  AND g.region_code IS NOT NULL;

\echo '### (구) p50/p95/p99/avg/max ms — ST_Covers LATERAL 2회 (K=1000)'
SELECT * FROM bench_old(:'target_grid', :bench_user, :iters);
\echo '### (신) p50/p95/p99/avg/max ms — equi-join (K=1000)'
SELECT * FROM bench_new(:'target_grid', :bench_user, :iters);

-- ============================================================================
-- 섹션 5 — 원복 + 검증. ROLLBACK 이 섹션1 BEGIN 이래 전부(행·임시테이블·함수)를 되돌린다.
--   ANALYZE 미실행 → pg_class 통계 무변경(reltuples 잔여 없음). 시퀀스 전진만 무해 잔류.
-- ============================================================================
ROLLBACK;

\echo '### 원상복구 검증 (기대: regions=3558, bench_users=0, bench_user_grids=0)'
SELECT (SELECT count(*) FROM regions)                                   AS regions,
       (SELECT count(*) FROM users WHERE email = 'bench-msg236@bench.local') AS bench_users,
       (SELECT count(*) FROM user_grids ug JOIN users u ON u.id = ug.user_id
          WHERE u.email = 'bench-msg236@bench.local')                   AS bench_user_grids,
       (SELECT reltuples::bigint FROM pg_class WHERE relname = 'grids') AS grids_reltuples;
