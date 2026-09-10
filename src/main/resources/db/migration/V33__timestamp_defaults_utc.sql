-- V33__timestamp_defaults_utc.sql
-- MSG-379 D8: naive TIMESTAMP 컬럼의 DEFAULT 를 세션 타임존과 무관한 UTC 벽시계 식으로 바꾼다.
--
-- 배경. 이 프로젝트는 "타임존 없는 LocalDateTime 값은 전부 UTC 벽시계"라는 규약으로 통일돼 있다
-- (MSG-376). 그런데 CURRENT_TIMESTAMP 는 타임존이 붙은 값(timestamptz)이라, 타임존 없는 컬럼에
-- 대입되는 순간 그 세션의 타임존으로 변환된다. 그래서 같은 DEFAULT 가 UTC 로 뜬 서버 앱에서는
-- 맞고, KST 로 뜬 로컬 앱이나 psql 직접 세션에서는 9시간 앞선 값을 저장했다.
-- statement_timestamp() AT TIME ZONE 'utc' 는 세션 타임존과 무관하게 항상 UTC 벽시계를 낸다
-- (이미 이 레포 native 쿼리들의 시각 기록 관례다).
--
-- 범위. INSERT 에서 시각 컬럼을 생략해 DEFAULT 에 맡기는 활성 경로가 6곳 있는데, 그 6곳을 각각
-- 고치는 대신 DEFAULT 식 자체를 여기서 한 번에 바꾼다. 지금 안 쓰이는 컬럼까지 균일하게 바꾸는
-- 것은 나중에 그 컬럼을 쓰는 경로가 생겼을 때 같은 함정을 다시 밟지 않게 하려는 것이다.
--
-- 영향. DEFAULT 정의만 바꾸므로 기존 행의 값과 컬럼 타입은 그대로고, 테이블 재작성 없이 즉시
-- 끝난다. 이 마이그레이션 이후의 INSERT 부터 적용된다 (소급 없음).

ALTER TABLE users              ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE grids              ALTER COLUMN first_seen_at      SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');
ALTER TABLE grids              ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE videos             ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE user_grids         ALTER COLUMN first_collected_at SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');
ALTER TABLE user_grids         ALTER COLUMN last_uploaded_at   SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE region_stats       ALTER COLUMN updated_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE push_tokens        ALTER COLUMN last_used_at       SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE friendships        ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE streaks            ALTER COLUMN updated_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE badges             ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE user_badges        ALTER COLUMN earned_at          SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE likes              ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE reports            ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE sponsor_ads        ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE missions           ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');
ALTER TABLE user_missions      ALTER COLUMN completed_at       SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE notifications      ALTER COLUMN created_at         SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE notification_opt_outs
	ALTER COLUMN created_at SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');

ALTER TABLE search_keyword_daily_counts
	ALTER COLUMN created_at SET DEFAULT (statement_timestamp() AT TIME ZONE 'utc');
