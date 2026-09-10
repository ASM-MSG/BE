-- 행사 운영자 계정 보안 (MSG-497).
-- 1) 첫 로그인 비밀번호 강제 변경 플래그. 기존 행은 전부 false(강제 없음)로 시작한다.
ALTER TABLE users ADD COLUMN password_must_change BOOLEAN NOT NULL DEFAULT FALSE;
-- 2) 담당자 연락처 (FR-23). ORG 계정만 쓰는 값이라 NULL 허용.
ALTER TABLE users ADD COLUMN contact_phone VARCHAR(20);

-- 3) 아이디(공식 이메일) 변경 요청 큐. 접수는 MSG-497, 관리자 노출·처리는 MSG-499·500.
CREATE TABLE org_email_change_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    requested_email VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_org_email_change_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
-- 사용자당 대기 요청은 한 건. 재요청은 이 행의 갱신이다 (부분 유니크 인덱스가 강제,
-- §6 의 ON CONFLICT 대상이기도 하다).
CREATE UNIQUE INDEX uq_org_email_change_requests_pending
    ON org_email_change_requests (user_id) WHERE status = 'PENDING';
