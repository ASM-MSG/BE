-- ============================================================================
-- MSG-164 [스파이크] 좌표→행정동 판별 성능 비교: 폴리곤 ST_Covers vs 컬럼 레이블
--
-- 재현 방법 (로컬 공유 PostGIS 컨테이너 fillmap-postgres 기동 상태에서):
--   docker exec -i fillmap-postgres psql -U user -d fillmap < scripts/bench-msg164.sql
--   (반복 수 조절: 파일 하단 하네스 호출의 :iters. 구조만 빨리 보려면 \set iters 를 작게.)
--
-- 이 스크립트 하나로 [전체가 단일 트랜잭션]: 격리 벤치 데이터 셋업 → EXPLAIN(ANALYZE,BUFFERS)
--   → p50/p95 하네스 → 끝에서 ROLLBACK 으로 전량 원복 → 원상복구 검증.
--
-- 공유 DB 가드레일:
--   - regions(3,558 실데이터)는 읽기만. INSERT/UPDATE/DELETE 없음.
--   - 벤치 데이터는 격리 식별자(bench164_*, 서해 공해상 좌표)로만 생성.
--   - videos.region_code 는 regions FK 제약이 있어 999대역 코드를 못 넣으므로
--     "실제 region_code를 읽기전용으로 빌려" 채운다(레이블 읽기 비용은 값과 무관).
--   - 원복 보장: 합성 행·임시테이블·함수를 하나의 트랜잭션에 담고 마지막에 ROLLBACK.
--     중간 어디서 실패해도(ON_ERROR_STOP) 커밋된 게 없어 세션 종료 시 자동 롤백 → 잔류 불가.
--   - ponytail: ANALYZE 의도적 생략. (b)는 PK 단건 조회라 행통계가 플랜에 영향 없고(항상 videos_pkey),
--     ANALYZE 의 pg_class.reltuples 는 inplace 갱신이라 ROLLBACK 으로도 안 지워진다(본 스파이크서 실측).
--     즉 트랜잭션 안에서 ANALYZE 하면 낡은 통계가 잔류하므로, 아예 하지 않아 ①통계잔류를 원천 차단.
--
-- (선택) pgbench 교차검증은 파일 맨 아래 주석 참조(별도 세션이라 커밋된 데이터가 필요 → 전용 절차).
-- ============================================================================
\set ON_ERROR_STOP on
-- 하네스 반복 수 (샘플수 = iters * 28). 구조만 빨리 볼 땐 작게 (예: 아래를 5로).
-- 주의: psql \set 은 같은 줄의 -- 주석까지 값에 삼키므로 주석을 이 줄에 붙이지 말 것.
\set iters 300

-- ============================================================================
-- 섹션 1 — 격리 벤치 데이터 셋업 (arm b 측정 대상). 이 BEGIN 이 스크립트 전체를 연다.
-- ============================================================================
BEGIN;

INSERT INTO users (provider, email, password_hash, nickname, grid_color, role, email_verified)
VALUES ('LOCAL', 'bench-msg164@bench.local', 'x', 'bench-msg164', 'BLUE', 'USER', TRUE)
ON CONFLICT (email) DO NOTHING;

INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
VALUES ('bench164_grid', 999001, 999001,
        ST_SetSRID(ST_MakePoint(124.5, 35.0), 4326)::geography,      -- 서해 공해상
        ST_SetSRID(ST_GeomFromText(
          'POLYGON((124.4990 34.9990,124.5010 34.9990,124.5010 35.0010,124.4990 35.0010,124.4990 34.9990))'
        ), 4326)::geography)
ON CONFLICT (grid_id) DO NOTHING;

-- 5,000 벤치 영상: region_code 는 실제 200개 코드를 순환(빌린 값, FK 충족), geom 은 서해
WITH codes AS (
    SELECT region_code, (row_number() OVER (ORDER BY region_code) - 1) AS rn
    FROM regions ORDER BY region_code LIMIT 200
),
u AS (SELECT id FROM users WHERE email = 'bench-msg164@bench.local')
INSERT INTO videos (user_id, grid_id, region_code, geom, duration_sec, recorded_at)
SELECT (SELECT id FROM u), 'bench164_grid',
       (SELECT region_code FROM codes WHERE rn = g % 200),
       ST_SetSRID(ST_MakePoint(124.5, 35.0), 4326)::geography,
       10, now()
FROM generate_series(1, 5000) g;

