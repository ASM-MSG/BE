-- ============================================================================
-- V19: friendships 대칭(무방향) 쌍 UNIQUE — 상호 요청 레이스의 DB 백스톱 (MSG-185, Codex 리뷰 반영)
--
-- 복합 PK (requester_id, addressee_id)는 같은 방향만 막는다 — 서로 동시에 요청하는 레이스에서
-- A→B 와 B→A 가 모두 INSERT 되면 "동일 쌍 최대 1행" 불변식이 깨지고, 이후 그 쌍의 양방향 조회
-- (findPair, Optional 단건 계약)가 2행을 만나 영구 실패한다(수동 정리 전까지 요청·삭제 전부 500).
-- 발생 확률이 아니라 오염의 영속성이 문제라 앱 검증만으로 두지 않고 DB 가 막는다.
--
-- LEAST/GREATEST 정규화로 방향 무관 쌍을 유일화한다 — 표현식이라 제약(CONSTRAINT)이 아닌
-- 유니크 인덱스로 건다. 레이스의 늦은 INSERT 는 유니크 위반으로 실패(500 1회) — 사용자가
-- 재시도하면 이미 생긴 역방향 PENDING 을 보고 자동 수락으로 수렴한다 (스펙 §D4 FR-8 경로).
-- ============================================================================

CREATE UNIQUE INDEX uq_friendships_pair
	ON friendships (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id));
