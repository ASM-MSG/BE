-- 행사 등재 심사 (MSG-500): 승인 번호와 산출물 링크, 노출 중지 기록, 아이디 변경 요청 처리 흔적.
-- V50 은 MSG-502(참여형 확장) 예약이라 이 티켓은 V51 을 쓴다.

CREATE SEQUENCE event_submission_approval_no_seq;

ALTER TABLE event_submissions
    ADD COLUMN approval_no          VARCHAR(20) UNIQUE,   -- "APR-2026-0001", 승인 시 부여
    ADD COLUMN published_mission_id BIGINT REFERENCES missions(id),  -- NO ACTION: 링크 고아 차단
    ADD COLUMN unpublished_at       TIMESTAMP,            -- UTC (MSG-376 체계)
    ADD COLUMN unpublish_reason     TEXT;

-- 미승인 행에 승인 흔적이 남지 않는다. 승인 행은 승인 번호가 반드시 있다.
ALTER TABLE event_submissions
    ADD CONSTRAINT chk_event_sub_approval
        CHECK ((status = 'APPROVED') = (approval_no IS NOT NULL)),
    ADD CONSTRAINT chk_event_sub_unpublish_pair
        CHECK ((unpublished_at IS NULL) = (unpublish_reason IS NULL)),
    ADD CONSTRAINT chk_event_sub_unpublish_approved
        CHECK (unpublished_at IS NULL OR status = 'APPROVED');

-- 노출 숨김 (D-3). NULL = 노출 중, 기존 행 전부 NULL 이라 시드 산출물 동작 불변.
ALTER TABLE missions        ADD COLUMN hidden_at TIMESTAMP;
ALTER TABLE event_locations ADD COLUMN hidden_at TIMESTAMP;

-- 참여형 승인분의 참여 속성 (D-8 복사 매핑, PRD v2.2 확정 1 "위치에 붙는 부가 정보").
-- 전부 nullable: 시드 위치는 전부 NULL 이라 기존 행사 조회 동작이 불변이다.
ALTER TABLE event_locations
    ADD COLUMN organizer_name       VARCHAR(100),
    ADD COLUMN description          TEXT,
    ADD COLUMN starts_on            DATE,          -- 공개 기간 표기 정보 (KST 날짜 라벨)
    ADD COLUMN ends_on              DATE,
    ADD COLUMN participation_method TEXT,
    ADD COLUMN image_key            VARCHAR(255);  -- 공개 프리픽스 사본 키 (D-6)

-- 아이디 변경 요청 심사 결과 (D-13). MSG-497 접수 스키마에는 처리 흔적 자리가 없었다.
ALTER TABLE org_email_change_requests
    ADD COLUMN processed_at  TIMESTAMP,
    ADD COLUMN reject_reason TEXT;
ALTER TABLE org_email_change_requests
    ADD CONSTRAINT chk_org_email_change_processed
        CHECK ((status = 'PENDING') = (processed_at IS NULL)),
    -- 반려면 사유 필수, 반려 아니면 사유 없음. 양방향 동치식이어야 한다: 반쪽 OR 식은
    -- 반려 행의 사유 NULL 을 통과시킨다.
    ADD CONSTRAINT chk_org_email_change_reject_reason
        CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL));
