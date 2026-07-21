package com.msg.fillmap.usergrid.repository;

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
}
