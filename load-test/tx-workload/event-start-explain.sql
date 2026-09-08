-- psql -v ON_ERROR_STOP=1 -f event-start-explain.sql
-- 별도 감사 DB에서 실제 5만 행 INSERT 계획을 실행하고 합성 행은 롤백한다.
BEGIN;
DO $$ BEGIN
    IF NOT starts_with(current_database(), 'fillmap_tx_bench_') THEN
        RAISE EXCEPTION '별도 감사 DB에서만 실행합니다';
    END IF;
END $$;

SELECT 'txexplain-' || txid_current() AS tag \gset
INSERT INTO event_series(series_key, name)
VALUES (:'tag', '계획 검증 행사') RETURNING id AS series_id \gset
INSERT INTO event_occurrences(event_series_id, occurrence_key, title, city_name,
    starts_at, ends_at, visible_from, min_grid_y, max_grid_y, min_grid_x, max_grid_x)
VALUES (:series_id, :'tag', '계획 검증 행사', '부산',
    '2026-10-06 01:00', '2026-10-16 01:00', '2026-09-22 01:00', 90000, 90001, 90500, 90501)
RETURNING id AS occurrence_id \gset
WITH ids AS MATERIALIZED (
    SELECT nextval(pg_get_serial_sequence('users', 'id')) AS id
    FROM generate_series(1, 50000)
)
INSERT INTO users(id, provider, email, password_hash, nickname, friend_code)
SELECT id, 'LOCAL', :'tag' || '-' || id || '@example.invalid', 'benchmark-unused-hash',
    '계획 검증 사용자', upper(lpad(to_hex(id), 8, '0'))
FROM ids;
INSERT INTO event_notification_subscriptions(user_id, event_occurrence_id, created_at)
SELECT id, :occurrence_id, '2026-10-05 01:00' FROM users
WHERE email LIKE :'tag' || '-%@example.invalid';
ANALYZE users;
ANALYZE event_notification_subscriptions;
ANALYZE notifications;

EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO notifications (user_id, category, event_key, title, body, created_at)
SELECT s.user_id, 'EVENT', 'EVENT_START:' || :'tag' || ':1791248400', '계획 검증 행사',
    '행사가 시작됐어요. 현장 영상을 올려보세요', statement_timestamp() AT TIME ZONE 'UTC'
FROM event_notification_subscriptions s
WHERE s.event_occurrence_id = :occurrence_id
  AND s.created_at <= TIMESTAMP '2026-10-06 01:00'
ON CONFLICT (user_id, event_key) DO NOTHING;
ROLLBACK;
