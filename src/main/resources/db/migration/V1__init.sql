-- ============================================================================
-- FillMap V1 — Initial Schema (v6 설계 반영)
-- PostgreSQL 16 + PostGIS 3.4
--
-- v6 변경 요약 (멘토 피드백 2026-07-12 반영, 상세: MSG-66 · Confluence ADR):
--   1. region-grid 분리: grids.region_code 제거, videos.region_code 추가
--      (행정동 레이블은 업로드 시점에 영상 좌표로 1회 판정 — 조회 경로 geospatial 금지)
--   2. 복합 PK 전환: user_grids, likes, user_badges, friendships (서러게이트 id 제거)
--      push_tokens는 fcm_token 자연키 승격
--   3. grids에 grid_y, grid_x INT 추가 (뷰포트 정수 범위 스캔용)
--      center_geom/bbox_geom + GIST는 벤치마크 후 제거 검토
--   4. ENUM 타입 → VARCHAR + CHECK (개발 중 값 변경 유연성)
--   5. users.grid_color 추가, videos.view_count 추가, duration_sec 최대 30초
--
-- ⚠️ V1 재작성이므로 기존 로컬 DB는 flyway clean 후 재마이그레이션 필요
-- ============================================================================

-- ============ Extensions ============
CREATE EXTENSION IF NOT EXISTS postgis;

-- ============================================================================
-- users
-- ============================================================================
CREATE TABLE users (
	id                BIGSERIAL PRIMARY KEY,
	provider          VARCHAR(20)  NOT NULL,
	oid               VARCHAR(64),
	email             VARCHAR(255) NOT NULL,
	password_hash     VARCHAR(255),
	nickname          VARCHAR(50)  NOT NULL,
	profile_image_url TEXT,
	grid_color        VARCHAR(10)  NOT NULL DEFAULT 'BLUE',
	role              VARCHAR(10)  NOT NULL DEFAULT 'USER',
	email_verified    BOOLEAN      NOT NULL DEFAULT FALSE,
	last_login_at     TIMESTAMP,
	created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT uq_users_email        UNIQUE (email),
	CONSTRAINT uq_users_provider_oid UNIQUE (provider, oid),
	CONSTRAINT chk_users_provider    CHECK (provider IN ('LOCAL', 'KAKAO')),
	CONSTRAINT chk_users_role        CHECK (role IN ('USER', 'ADMIN')),
	CONSTRAINT chk_users_grid_color  CHECK (grid_color IN
		('BLUE', 'GREEN', 'PURPLE', 'ORANGE', 'PINK', 'YELLOW', 'RED', 'TEAL')),
	CONSTRAINT chk_users_auth CHECK (
		(provider = 'LOCAL' AND password_hash IS NOT NULL AND oid IS NULL)
			OR (provider <> 'LOCAL' AND oid IS NOT NULL)
		)
);

