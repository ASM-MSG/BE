-- V41__event_video_interaction.sql
-- MSG-441: 행사 영상의 댓글과 도움돼요 (PRD US-006, FR-13·14·21).
-- 두 테이블 모두 event_videos 를 참조한다. videos 가 아니라 행사 영상 연결을 가리켜야
-- 행사 영상이 아닌 영상에 반응이 붙는 상태를 DDL 이 막는다.
-- 연쇄 삭제가 실제로 도는 경로는 사용자 탈퇴이고 방향이 둘이다. 내 영상에 달린 남의 반응은
-- users -> videos -> event_videos -> 이 두 테이블로 내려가고, 남의 영상에 단 내 반응은
-- 아래 user_id FK 가 직접 지운다 (그래서 user_id 인덱스 2개가 필요하다).
-- 사용자의 영상 삭제는 소프트 삭제(markDeleted)라 연쇄가 발화하지 않고 반응 행이 남지만,
-- 노출 술어(ACTIVE)가 그 영상을 조회·변경 전 경로에서 배제하므로 남은 행은 어디에도 안 실린다.
-- 시각 DEFAULT 는 V33·V40 선례를 따른다: CURRENT_TIMESTAMP 는 timestamptz 라 naive 컬럼
-- 대입 시 세션 타임존으로 변환돼 KST 세션에서 9시간 앞선 값이 저장된다.

CREATE TABLE event_video_comments (
	id         BIGSERIAL PRIMARY KEY,
	video_id   BIGINT       NOT NULL REFERENCES event_videos(video_id) ON DELETE CASCADE,
	user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	content    VARCHAR(500) NOT NULL,
	-- JPA 경로에서는 엔티티 팩터리가 값을 채운다. 이 DEFAULT 는 운영 중 수동 INSERT 대비 안전망이다
	-- (JPA save 는 created_at 을 INSERT 컬럼 목록에 포함하므로 DEFAULT 가 적용되지 않는다).
	created_at TIMESTAMP    NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'utc')
);
COMMENT ON TABLE event_video_comments IS '행사 영상 댓글. 작성자 본인만 수정·삭제. MSG-441';

-- 목록 keyset(video_id = ? ORDER BY id)과 댓글 수 집계가 같이 타는 인덱스
CREATE INDEX idx_event_video_comments_video ON event_video_comments (video_id, id);
-- 탈퇴 CASCADE 조회용. PostgreSQL 은 참조 측 FK 컬럼을 자동 인덱싱하지 않아, 없으면 사용자
-- 한 명이 탈퇴할 때마다 이 테이블 전체 스캔이 된다 (videos.idx_videos_user_created 관행).
CREATE INDEX idx_event_video_comments_user ON event_video_comments (user_id);

CREATE TABLE event_video_helpfuls (
	video_id   BIGINT    NOT NULL REFERENCES event_videos(video_id) ON DELETE CASCADE,
	user_id    BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	-- 이쪽은 native ON CONFLICT INSERT 라 컬럼을 생략하므로 DEFAULT 가 실제 저장값이 된다
	-- (댓글은 JPA save 경로라 팩터리가 채운다. 두 테이블의 차이가 여기서 갈린다).
	created_at TIMESTAMP NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'utc'),

	-- 사용자당 1회를 DDL 이 보장한다. video_id 선두라 영상별 집계가 이 PK 인덱스를 탄다
	-- (user_grids·user_badges·likes 가 user_id 선두인 것과 달리 여기는 집계 축이 영상이다).
	PRIMARY KEY (video_id, user_id)
);
COMMENT ON TABLE event_video_helpfuls IS '행사 영상 도움돼요. 사용자당 1회, 취소 가능. MSG-441';

-- 탈퇴 CASCADE 조회용. 위 PK 가 video_id 선두라 user_id 단독 조회를 못 타므로 별개로 둔다
-- (없으면 탈퇴마다 전체 스캔. PostgreSQL 은 참조 측 FK 컬럼을 자동 인덱싱하지 않는다).
CREATE INDEX idx_event_video_helpfuls_user ON event_video_helpfuls (user_id);
