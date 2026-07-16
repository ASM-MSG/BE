-- MSG-132: 같은 원본을 두 번 확정하는 걸 막는다.
--
-- 없으면 영상 하나를 올려두고 좌표만 바꿔가며 POST /api/videos 를 반복해 격자를 무한 점령할 수 있다.
-- 애플리케이션에서 existsByOriginalS3Key 로 미리 거르지만, 동시 요청은 그 검사를 통과할 수 있으므로
-- 최종 방어선은 여기다.
--
-- original_s3_key 는 nullable 이고 PostgreSQL 의 UNIQUE 는 NULL 을 서로 다른 값으로 보므로
-- 값이 없는 행이 여럿이어도 충돌하지 않는다.
ALTER TABLE videos
	ADD CONSTRAINT uq_videos_original_s3_key UNIQUE (original_s3_key);
