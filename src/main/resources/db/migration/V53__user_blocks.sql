-- 사용자 차단 (MSG-569). friendships 의 BLOCKED 상태를 쓰지 않는 이유: V19 대칭 쌍 유니크가
-- "친구이면서 차단"을 한 행에 담을 수 없고, 차단은 방향이 있는 관계(A→B 와 B→A 가 별개 행)다.
CREATE TABLE user_blocks (
	blocker_id BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	blocked_id BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	created_at TIMESTAMP NOT NULL,
	PRIMARY KEY (blocker_id, blocked_id),
	CONSTRAINT chk_user_blocks_self CHECK (blocker_id <> blocked_id)
);

-- 피차단자 기준 역조회 (상호 비노출의 "상대가 나를 차단" 방향, 탈퇴 CASCADE 의 blocked_id FK 검사).
CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_id, blocker_id);
