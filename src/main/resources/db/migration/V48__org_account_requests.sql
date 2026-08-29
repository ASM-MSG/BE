-- 행사 운영자 계정 발급 (MSG-499).
-- 1) 기관명. 발급 계정(role ORG)에만 값이 있고 그 외 역할은 항상 NULL 이다 (contact_phone 과 같은 방식).
ALTER TABLE users ADD COLUMN org_name VARCHAR(100);

-- 2) 계정 발급 요청 큐 (FR-6). 비로그인 공개 폼이 쓰고 관리자가 검토한다.
--    created_at 은 최초 접수 시각(보존), updated_at 은 마지막 접수 시각이며 관리자 심사의
--    낙관적 검증 토큰이다 (approve/reject 가 에코 대조).
CREATE TABLE org_account_requests (
    id BIGSERIAL PRIMARY KEY,
    org_name VARCHAR(100) NOT NULL,
    contact_name VARCHAR(20) NOT NULL,
    contact_phone VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    event_name VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reject_reason VARCHAR(500),
    issued_user_id BIGINT REFERENCES users (id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    CONSTRAINT chk_org_account_requests_status
        CHECK (status IN ('PENDING', 'ISSUED', 'REJECTED'))
);
-- 이메일당 대기 요청은 한 건. 재접수는 그 행의 갱신이다 (접수 UPSERT 의 ON CONFLICT 대상).
CREATE UNIQUE INDEX uq_org_account_requests_pending
    ON org_account_requests (email) WHERE status = 'PENDING';
-- 큐 목록(상태 필터 + 마지막 접수 최신순)용.
CREATE INDEX idx_org_account_requests_status_updated
    ON org_account_requests (status, updated_at DESC);
