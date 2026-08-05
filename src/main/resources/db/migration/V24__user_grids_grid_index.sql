-- V24__user_grids_grid_index.sql
-- 격자 → 점령 사용자 역조회 (MSG-181 핫구역 진입 통지). PK (user_id, grid_id)는 선두가
-- user_id 라 못 받치고, FK 는 인덱스 자동 생성이 없다 (V1 idx_push_tokens_user 선례).
CREATE INDEX idx_user_grids_grid ON user_grids (grid_id);
