-- ============================================================================
-- MomentMap V1 — Initial Schema
-- PostgreSQL 16 + PostGIS 3.4
-- ============================================================================

-- ============ Extensions ============
CREATE EXTENSION IF NOT EXISTS postgis;

-- ============ Enums ============
CREATE TYPE auth_provider          AS ENUM ('LOCAL', 'KAKAO');
CREATE TYPE user_role              AS ENUM ('USER', 'ADMIN');
CREATE TYPE push_platform          AS ENUM ('IOS', 'ANDROID', 'WEB');
CREATE TYPE friendship_status      AS ENUM ('PENDING', 'ACCEPTED', 'REJECTED', 'BLOCKED');
CREATE TYPE video_processing_status AS ENUM ('UPLOADED', 'ENCODING', 'BLURRING', 'READY', 'FAILED');
CREATE TYPE video_visibility       AS ENUM ('PRIVATE', 'PUBLIC');
CREATE TYPE video_status           AS ENUM ('ACTIVE', 'BLINDED', 'DELETED');
CREATE TYPE report_reason          AS ENUM ('SPAM', 'SEXUAL', 'VIOLENCE', 'COPYRIGHT', 'PRIVACY', 'OTHER');
CREATE TYPE report_status          AS ENUM ('PENDING', 'REVIEWED', 'ACCEPTED', 'REJECTED');
CREATE TYPE badge_condition_type   AS ENUM ('REGION_PERCENT', 'TOTAL_GRIDS', 'STREAK_DAYS', 'FIRST_IN_REGION');
CREATE TYPE sponsor_ad_status      AS ENUM ('PENDING', 'ACTIVE', 'EXPIRED', 'REJECTED');

-- ============================================================================
-- users
-- ============================================================================
CREATE TABLE users (
                       id                 BIGSERIAL PRIMARY KEY,
                       provider           auth_provider NOT NULL,
                       oid                VARCHAR(64),
                       email              VARCHAR(255)  NOT NULL,
                       password_hash      VARCHAR(255),
                       nickname           VARCHAR(50)   NOT NULL,
                       profile_image_url  TEXT,
                       role               user_role     NOT NULL DEFAULT 'USER',
                       email_verified     BOOLEAN       NOT NULL DEFAULT FALSE,
                       last_login_at      TIMESTAMP,
                       created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

                       CONSTRAINT uq_users_email        UNIQUE (email),
                       CONSTRAINT uq_users_provider_oid UNIQUE (provider, oid),
                       CONSTRAINT chk_users_auth CHECK (
                           (provider = 'LOCAL' AND password_hash IS NOT NULL AND oid IS NULL)
                               OR (provider <> 'LOCAL' AND oid IS NOT NULL)
                           )
);