-- ============================================================================
-- regions (행정동 마스터 — GeoJSON adm_cd2 시딩 필수)
-- ============================================================================
CREATE TABLE regions (
	region_code      VARCHAR(10) PRIMARY KEY,
	region_name      VARCHAR(100) NOT NULL,
	parent_code      VARCHAR(10),
	boundary_geom    GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL,
	total_grid_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_regions_boundary ON regions USING GIST (boundary_geom);
CREATE INDEX idx_regions_parent   ON regions (parent_code);

-- ============================================================================
-- grids (100×100m 격자, lazy insert — 행정동과 무관한 도감 축)
-- center_geom/bbox_geom + GIST는 정수 범위 스캔 벤치마크 후 제거 검토
-- ============================================================================
CREATE TABLE grids (
	grid_id       VARCHAR(20) PRIMARY KEY,
	grid_y        INTEGER NOT NULL,
	grid_x        INTEGER NOT NULL,
	center_geom   GEOGRAPHY(POINT, 4326)   NOT NULL,
	bbox_geom     GEOGRAPHY(POLYGON, 4326) NOT NULL,
	first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
	created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT uq_grids_yx UNIQUE (grid_y, grid_x)
);
CREATE INDEX idx_grids_bbox ON grids USING GIST (bbox_geom);

-- ============================================================================
-- videos (방문 이벤트, 1 row = 업로드 1건 — 행정동 레이블 보유)
-- ============================================================================
CREATE TABLE videos (
	id                BIGSERIAL PRIMARY KEY,
	user_id           BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	grid_id           VARCHAR(20) NOT NULL REFERENCES grids(grid_id),
	region_code       VARCHAR(10) REFERENCES regions(region_code),
	original_s3_key   VARCHAR(500),
	encoded_url       TEXT,
	thumbnail_url     TEXT,
	geom              GEOGRAPHY(POINT, 4326) NOT NULL,
	duration_sec      SMALLINT    NOT NULL,
	processing_status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
	visibility        VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',
	status            VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
	view_count        BIGINT      NOT NULL DEFAULT 0,
	recorded_at       TIMESTAMP   NOT NULL,
	created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT chk_videos_duration   CHECK (duration_sec > 0 AND duration_sec <= 30),
	CONSTRAINT chk_videos_processing CHECK (processing_status IN
		('UPLOADED', 'ENCODING', 'BLURRING', 'READY', 'FAILED')),
	CONSTRAINT chk_videos_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
	CONSTRAINT chk_videos_status     CHECK (status IN ('ACTIVE', 'BLINDED', 'DELETED')),
	CONSTRAINT chk_videos_view_count CHECK (view_count >= 0)
);
CREATE INDEX idx_videos_user_created ON videos (user_id, created_at DESC);
CREATE INDEX idx_videos_region       ON videos (region_code);
-- 격자 대표 영상 조회 (조회수 → 최신순)
CREATE INDEX idx_videos_grid_popular ON videos (grid_id, view_count DESC, created_at DESC)
	WHERE status = 'ACTIVE' AND visibility = 'PUBLIC' AND processing_status = 'READY';

-- ============================================================================
-- user_grids (개인 도감 = 점령 상태, 복합 PK)
-- ============================================================================
CREATE TABLE user_grids (
	user_id            BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	grid_id            VARCHAR(20) NOT NULL REFERENCES grids(grid_id),
	first_collected_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
	last_uploaded_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
	video_count        INTEGER     NOT NULL DEFAULT 1,
	cover_video_id     BIGINT REFERENCES videos(id) ON DELETE SET NULL,

	PRIMARY KEY (user_id, grid_id)
);

-- ============================================================================
-- region_stats (수집률 캐시, 복합 PK — MVP 선택 사항)
-- ============================================================================
CREATE TABLE region_stats (
	user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	region_code     VARCHAR(10)  NOT NULL REFERENCES regions(region_code),
	collected_count INTEGER      NOT NULL DEFAULT 0,
	total_count     INTEGER      NOT NULL DEFAULT 0,
	progress_rate   DECIMAL(5,2) NOT NULL DEFAULT 0.00,
	updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

	PRIMARY KEY (user_id, region_code)
);

-- ============================================================================
-- push_tokens (FCM — fcm_token 자연키)
-- ============================================================================
CREATE TABLE push_tokens (
	fcm_token    VARCHAR(512) PRIMARY KEY,
	user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	platform     VARCHAR(10) NOT NULL,
	app_version  VARCHAR(20),
	last_used_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT chk_push_tokens_platform CHECK (platform IN ('IOS', 'ANDROID', 'WEB'))
);
CREATE INDEX idx_push_tokens_user ON push_tokens (user_id);

-- ============================================================================
-- friendships (친구 관계, 복합 PK — Phase 2)
-- ============================================================================
CREATE TABLE friendships (
	requester_id BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	addressee_id BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	status       VARCHAR(10) NOT NULL DEFAULT 'PENDING',
	created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
	responded_at TIMESTAMP,

	PRIMARY KEY (requester_id, addressee_id),
	CONSTRAINT chk_friendships_self   CHECK (requester_id <> addressee_id),
	CONSTRAINT chk_friendships_status CHECK (status IN
		('PENDING', 'ACCEPTED', 'REJECTED', 'BLOCKED'))
);
CREATE INDEX idx_friendships_addressee ON friendships (addressee_id, status);

-- ============================================================================
-- streaks (연속 기록, 1 user = 1 row — Phase 2)
-- ============================================================================
CREATE TABLE streaks (
	user_id            BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	current_count      INTEGER NOT NULL DEFAULT 0,
	max_count          INTEGER NOT NULL DEFAULT 0,
	last_recorded_date DATE,
	updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- badges (뱃지 정의 — user_badges가 참조하므로 서러게이트 id 유지, Phase 2)
-- ============================================================================
CREATE TABLE badges (
	id              BIGSERIAL PRIMARY KEY,
	code            VARCHAR(50)  NOT NULL UNIQUE,
	name            VARCHAR(100) NOT NULL,
	description     TEXT,
	icon_url        TEXT,
	condition_type  VARCHAR(20)  NOT NULL,
	condition_value JSONB        NOT NULL,
	created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT chk_badges_condition CHECK (condition_type IN
		('REGION_PERCENT', 'TOTAL_GRIDS', 'STREAK_DAYS', 'UPLOAD_COUNT', 'SPECIAL'))
);

-- ============================================================================
-- user_badges (복합 PK — Phase 2)
-- ============================================================================
CREATE TABLE user_badges (
	user_id   BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	badge_id  BIGINT    NOT NULL REFERENCES badges(id),
	earned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

	PRIMARY KEY (user_id, badge_id)
);

-- ============================================================================
-- likes (복합 PK — Phase 2)
-- ============================================================================
CREATE TABLE likes (
	user_id    BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	video_id   BIGINT    NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
	created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

	PRIMARY KEY (user_id, video_id)
);
CREATE INDEX idx_likes_video ON likes (video_id);

-- ============================================================================
-- reports (신고 — 이벤트 로그, 서러게이트 id 유지, Phase 2)
-- ============================================================================
CREATE TABLE reports (
	id          BIGSERIAL PRIMARY KEY,
	reporter_id BIGINT      NOT NULL REFERENCES users(id),
	video_id    BIGINT      NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
	reviewed_by BIGINT REFERENCES users(id),
	reason      VARCHAR(20) NOT NULL,
	status      VARCHAR(10) NOT NULL DEFAULT 'PENDING',
	reviewed_at TIMESTAMP,
	created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT chk_reports_reason CHECK (reason IN
		('INAPPROPRIATE', 'PRIVACY', 'SPAM', 'COPYRIGHT', 'OTHER')),
	CONSTRAINT chk_reports_status CHECK (status IN
		('PENDING', 'REVIEWING', 'RESOLVED', 'REJECTED'))
);
CREATE INDEX idx_reports_video_status ON reports (video_id, status);
CREATE INDEX idx_reports_pending      ON reports (created_at) WHERE status = 'PENDING';

-- ============================================================================
-- sponsor_ads (스폰서 격자 — 기간별 입찰 이벤트, 서러게이트 id 유지, Phase 2)
-- ============================================================================
CREATE TABLE sponsor_ads (
	id              BIGSERIAL PRIMARY KEY,
	grid_id         VARCHAR(20)   NOT NULL REFERENCES grids(grid_id),
	advertiser_name VARCHAR(200)  NOT NULL,
	promo_video_url TEXT          NOT NULL,
	bid_amount      DECIMAL(10,2) NOT NULL,
	start_date      DATE NOT NULL,
	end_date        DATE NOT NULL,
	status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
	created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT chk_sponsor_dates  CHECK (start_date <= end_date),
	CONSTRAINT chk_sponsor_status CHECK (status IN
		('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'PAUSED', 'ENDED', 'REJECTED'))
);
CREATE INDEX idx_sponsor_ads_active ON sponsor_ads (grid_id, end_date)
	WHERE status = 'ACTIVE';
