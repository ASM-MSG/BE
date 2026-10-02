INSERT INTO user_grids (user_id, grid_id, video_count, first_collected_at, last_uploaded_at)
SELECT (SELECT id FROM users WHERE email='bench@fillmap.kr'), g.grid_id, 1, now() at time zone 'UTC', now() at time zone 'UTC'
FROM grids g WHERE (g.grid_y + g.grid_x) % 3 = 0
ON CONFLICT DO NOTHING;
VACUUM ANALYZE grids; VACUUM ANALYZE user_grids; VACUUM ANALYZE videos; VACUUM ANALYZE users;
SELECT count(*) AS bench_user_grids FROM user_grids ug JOIN users u ON u.id=ug.user_id WHERE u.email='bench@fillmap.kr';
