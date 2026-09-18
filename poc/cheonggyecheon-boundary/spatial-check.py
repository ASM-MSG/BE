"""PostGIS experiment; all synthetic tables are TEMP and the transaction rolls back.

Requires the existing fillmap-postgres container and its PostGIS-enabled fillmap DB.
No application tables are read or written. Only Python stdlib + container psql.
"""
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent
source = json.loads((root / "centerline.geojson").read_text())
constants = (root.parents[1] / "src/main/java/com/msg/fillmap/grid/GridConstants.java").read_text()
crs = "".join(re.findall(r'"([^"]+)"', constants.split("CRS_DEF_EPSG5179 =", 1)[1].split(";", 1)[0]))
assert "CELL_SIZE_METERS = 100;" in constants
def literal(value):
    return "'" + value.replace("'", "''") + "'"

sql = f"""
BEGIN;
SET LOCAL statement_timeout = '120s';
SET LOCAL jit = off;
CREATE TEMP TABLE findings(name text, result jsonb);
INSERT INTO findings VALUES ('environment', jsonb_build_object(
 'postgres',version(),'postgis',postgis_full_version(),'rows',100000,
 'fixture','deterministic synthetic lattice, no real videos','crs',{literal(crs)}));
CREATE TEMP TABLE line AS SELECT ST_SetSRID(ST_Transform(
 ST_SetSRID(ST_GeomFromGeoJSON({literal(json.dumps(source['geometry']))}),4326),{literal(crs)}),5179) geom;
CREATE TEMP TABLE venue AS SELECT ST_Buffer(geom,20,'quad_segs=16') projected,
 ST_Transform(ST_Buffer(geom,20,'quad_segs=16'),{literal(crs)},4326) geom FROM line;
CREATE TEMP TABLE cells AS
 WITH b AS (SELECT floor(ST_XMin(projected)/100)::int x0, floor(ST_XMax(projected)/100)::int x1,
 floor(ST_YMin(projected)/100)::int y0, floor(ST_YMax(projected)/100)::int y1 FROM venue)
 SELECT y||'_'||x grid_id, ST_MakeEnvelope(x*100,y*100,(x+1)*100,(y+1)*100,5179) geom
 FROM b, generate_series(x0,x1) x, generate_series(y0,y1) y, venue v
 WHERE ST_Intersects(v.projected,ST_MakeEnvelope(x*100,y*100,(x+1)*100,(y+1)*100,5179));
CREATE UNIQUE INDEX ON cells(grid_id);
CREATE TEMP TABLE sample_videos (
 id int PRIMARY KEY, grid_id text, geom geography(Point,4326),
 status text, visibility text, processing_status text, created_at timestamp, user_id int);
INSERT INTO sample_videos
 SELECT i, floor(ST_Y(p)/100)::int||'_'||floor(ST_X(p)/100)::int,
 ST_Transform(p,{literal(crs)},4326)::geography,
 CASE WHEN i%17=0 THEN 'DELETED' ELSE 'ACTIVE' END,
 CASE WHEN i%19=0 THEN 'PRIVATE' ELSE 'PUBLIC' END,
 CASE WHEN i%23=0 THEN 'ENCODING' ELSE 'READY' END,
 timestamp '2026-09-13 12:00:00' - (i%48)*interval '1 hour', i%101
 FROM generate_series(1,100000) i CROSS JOIN line
 CROSS JOIN LATERAL (SELECT ST_SetSRID(ST_MakePoint(
 ST_XMin(line.geom)-500 + ((i-1)%500)*17.3,
 ST_YMin(line.geom)-500 + floor((i-1)/500.0)*17.1),5179) p) coords;
-- Mirrors the relevant leading key and public-ready predicate of the existing index.
CREATE INDEX sample_grid_public ON sample_videos(grid_id, created_at DESC)
 WHERE status='ACTIVE' AND visibility='PUBLIC' AND processing_status='READY';
ANALYZE sample_videos;
ANALYZE cells;
CREATE TEMP VIEW eligible AS SELECT * FROM sample_videos
 WHERE status='ACTIVE' AND visibility='PUBLIC' AND processing_status='READY';
CREATE TEMP VIEW exact AS SELECT id FROM eligible v, venue b WHERE ST_Covers(b.geom,v.geom::geometry);
CREATE TEMP VIEW candidate AS SELECT v.* FROM eligible v JOIN cells c USING(grid_id);
CREATE TEMP VIEW filtered AS SELECT id FROM candidate v, venue b WHERE ST_Covers(b.geom,v.geom::geometry);
INSERT INTO findings SELECT 'membership',jsonb_build_object(
 'cells_20m',(SELECT count(*) FROM cells),'eligible',(SELECT count(*) FROM eligible),
 'grid_candidates',(SELECT count(*) FROM candidate),'exact',(SELECT count(*) FROM exact),
 'grid_only_false_positives',(SELECT count(*) FROM candidate WHERE id NOT IN (SELECT id FROM exact)),
 'two_stage_symmetric_difference',(SELECT count(*) FROM
 ((SELECT id FROM exact EXCEPT SELECT id FROM filtered) UNION ALL
 (SELECT id FROM filtered EXCEPT SELECT id FROM exact)) d),
 'cells_40m',(SELECT count(*) FROM line l,
 generate_series(floor((ST_XMin(l.geom)-40)/100)::int,floor((ST_XMax(l.geom)+40)/100)::int) x,
 generate_series(floor((ST_YMin(l.geom)-40)/100)::int,floor((ST_YMax(l.geom)+40)/100)::int) y
 WHERE ST_Intersects(ST_Buffer(l.geom,40),ST_MakeEnvelope(x*100,y*100,(x+1)*100,(y+1)*100,5179))));
-- Model lazy grids: only half of the full cells existed at publication time.
INSERT INTO findings SELECT 'lazy_grid_snapshot',jsonb_build_object(
 'missed_when_half_cells_absent',count(*)) FROM exact e JOIN sample_videos v USING(id)
 WHERE (split_part(v.grid_id,'_',2)::int)%2=0;
INSERT INTO findings SELECT 'center_coordinate_loss',jsonb_build_object(
 'inside_samples_misclassified_by_own_cell_center',count(*))
 FROM exact e JOIN sample_videos v USING(id),venue b
 WHERE NOT ST_Covers(b.geom,ST_Transform(ST_SetSRID(ST_MakePoint(
 (split_part(v.grid_id,'_',2)::int+0.5)*100,
 (split_part(v.grid_id,'_',1)::int+0.5)*100),5179),{literal(crs)},4326));
CREATE TEMP TABLE widened_venue AS
 SELECT ST_Transform(ST_Buffer(geom,40),{literal(crs)},4326) geom FROM line;
INSERT INTO findings SELECT 'boundary_revision',jsonb_build_object(
 'missed_after_20_to_40m_without_rebuild',count(*)) FROM eligible v,widened_venue b
 WHERE ST_Covers(b.geom,v.geom::geometry)
 AND NOT EXISTS(SELECT 1 FROM cells c WHERE c.grid_id=v.grid_id);
CREATE TEMP TABLE measurements(name text, ms double precision, plan jsonb);
CREATE FUNCTION pg_temp.measure(label text, query text) RETURNS void LANGUAGE plpgsql AS $body$
DECLARE p jsonb;
BEGIN
 FOR i IN 1..35 LOOP
  EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '||query INTO p;
  IF i>5 THEN INSERT INTO measurements VALUES(label,(p->0->>'Execution Time')::float,p); END IF;
 END LOOP;
END $body$;
SELECT pg_temp.measure('direct_existing_indexes','SELECT id FROM exact');
SELECT pg_temp.measure('grid_then_exact_existing_index','SELECT id FROM filtered');
-- Optional additive expression index, not a migration or change to real videos.
CREATE INDEX sample_geom_gist ON sample_videos USING gist ((geom::geometry));
ANALYZE sample_videos;
SELECT pg_temp.measure('direct_added_expression_gist','SELECT id FROM exact');
INSERT INTO findings SELECT 'timing_'||name,jsonb_build_object(
 'samples',count(*),'p50_ms',percentile_cont(0.5) WITHIN GROUP(ORDER BY ms),
 'p95_ms',percentile_cont(0.95) WITHIN GROUP(ORDER BY ms),'max_ms',max(ms))
 FROM measurements GROUP BY name;
INSERT INTO findings SELECT DISTINCT ON(name) 'plan_'||name,plan FROM measurements ORDER BY name,ms;
-- Geometry import semantics, all fixture coordinates are in the local plane.
WITH fixtures AS (SELECT
 ST_GeomFromText('POLYGON((0 0,10 0,10 10,0 10,0 0),(3 3,3 7,7 7,7 3,3 3))') hole,
 ST_GeomFromText('POLYGON((0 0,10 10,0 10,10 0,0 0))') crossed,
 ST_GeomFromText('MULTIPOLYGON(((0 0,2 0,2 2,0 2,0 0)),((8 0,10 0,10 2,8 2,8 0)))') multi)
INSERT INTO findings SELECT 'geometry',jsonb_build_object(
 'hole_valid',ST_IsValid(hole),'hole_center_excluded',NOT ST_Covers(hole,ST_Point(5,5)),
 'outer_edge_covered',ST_Covers(hole,ST_Point(0,5)),
 'contains_excludes_edge',NOT ST_Contains(hole,ST_Point(0,5)),
 'multipolygon_valid',ST_IsValid(multi),'gap_excluded',NOT ST_Covers(multi,ST_Point(5,1)),
 'self_cross_rejected',NOT ST_IsValid(crossed),
 'empty_detected',ST_IsEmpty(ST_GeomFromText('POLYGON EMPTY')),
 'geojson_roundtrip',ST_Equals(hole,ST_SetSRID(ST_GeomFromGeoJSON(ST_AsGeoJSON(hole)),0)),
 'circle_valid',ST_IsValid(ST_Buffer(ST_Point(0,0),20)),
 'bridge_height_ignored_by_2d',ST_Equals(ST_GeomFromText('POINT Z (5 5 0)'),ST_GeomFromText('POINT Z (5 5 10)')),
 'venue_valid',(SELECT ST_IsValid(geom) FROM venue)) FROM fixtures;
-- Ranking is recomputed from filtered rows; a hidden video must disappear immediately.
CREATE TEMP VIEW ranking AS SELECT c.grid_id,count(*) n FROM candidate c,venue b
 WHERE ST_Covers(b.geom,c.geom::geometry)
 AND c.created_at > timestamp '2026-09-12 12:00:00'
 AND c.created_at <= timestamp '2026-09-13 12:00:00'
 AND c.user_id<>7 GROUP BY c.grid_id;
CREATE TEMP TABLE before_rank AS SELECT coalesce(sum(n),0)::int total FROM ranking;
UPDATE sample_videos SET status='BLINDED' WHERE id=(SELECT min(c.id) FROM candidate c,venue b
 WHERE ST_Covers(b.geom,c.geom::geometry)
 AND c.created_at > timestamp '2026-09-12 12:00:00' AND c.user_id<>7);
INSERT INTO findings SELECT 'ranking',jsonb_build_object(
 'before',(SELECT total FROM before_rank),'after_hide',coalesce(sum(n),0),
 'blocked_user_fixture',7,'window_hours',24) FROM ranking;
SELECT jsonb_object_agg(name,result) FROM findings;
ROLLBACK;
"""
run = subprocess.run(["docker", "exec", "-i", "fillmap-postgres", "psql", "-X", "-qAt",
                      "-v", "ON_ERROR_STOP=1", "-U", "user", "-d", "fillmap"],
                     input=sql, text=True, capture_output=True, timeout=240)
if run.returncode:
    raise RuntimeError(run.stderr)
result = json.loads(next(line for line in run.stdout.splitlines() if line.startswith('{"')))
assert all(result["geometry"].values()), result["geometry"]
assert result["membership"]["two_stage_symmetric_difference"] == 0
assert result["membership"]["grid_only_false_positives"] > 0
assert result["lazy_grid_snapshot"]["missed_when_half_cells_absent"] > 0
assert result["center_coordinate_loss"]["inside_samples_misclassified_by_own_cell_center"] > 0
assert result["boundary_revision"]["missed_after_20_to_40m_without_rebuild"] > 0
assert result["ranking"]["before"] - result["ranking"]["after_hide"] == 1
(root / "spatial-results.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
print(json.dumps({k:v for k,v in result.items() if not k.startswith('plan_')}, indent=2, ensure_ascii=False))
print("PASS: spatial fixtures, exact ID equivalence, lazy-grid/center-point failure reproduced; ROLLBACK")
