-- MSG-310: 카카오 가입 이메일 미수집 — users.email NOT NULL 해제.
-- 카카오 로그인에서 이메일 동의항목을 쓰지 않기로 확정(비즈 앱 전환 회피, 2026-08-03) — 카카오 유저는 email NULL.
-- 이메일 가입(로컬 테스트용)은 요청 DTO 검증(@NotBlank @Email)이 계속 필수를 강제하므로 무영향.
-- uq_users_email UNIQUE 는 유지 — PostgreSQL UNIQUE 는 NULL 중복을 허용해 email 있는 유저 간 중복만 계속 차단한다.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
