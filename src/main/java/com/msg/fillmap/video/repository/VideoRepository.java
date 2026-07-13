package com.msg.fillmap.video.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.video.entity.Video;

public interface VideoRepository extends JpaRepository<Video, Long> {

	/**
	 * 격자 lazy insert (전역 격자 등록). 이미 있으면 no-op — 멱등.
	 * center/bbox 는 GridEncoder 산출값을 PostGIS geography 로 변환해 저장한다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
		VALUES (
			:gridId, :gridY, :gridX,
			ST_SetSRID(ST_MakePoint(:centerLon, :centerLat), 4326)::geography,
			ST_SetSRID(ST_GeomFromText(:bboxWkt), 4326)::geography
		)
		ON CONFLICT (grid_id) DO NOTHING
		""", nativeQuery = true)
	void upsertGrid(
		@Param("gridId") String gridId,
		@Param("gridY") long gridY,
		@Param("gridX") long gridX,
		@Param("centerLat") double centerLat,
		@Param("centerLon") double centerLon,
		@Param("bboxWkt") String bboxWkt
	);

	/**
	 * 점령 여부 판정 (upsert 전 호출). false 면 이번 업로드가 첫 점령.
	 */
	@Query(value = "SELECT EXISTS(SELECT 1 FROM user_grids WHERE user_id = :userId AND grid_id = :gridId)",
		nativeQuery = true)
	boolean existsUserGrid(@Param("userId") long userId, @Param("gridId") String gridId);

	/**
	 * 점령 UPSERT. 첫 방문이면 INSERT(video_count=1), 재방문이면 video_count+1 + last_uploaded_at 갱신.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO user_grids (user_id, grid_id, video_count, cover_video_id, first_collected_at, last_uploaded_at)
		VALUES (:userId, :gridId, 1, :coverVideoId, now(), now())
		ON CONFLICT (user_id, grid_id) DO UPDATE
			SET video_count = user_grids.video_count + 1,
			    last_uploaded_at = now()
		""", nativeQuery = true)
	void upsertUserGrid(
		@Param("userId") long userId,
		@Param("gridId") String gridId,
		@Param("coverVideoId") long coverVideoId
	);
}