-- 전국 육지 샘플 좌표(+경계 근처) — 하네스/ pgbench 공용
CREATE TEMP TABLE bench_msg164_pts (rn int PRIMARY KEY, lat float8, lon float8, label text);
INSERT INTO bench_msg164_pts (rn, lat, lon, label) VALUES
 (1 ,37.4979,127.0276,'서울 강남역'),   (2 ,37.5663,126.9779,'서울 시청'),
 (3 ,37.5563,126.9236,'서울 홍대'),     (4 ,37.4894,126.7246,'인천 부평'),
 (5 ,37.2636,127.0286,'수원'),          (6 ,35.1577,129.0594,'부산 서면'),
 (7 ,35.1631,129.1637,'부산 해운대'),   (8 ,35.8714,128.6014,'대구'),
 (9 ,35.1595,126.8526,'광주'),          (10,36.3504,127.3845,'대전'),
 (11,35.5384,129.3114,'울산'),          (12,33.4996,126.5312,'제주시'),
 (13,33.2541,126.5601,'서귀포'),        (14,37.7519,128.8761,'강릉'),
 (15,37.8813,127.7300,'춘천'),          (16,35.8242,127.1480,'전주'),
 (17,36.6424,127.4890,'청주'),          (18,36.0190,129.3435,'포항'),
 (19,35.2280,128.6811,'창원'),          (20,36.8151,127.1139,'천안'),
 (21,34.8118,126.3922,'목포'),          (22,34.7604,127.6622,'여수'),
 (23,36.5684,128.7294,'안동'),          (24,37.3422,127.9202,'원주'),
 (25,35.2285,128.8894,'김해'),          (26,37.5219,126.9245,'여의도(경계근처)'),
 (27,37.5700,126.9910,'종로-중구 경계근처'), (28,37.5040,127.0100,'서초-강남 경계근처');

-- ============================================================================
-- 섹션 2 — EXPLAIN (ANALYZE, BUFFERS) : 인덱스 사용 실측 (핵심 산출물)
--   * 첫 실행은 플랜/버퍼 콜드라 Planning/Execution 이 크게 나온다.
--     정상상태(warm) 수치는 섹션 3 하네스가 준다.
-- ============================================================================
\echo '### (a) 폴리곤 ST_Covers — GIST idx_regions_boundary (강남역)'
SELECT count(*) FROM regions
  WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(127.0276,37.4979),4326)::geography);  -- warmup
EXPLAIN (ANALYZE, BUFFERS)
SELECT region_code, region_name, parent_code FROM regions
WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(127.0276,37.4979),4326)::geography)
LIMIT 1;

\echo '### (a) 미스매치(바다) — 동해상. 인덱스로 후보 0건 확인'
EXPLAIN (ANALYZE, BUFFERS)
SELECT region_code FROM regions
WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(130.5,37.5),4326)::geography)
LIMIT 1;

\echo '### (a) 인덱스 미사용 대조군 — 강제 seqscan, 3,558 폴리곤 전수 ST_Covers'
-- 이미 트랜잭션 안이므로 중첩 BEGIN 불가 → SAVEPOINT 로 국소화한다.
-- SET LOCAL 은 savepoint 롤백으로도 원복되어 이후 하네스(arm a)가 다시 인덱스를 탄다.
SAVEPOINT ctl;
SET LOCAL enable_indexscan=off;
SET LOCAL enable_bitmapscan=off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT region_code FROM regions
WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(127.0276,37.4979),4326)::geography)
LIMIT 1;
ROLLBACK TO SAVEPOINT ctl;

\echo '### (b) 사전 레이블 컬럼 — videos.region_code PK 단건 읽기'
-- 대상 id 는 측정 문장 밖에서 미리 고른다(\gset, 트랜잭션 안이라 가능). 이래야 EXPLAIN 이
-- 순수 PK 조회만 보이고 2,500행 탐색/정렬/스킵 비용이 안 섞인다.
SELECT id AS bench_b_id FROM videos WHERE grid_id='bench164_grid' ORDER BY id OFFSET 2500 LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS)
SELECT region_code FROM videos WHERE id = :bench_b_id;

