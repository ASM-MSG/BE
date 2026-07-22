package com.msg.fillmap.usergrid.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.grid.entity.UserGrid;
import com.msg.fillmap.grid.entity.UserGridId;

/**
 * 개인 도감 집계 리포지토리 (MSG-152, read 전용). grid.entity.UserGrid 를 JpaRepository 루트로
 * 재사용하고(MSG-78 D6), user_grids·videos 를 네이티브로 직접 집계한다(VideoRepository 와 동일 패턴).
 */
public interface UserGridRepository extends JpaRepository<UserGrid, UserGridId> {

	/**
	 * 도감 요약 3지표를 스칼라 서브쿼리 3개로 1왕복 조회한다 (MSG-152 D4).
	 * - totalGridCount: 내가 점령한 격자 수 (user_grids COUNT).
	 * - totalVideoCount: 내 영상 총합 (SUM(video_count), 점령 0건이면 COALESCE 로 0).
	 * - visitedRegionCount: 방문한 서로 다른 행정동 수 (videos.region_code DISTINCT, DELETED 제외, NULL 자동 제외).
	 * COUNT 는 bigint 라 ::int 캐스트로 Integer 프로젝션과 맞춘다.
	 */
	@Query(value = """
		SELECT
			(SELECT COUNT(*)::int
				FROM user_grids WHERE user_id = :userId) AS "totalGridCount",
			(SELECT COALESCE(SUM(video_count), 0)
				FROM user_grids WHERE user_id = :userId) AS "totalVideoCount",
			(SELECT COUNT(DISTINCT region_code)::int
				FROM videos WHERE user_id = :userId AND status <> 'DELETED') AS "visitedRegionCount"
		""", nativeQuery = true)
	CollectionSummaryProjection getCollectionSummary(@Param("userId") long userId);

	/**
	 * 갤러리 격자 목록 — 내 점령 격자를 최근 수집순(first_collected_at DESC, 타이브레이크 grid_id DESC)
	 * 최대 30개 (MSG-153 D3). cover 는 READY 게이트를 건 videos LEFT JOIN 에서만 가져온다 — id 와 썸네일 key 를
	 * 둘 다 v.* 에서 뽑아 "cover 없거나 READY 이전이면 둘 다 null"(D4·DTO 계약)이 쌍으로 보장된다.
	 * ug.cover_video_id 를 직접 SELECT 하면 pre-READY 에 id 만 살아나와 계약 위반(Codex 커밋 리뷰 지적).
	 * geospatial·grids 조인 없음(D5) — equi/LEFT JOIN + PK 조회뿐이라 조회 핫패스에 point-in-polygon 이 없다.
	 * gridY/gridX 는 서비스가 GridEncoder.decode(gridId) 로 산출하고, coverThumbnailKey 는 서비스가 presign 한다.
	 */
	@Query(value = """
		SELECT
			ug.grid_id            AS "gridId",
			ug.first_collected_at AS "firstCollectedAt",
			ug.last_uploaded_at   AS "lastUploadedAt",
			ug.video_count        AS "videoCount",
			v.id                  AS "coverVideoId",
			v.thumbnail_url       AS "coverThumbnailKey"
		FROM user_grids ug
		LEFT JOIN videos v ON v.id = ug.cover_video_id AND v.processing_status = 'READY'
		WHERE ug.user_id = :userId
		ORDER BY ug.first_collected_at DESC, ug.grid_id DESC
		LIMIT 30
		""", nativeQuery = true)
	List<CollectionGridProjection> getCollectionGrids(@Param("userId") long userId);
}
