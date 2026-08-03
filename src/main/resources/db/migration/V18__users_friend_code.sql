-- ============================================================================
-- V18: users.friend_code — 고정 친구 코드 (MSG-185 §D1)
--
-- 친구 추가 방식 = 고정 친구 코드 확정 (2026-08-03, MSG-172): 닉네임은 중복 허용(MSG-203)이라
-- 검색으로 상대를 특정할 수 없다. 코드는 혼동 문자 I·O·0·1 제외 32종 8자 — 탐색 공간 32^8 ≈ 1.1조.
--
-- 4단 구조: 컬럼 추가(nullable) → 기존 가입자 백필 → NOT NULL 승격 → UNIQUE.
-- 신규 가입 코드는 앱(User 생성자, SecureRandom)이 만들고, 이 백필은 기존 행 한정이라
-- random() 이 CSPRNG 가 아니어도 무방하다. 백필 충돌 검출은 마지막 UNIQUE 몫 —
-- 수십 행 기준 확률 ~1e-9, 만에 하나 실패하면 마이그레이션 재실행으로 수렴한다 (V5 백필 관례).
-- ============================================================================

-- 1) 컬럼 추가 — 백필 전이라 nullable 로 시작한다.
ALTER TABLE users ADD COLUMN friend_code VARCHAR(8);

-- 2) 기존 가입자 백필 — 상관 서브쿼리로 행마다 새 코드를 생성한다.
--    WHERE u.id IS NOT NULL 은 상관 강제 장치: 이 조건이 없으면 서브쿼리가 외부 행을 참조하지
--    않아 PostgreSQL 이 InitPlan 으로 1회만 평가 → 전 행 동일 코드 → 4단의 UNIQUE 가 실패한다.
UPDATE users u SET friend_code = (
	SELECT string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', floor(random() * 32)::int + 1, 1), '')
	FROM generate_series(1, 8)
	WHERE u.id IS NOT NULL);

-- 3) NOT NULL 승격 — 백필 완료 후에만 가능하다.
ALTER TABLE users ALTER COLUMN friend_code SET NOT NULL;

-- 4) 전역 유일 제약 — 랜덤 충돌의 백스톱 (네이밍은 uq_users_email 관례).
ALTER TABLE users ADD CONSTRAINT uq_users_friend_code UNIQUE (friend_code);

COMMENT ON COLUMN users.friend_code IS '고정 친구 코드(혼동 문자 제외 32종 8자, 전역 유일) — MSG-185';