-- ============================================================================
-- 섹션 3 — p50/p95 하네스 (정상상태: warmup 후 반복, clock_timestamp + percentile_cont)
--   plpgsql 정적 쿼리는 5회 뒤 generic plan 을 캐시 → 앱의 서버-준비 statement 와 동일 조건.
--   측정 구간은 SELECT 만(INSERT 는 타이밍 밖). 서버측 순수 statement 레이턴시(네트워크 제외).
--   함수도 이 트랜잭션 소속 → 끝의 ROLLBACK 으로 함께 사라진다(DROP 불요).
-- ============================================================================
CREATE OR REPLACE FUNCTION bench_a(iters int)
RETURNS TABLE(samples bigint, p50_ms numeric, p95_ms numeric, p99_ms numeric, avg_ms numeric, max_ms numeric)
AS $$
DECLARE s record; t0 timestamptz; d text; k int;
BEGIN
	DROP TABLE IF EXISTS _t; CREATE TEMP TABLE _t(ms float8);
	FOR s IN SELECT lat, lon FROM bench_msg164_pts LOOP        -- warmup
		SELECT region_code INTO d FROM regions
			WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(s.lon, s.lat), 4326)::geography) LIMIT 1;
	END LOOP;
	FOR k IN 1..iters LOOP
		FOR s IN SELECT lat, lon FROM bench_msg164_pts LOOP
			t0 := clock_timestamp();
			SELECT region_code INTO d FROM regions
				WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(s.lon, s.lat), 4326)::geography) LIMIT 1;
			INSERT INTO _t VALUES (extract(epoch FROM clock_timestamp() - t0) * 1000);
		END LOOP;
	END LOOP;
	RETURN QUERY SELECT count(*),
		round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(avg(ms)::numeric, 4), round(max(ms)::numeric, 4) FROM _t;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION bench_b(iters int)
RETURNS TABLE(samples bigint, p50_ms numeric, p95_ms numeric, p99_ms numeric, avg_ms numeric, max_ms numeric)
AS $$
DECLARE t0 timestamptz; d text; k int; j int; cnt int; n int; vid bigint; ids bigint[];
BEGIN
	DROP TABLE IF EXISTS _t; CREATE TEMP TABLE _t(ms float8);
	cnt := (SELECT count(*) FROM bench_msg164_pts);           -- arm(a)와 동일 ops/iter
	-- 실제 삽입된 bench 행의 id 만 배열로 적재(타이밍 밖). videos.id 는 비트랜잭셔널·전역 시퀀스라
	-- min~max 범위 난수로 뽑으면 동시 삽입/롤백이 만든 구멍(없는 id)을 쳐서 heap 접근이 생략돼
	-- (b)를 실제보다 빠르게 편향시킨다 → 존재하는 bench id 에서만 뽑아 그 편향을 제거.
	ids := ARRAY(SELECT id FROM videos WHERE grid_id = 'bench164_grid' ORDER BY id);
	n := array_length(ids, 1);
	PERFORM region_code FROM videos WHERE id = ids[1];        -- warmup
	FOR k IN 1..iters LOOP
		FOR j IN 1..cnt LOOP
			vid := ids[1 + floor(random() * n)::int];         -- 항상 존재하는 bench 행
			t0 := clock_timestamp();
			SELECT region_code INTO d FROM videos WHERE id = vid;
			INSERT INTO _t VALUES (extract(epoch FROM clock_timestamp() - t0) * 1000);
		END LOOP;
	END LOOP;
	RETURN QUERY SELECT count(*),
		round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 4),
		round(avg(ms)::numeric, 4), round(max(ms)::numeric, 4) FROM _t;
END $$ LANGUAGE plpgsql;

\echo '### ARM (a) 폴리곤 ST_Covers — p50/p95/p99 (ms)'
SELECT * FROM bench_a(:iters);
\echo '### ARM (b) videos.region_code 컬럼 읽기 — p50/p95/p99 (ms)'
SELECT * FROM bench_b(:iters);

-- ============================================================================
-- 섹션 4 — 원복 + 원상복구 검증. ROLLBACK 이 섹션1의 BEGIN 이래 전부(행·임시테이블·함수)를 되돌린다.
--   ANALYZE 를 안 했으므로 pg_class 통계도 건드리지 않음 → videos_reltuples 는 실행 전과 동일(0).
--   알려진 잔류 1가지: users/videos id 시퀀스 전진(~5,001/run). nextval 은 비트랜잭셔널이라 ROLLBACK 으로
--   안 돌아간다 — surrogate key 갭이라 무해(모든 롤백된 INSERT 가 동일하게 소비). 복원 대상 아님.
-- ============================================================================
ROLLBACK;