-- ============================================================================
-- regions (행정동 마스터)
-- ============================================================================
CREATE TABLE regions (
                         region_code       VARCHAR(10) PRIMARY KEY,
                         region_name       VARCHAR(100) NOT NULL,
                         parent_code       VARCHAR(10),
                         boundary_geom     GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL,
                         total_grid_count  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_regions_boundary ON regions USING GIST (boundary_geom);
CREATE INDEX idx_regions_parent   ON regions (parent_code);

-- ============================================================================
-- grids (GeoHash7 격자)
-- ============================================================================
CREATE TABLE grids (
                       geohash        VARCHAR(7) PRIMARY KEY,
                       region_code    VARCHAR(10) REFERENCES regions(region_code),
                       center_geom    GEOGRAPHY(POINT, 4326)   NOT NULL,
                       bbox_geom      GEOGRAPHY(POLYGON, 4326) NOT NULL,
                       first_seen_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_grids_bbox       ON grids USING GIST (bbox_geom);
CREATE INDEX idx_grids_region     ON grids (region_code);
CREATE INDEX idx_grids_prefix_5   ON grids (LEFT(geohash, 5));  -- viewport prefix 쿼리용

-- ============================================================================
-- push_tokens (FCM)
-- ============================================================================
CREATE TABLE push_tokens (
                             id            BIGSERIAL PRIMARY KEY,
                             user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                             fcm_token     VARCHAR(255) NOT NULL UNIQUE,
                             platform      push_platform NOT NULL,
                             app_version   VARCHAR(20),
                             last_used_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_push_tokens_user ON push_tokens (user_id);

-- ============================================================================
-- friendships (친구 관계)
-- ============================================================================
CREATE TABLE friendships (
                             id            BIGSERIAL PRIMARY KEY,
                             requester_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                             addressee_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                             status        friendship_status NOT NULL DEFAULT 'PENDING',
                             created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             responded_at  TIMESTAMP,

                             CONSTRAINT uq_friendships       UNIQUE (requester_id, addressee_id),
                             CONSTRAINT chk_friendships_self CHECK (requester_id <> addressee_id)
);
CREATE INDEX idx_friendships_addressee ON friendships (addressee_id, status);

-- ============================================================================
-- streaks (연속 기록, 1 user = 1 row)
-- ============================================================================
CREATE TABLE streaks (
                         user_id             BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                         current_count       INTEGER NOT NULL DEFAULT 0,
                         max_count           INTEGER NOT NULL DEFAULT 0,
                         last_recorded_date  DATE,
                         updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- videos (5초 영상)
-- ============================================================================
CREATE TABLE videos (
                        id                 BIGSERIAL PRIMARY KEY,
                        user_id            BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        geohash            VARCHAR(7) NOT NULL REFERENCES grids(geohash),
                        original_s3_key    VARCHAR(500),
                        encoded_url        TEXT,
                        thumbnail_url      TEXT,
                        geom               GEOGRAPHY(POINT, 4326) NOT NULL,
                        duration_sec       SMALLINT NOT NULL DEFAULT 5,
                        processing_status  video_processing_status NOT NULL DEFAULT 'UPLOADED',
                        visibility         video_visibility NOT NULL DEFAULT 'PRIVATE',
                        status             video_status     NOT NULL DEFAULT 'ACTIVE',
                        recorded_at        TIMESTAMP NOT NULL,
                        created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_videos_geom         ON videos USING GIST (geom);
CREATE INDEX idx_videos_geohash      ON videos (geohash);
CREATE INDEX idx_videos_user_created ON videos (user_id, created_at DESC);
CREATE INDEX idx_videos_active       ON videos (geohash, created_at DESC)
    WHERE status = 'ACTIVE' AND visibility = 'PUBLIC';

-- ============================================================================
-- user_grids (개인 도감)
-- ============================================================================
CREATE TABLE user_grids (
                            id                  BIGSERIAL PRIMARY KEY,
                            user_id             BIGINT     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            geohash             VARCHAR(7) NOT NULL REFERENCES grids(geohash),
                            first_collected_at  TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            last_uploaded_at    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            video_count         INTEGER    NOT NULL DEFAULT 1,
                            cover_video_id      BIGINT REFERENCES videos(id) ON DELETE SET NULL,

                            CONSTRAINT uq_user_grids UNIQUE (user_id, geohash)
);
CREATE INDEX idx_user_grids_user ON user_grids (user_id);

-- ============================================================================
-- region_stats (수집률 캐시, 복합 PK)
-- ============================================================================
CREATE TABLE region_stats (
                              user_id          BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                              region_code      VARCHAR(10) NOT NULL REFERENCES regions(region_code),
                              collected_count  INTEGER     NOT NULL DEFAULT 0,
                              total_count      INTEGER     NOT NULL DEFAULT 0,
                              progress_rate    DECIMAL(5,2) NOT NULL DEFAULT 0.00,
                              updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              PRIMARY KEY (user_id, region_code)
);

-- ============================================================================
-- badges (뱃지 정의)
-- ============================================================================
CREATE TABLE badges (
                        id              BIGSERIAL PRIMARY KEY,
                        code            VARCHAR(50)  NOT NULL UNIQUE,
                        name            VARCHAR(100) NOT NULL,
                        description     TEXT,
                        icon_url        TEXT,
                        condition_type  badge_condition_type NOT NULL,
                        condition_value JSONB        NOT NULL,
                        created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- user_badges
-- ============================================================================
CREATE TABLE user_badges (
                             id         BIGSERIAL PRIMARY KEY,
                             user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                             badge_id   BIGINT NOT NULL REFERENCES badges(id),
                             earned_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                             CONSTRAINT uq_user_badges UNIQUE (user_id, badge_id)
);
CREATE INDEX idx_user_badges_user ON user_badges (user_id);

-- ============================================================================
-- likes
-- ============================================================================
CREATE TABLE likes (
                       id          BIGSERIAL PRIMARY KEY,
                       user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                       video_id    BIGINT NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
                       created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                       CONSTRAINT uq_likes UNIQUE (user_id, video_id)
);
CREATE INDEX idx_likes_video ON likes (video_id);

-- ============================================================================
-- reports
-- ============================================================================
CREATE TABLE reports (
                         id           BIGSERIAL PRIMARY KEY,
                         reporter_id  BIGINT NOT NULL REFERENCES users(id),
                         video_id     BIGINT NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
                         reviewed_by  BIGINT REFERENCES users(id),
                         reason       report_reason NOT NULL,
                         status       report_status NOT NULL DEFAULT 'PENDING',
                         reviewed_at  TIMESTAMP,
                         created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_reports_video_status ON reports (video_id, status);
CREATE INDEX idx_reports_pending      ON reports (created_at) WHERE status = 'PENDING';

-- ============================================================================
-- sponsor_ads
-- ============================================================================
CREATE TABLE sponsor_ads (
                             id              BIGSERIAL PRIMARY KEY,
                             geohash         VARCHAR(7) NOT NULL REFERENCES grids(geohash),
                             advertiser_name VARCHAR(200) NOT NULL,
                             promo_video_url TEXT         NOT NULL,
                             bid_amount      DECIMAL(10,2) NOT NULL,
                             start_date      DATE NOT NULL,
                             end_date        DATE NOT NULL,
                             status          sponsor_ad_status NOT NULL DEFAULT 'PENDING',
                             created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                             CONSTRAINT chk_sponsor_dates CHECK (start_date <= end_date)
);
CREATE INDEX idx_sponsor_ads_active
    ON sponsor_ads (geohash, end_date)
    WHERE status = 'ACTIVE';