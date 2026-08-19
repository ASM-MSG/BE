-- 알림함 읽음 상태 (MSG-434). NULL = 안읽음. 기존 행은 전부 안읽음으로 시작한다 (D-1).
ALTER TABLE notifications ADD COLUMN read_at TIMESTAMP;