\echo '### 원상복구 검증 (기대: regions=3558, videos=0, users=4, grids=0, user_grids=0, leftover=0, videos_reltuples=0)'
SELECT (SELECT count(*) FROM regions)    AS regions,
       (SELECT count(*) FROM videos)     AS videos,
       (SELECT count(*) FROM users)      AS users,
       (SELECT count(*) FROM grids)      AS grids,
       (SELECT count(*) FROM user_grids) AS user_grids,
       (SELECT count(*) FROM pg_tables WHERE tablename='bench_msg164_pts') AS leftover_bench_tbl,
       (SELECT reltuples::bigint FROM pg_class WHERE relname='videos') AS videos_reltuples;

-- ============================================================================
-- (선택) pgbench 교차검증 — 별도 세션이라 위 롤백-모드로는 데이터가 안 보인다.
--   전용 절차(commit 모드): 셋업 커밋 → pgbench → 정리(+통계 원복). psql 밖(호스트 셸)에서 실행.
--   실행법: 아래 각 줄 맨 앞 '-- '(3글자) 제거 후 호스트 셸에 붙여넣기 (예: 블록 복사 → `sed 's/^-- //'`).
--     주의: heredoc 종료어(SQL)는 '-- ' 제거 뒤 반드시 행 맨 앞에 와야 한다(본문 줄만 들여씀).
--   ⚠️ 정리 DELETE 가 무조건 삭제이므로, 선존재하는 동일 식별자 행(중단된 과거 실행 잔재·우연한 충돌)을
--      지우거나 연쇄 삭제할 수 있다 → 반드시 아래 (0) fail-fast 선점검을 먼저 한다. ON CONFLICT 재사용 금지.
--
--   # 0) fail-fast — 식별자 선존재 검사. 0 이 아니면 즉시 중단하고 원인 확인(내 잔재면 아래 3)로 정리 후 재시도).
--   docker exec fillmap-postgres psql -U user -d fillmap -tAc "
--     SELECT (SELECT count(*) FROM users WHERE email='bench-msg164@bench.local')
--          + (SELECT count(*) FROM grids WHERE grid_id='bench164_grid')
--          + (SELECT count(*) FROM videos WHERE grid_id='bench164_grid')
--          + (SELECT count(*) FROM pg_tables WHERE tablename='bench_msg164_pts');"
--   # ↑ 출력이 0 이 아니면 STOP. (다른 트랙 데이터일 수 있으니 무단 삭제하지 말 것.)
--
--   # 1) 셋업 커밋 — 실 SQL(ON CONFLICT 없음: 선존재면 에러=fail-fast). pts 는 pgbench 세션이 봐야 하니 일반 테이블.
--   #    반드시 '동시 videos 삽입이 없는 창'에서: 5,000행이 연속 id 를 받아 (2)의 range 난수가 구멍 없는 순수 PK 읽기가 됨.
--   docker exec -i fillmap-postgres psql -U user -d fillmap -v ON_ERROR_STOP=1 <<'SQL'
--   BEGIN;
--   INSERT INTO users (provider, email, password_hash, nickname, grid_color, role, email_verified)
--   VALUES ('LOCAL','bench-msg164@bench.local','x','bench-msg164','BLUE','USER',TRUE);
--   INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
--   VALUES ('bench164_grid',999001,999001,
--     ST_SetSRID(ST_MakePoint(124.5,35.0),4326)::geography,
--     ST_SetSRID(ST_GeomFromText('POLYGON((124.4990 34.9990,124.5010 34.9990,124.5010 35.0010,124.4990 35.0010,124.4990 34.9990))'),4326)::geography);
--   WITH codes AS (SELECT region_code,(row_number() OVER (ORDER BY region_code)-1) AS rn FROM regions ORDER BY region_code LIMIT 200),
--        u AS (SELECT id FROM users WHERE email='bench-msg164@bench.local')
--   INSERT INTO videos (user_id, grid_id, region_code, geom, duration_sec, recorded_at)
--   SELECT (SELECT id FROM u),'bench164_grid',(SELECT region_code FROM codes WHERE rn=g%200),
--          ST_SetSRID(ST_MakePoint(124.5,35.0),4326)::geography,10,now()
--   FROM generate_series(1,5000) g;
--   CREATE TABLE bench_msg164_pts (rn int PRIMARY KEY, lat float8, lon float8, label text);
--   INSERT INTO bench_msg164_pts (rn,lat,lon,label) VALUES
--    (1,37.4979,127.0276,'강남역'),(2,37.5663,126.9779,'시청'),(3,37.5563,126.9236,'홍대'),(4,37.4894,126.7246,'부평'),
--    (5,37.2636,127.0286,'수원'),(6,35.1577,129.0594,'서면'),(7,35.1631,129.1637,'해운대'),(8,35.8714,128.6014,'대구'),
--    (9,35.1595,126.8526,'광주'),(10,36.3504,127.3845,'대전'),(11,35.5384,129.3114,'울산'),(12,33.4996,126.5312,'제주'),
--    (13,33.2541,126.5601,'서귀포'),(14,37.7519,128.8761,'강릉'),(15,37.8813,127.7300,'춘천'),(16,35.8242,127.1480,'전주'),
--    (17,36.6424,127.4890,'청주'),(18,36.0190,129.3435,'포항'),(19,35.2280,128.6811,'창원'),(20,36.8151,127.1139,'천안'),
--    (21,34.8118,126.3922,'목포'),(22,34.7604,127.6622,'여수'),(23,36.5684,128.7294,'안동'),(24,37.3422,127.9202,'원주'),
--    (25,35.2285,128.8894,'김해'),(26,37.5219,126.9245,'여의도'),(27,37.5700,126.9910,'종로중구경계'),(28,37.5040,127.0100,'서초강남경계');
--   COMMIT;
-- SQL
--
--   # 2) a.sql/b.sql 생성(호스트 printf → 컨테이너 파일) 후 pgbench(prepared, 1 client, 8s).
--   #    (1)이 연속 id 를 보장 → b.sql 은 실제 min~max range 순수 PK 읽기.
--   LO=$(docker exec fillmap-postgres psql -U user -d fillmap -tAc "SELECT min(id) FROM videos WHERE grid_id='bench164_grid'")
--   HI=$(docker exec fillmap-postgres psql -U user -d fillmap -tAc "SELECT max(id) FROM videos WHERE grid_id='bench164_grid'")
--   printf '%s\n' '\set id random(1, 28)' 'SELECT r.region_code FROM bench_msg164_pts p JOIN regions r ON ST_Covers(r.boundary_geom, ST_SetSRID(ST_MakePoint(p.lon,p.lat),4326)::geography) WHERE p.rn = :id LIMIT 1;' | docker exec -i fillmap-postgres sh -c 'cat > /tmp/a.sql'
--   printf '%s\n' "\\set id random($LO, $HI)" 'SELECT region_code FROM videos WHERE id = :id;' | docker exec -i fillmap-postgres sh -c 'cat > /tmp/b.sql'
--   docker exec fillmap-postgres pgbench -U user -d fillmap -n -M prepared -c 1 -j 1 -T 8 -r -l --log-prefix=/tmp/pgb_a -f /tmp/a.sql
--   docker exec fillmap-postgres pgbench -U user -d fillmap -n -M prepared -c 1 -j 1 -T 8 -r -l --log-prefix=/tmp/pgb_b -f /tmp/b.sql
--
--   # p50/p95/p99 — pgbench -l 로그 3번째 컬럼(트랜잭션 레이턴시, µs)을 호스트에서 정렬·백분위:
--   for x in a b; do docker exec fillmap-postgres sh -c "cat /tmp/pgb_${x}.*" | awk '{print $3}' | sort -n | awk -v arm=$x '{v[NR]=$1} END{n=NR; printf "arm(%s) p50=%.3f p95=%.3f p99=%.3f max=%.3f ms (n=%d)\n", arm, v[int(n*0.5)]/1000, v[int(n*0.95)]/1000, v[int(n*0.99)]/1000, v[n]/1000, n}'; done
--
--   # 3) 정리 — (1)에서 새로 만든 것만 회수. DELETE WHERE + ANALYZE 로 통계까지 원복.
--   docker exec -i fillmap-postgres psql -U user -d fillmap <<'SQL'
--   DELETE FROM videos     WHERE grid_id = 'bench164_grid';
--   DELETE FROM user_grids WHERE grid_id = 'bench164_grid';
--   DELETE FROM grids      WHERE grid_id = 'bench164_grid';
--   DELETE FROM users      WHERE email   = 'bench-msg164@bench.local';
--   DROP TABLE IF EXISTS bench_msg164_pts;
--   ANALYZE videos;   -- reltuples 를 0 으로 되돌린다(삭제만으론 낡은 통계 잔류)
-- SQL
--   docker exec fillmap-postgres sh -c 'rm -f /tmp/a.sql /tmp/b.sql /tmp/pgb_a.* /tmp/pgb_b.*'
-- ============================================================================
