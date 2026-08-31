-- 행사 등재 v2 (MSG-496): users.role 에 ORG(행사 운영자) 추가.
-- V1 의 chk_users_role 이 USER, ADMIN 만 허용하고 있어 제약을 재정의한다.
-- 기존 행은 값 변경이 없어 새 CHECK 을 그대로 통과한다.
ALTER TABLE users DROP CONSTRAINT chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ORG', 'ADMIN'));
