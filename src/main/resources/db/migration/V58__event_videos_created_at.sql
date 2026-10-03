-- MSG-612: 행사 피드 "위치의 최신순 상위 N" 인덱스. 정렬 키(videos.created_at)가 필터 키
-- (event_videos.event_location_id)와 다른 테이블에 있어 어떤 인덱스도 top-N 을 못 줬다
-- (2026-10-03 감사 §3.1 #12: 영상 2만 건 위치의 첫 페이지 361ms, 매 페이지 전 영상 정렬).
-- videos.created_at 을 event_videos 에 한 칸 복사한다. 원본은 updatable=false 라 어긋날 경로가 없다.
-- DEFAULT 를 두지 않는다: 이 값은 "지금"이 아니라 videos 의 복사본이라, 빠뜨린 INSERT 는 틀린 값으로
-- 조용히 채워지는 것보다 NOT NULL 로 실패하는 편이 낫다.
ALTER TABLE event_videos ADD COLUMN created_at TIMESTAMP;

UPDATE event_videos ev
SET created_at = v.created_at
FROM videos v
WHERE v.id = ev.video_id;

ALTER TABLE event_videos ALTER COLUMN created_at SET NOT NULL;
COMMENT ON COLUMN event_videos.created_at IS
	'videos.created_at 복사본(피드 정렬과 keyset 인덱스용). 업로드 시 EventVideo 생성자가 채운다. MSG-612';

CREATE INDEX idx_event_videos_location_recent
	ON event_videos (event_location_id, created_at DESC, video_id DESC);

-- V39 의 단일 열 인덱스는 새 인덱스의 선두 열이 같아 전부 대체된다(위치별 count, exists, 피드).
DROP INDEX idx_event_videos_location;
