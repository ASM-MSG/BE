-- 행사 등재 v2 (MSG-498): 행사 운영자 등록 신청. 심사·승인 반영은 MSG-500.
-- 격자 컬럼은 grids 를 FK 하지 않는다 (lazy insert 전략상 행 존재 비보장, V39 선례).

CREATE SEQUENCE event_submission_no_seq;

CREATE TABLE event_submissions (
    id                  BIGSERIAL PRIMARY KEY,
    submission_no       VARCHAR(20) NOT NULL UNIQUE,   -- "FM-2026-0007"
    user_id             BIGINT NOT NULL REFERENCES users(id),  -- 신청자 (role=ORG)
    type                VARCHAR(20) NOT NULL,          -- EventSubmissionType
    status              VARCHAR(20) NOT NULL,          -- EventSubmissionStatus
    title               VARCHAR(100) NOT NULL,         -- 축제명 / 팝업명
    organizer_name      VARCHAR(100) NOT NULL,         -- 주최 기관 / 브랜드·운영사
    starts_on           DATE NOT NULL,                 -- KST 날짜 라벨 (기간)
    ends_on             DATE NOT NULL,
    operating_hours     VARCHAR(100),                  -- POPUP 전용 (event_locations 선례 타입)
    program_description TEXT,                          -- FESTIVAL 전용, 최소 10자 (#100)
    description         TEXT NOT NULL,                 -- 소개, 최소 10자 (#100)
    image_key           VARCHAR(255) NOT NULL,         -- 확정 프리픽스 S3 키
    created_at          TIMESTAMP NOT NULL,            -- UTC (MSG-376 체계, 앱이 Clock 으로 기록)
    updated_at          TIMESTAMP NOT NULL,

    -- 이벤트 참여형(MSG-502)은 값 추가 + 부모 이벤트 컬럼 추가로 확장한다. 선반영하지 않는다.
    CONSTRAINT chk_event_sub_type   CHECK (type IN ('FESTIVAL', 'POPUP')),
    CONSTRAINT chk_event_sub_status CHECK (status IN ('IN_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_event_sub_period CHECK (starts_on <= ends_on)
);
CREATE INDEX idx_event_submissions_user ON event_submissions(user_id);

CREATE TABLE event_submission_locations (
    id                     BIGSERIAL PRIMARY KEY,
    event_submission_id    BIGINT NOT NULL REFERENCES event_submissions(id) ON DELETE CASCADE,
    display_order          INTEGER NOT NULL,           -- 요청 배열 순서, 1부터. 위치 이름 없음 (#102)
    representative_grid_id VARCHAR(20) NOT NULL        -- 서버 계산 (FR-9)
);
CREATE INDEX idx_event_sub_locations_submission ON event_submission_locations(event_submission_id);

-- 제출 원본 사각형. 승인 시 event_locations·event_location_grids 로 전개하는 쪽은 MSG-500.
CREATE TABLE event_submission_location_rects (
    event_submission_location_id BIGINT NOT NULL
        REFERENCES event_submission_locations(id) ON DELETE CASCADE,
    min_grid_y INTEGER NOT NULL,
    max_grid_y INTEGER NOT NULL,
    min_grid_x INTEGER NOT NULL,
    max_grid_x INTEGER NOT NULL,

    CONSTRAINT chk_event_sub_rect_y CHECK (min_grid_y <= max_grid_y),
    CONSTRAINT chk_event_sub_rect_x CHECK (min_grid_x <= max_grid_x)
);
CREATE INDEX idx_event_sub_rects_location
    ON event_submission_location_rects(event_submission_location_id);

CREATE TABLE event_submission_status_history (
    id                  BIGSERIAL PRIMARY KEY,
    event_submission_id BIGINT NOT NULL REFERENCES event_submissions(id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL,
    reason_codes        VARCHAR(30),                   -- 쉼표 연결 "AREA,INFO" (쓰기는 MSG-500)
    reason_text         TEXT,
    created_at          TIMESTAMP NOT NULL,

    CONSTRAINT chk_event_sub_hist_status CHECK (status IN ('IN_REVIEW', 'APPROVED', 'REJECTED')),
    -- 반려 행에는 사유 두 벌이 반드시 있고, 그 외 상태 행에는 없다 (FR-19 저장 계약)
    CONSTRAINT chk_event_sub_hist_reason_required
        CHECK (status <> 'REJECTED' OR (reason_codes IS NOT NULL AND reason_text IS NOT NULL)),
    CONSTRAINT chk_event_sub_hist_reason_absent
        CHECK (status = 'REJECTED' OR (reason_codes IS NULL AND reason_text IS NULL))
);
CREATE INDEX idx_event_sub_history_submission
    ON event_submission_status_history(event_submission_id);
