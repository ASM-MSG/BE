-- 애플 로그인 (MSG-594). CHECK 개정과 토큰 보관 컬럼 추가. 롤백은 역방향 ALTER 두 문장.
ALTER TABLE users DROP CONSTRAINT chk_users_provider;
ALTER TABLE users ADD CONSTRAINT chk_users_provider CHECK (provider IN ('LOCAL', 'KAKAO', 'APPLE'));

-- 애플 리프레시 토큰의 AES-256-GCM 암호문(Base64). 탈퇴 시 취소 호출에만 쓴다. APPLE 이 아니거나
-- dev 모의 로그인으로 만든 계정은 NULL.
ALTER TABLE users ADD COLUMN apple_refresh_token_encrypted TEXT;
