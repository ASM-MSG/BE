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
	 * 최대 30개 (MSG-153 D3). coverVideoId 는 스펙 §API 정본대로 user_grids.cover_video_id 그대로(cover 없으면 null,
	 * readiness 무관), 썸네일 key 만 READY 게이트를 건 LEFT JOIN 에서 가져온다 — "READY 이전이면 썸네일 null"(§D4)을
	 * 조인 조건으로 강제해 교체·재인코딩 경계에서 pre-READY 행에 남은 stale 썸네일이 새지 않는다. READY 여도 썸네일이
	 * null 일 수 있어(markReady 가 null thumbnailKey 허용) "id·썸네일 둘 다 null 쌍"은 계약이 아니다 —
	 * 인코딩 중 cover 는 id 만 있고 key 가 null 인 게 정상 상태.
	 * regionName 은 격자 중심점 행정동 이름 — grids·regions LEFT JOIN(PK/equi)으로 붙인다(MSG-167 §D4).
	 * 저장된 라벨(grids.region_code)을 equi 로 소비하므로 조인이 늘어도 여전히 geospatial 0(성공 기준 8) —
	 * point-in-polygon 판정은 쓰기 경로(upsertGrid)·백필에서만 돈다. region_code NULL(해안/미판정)이면 regionName null.
	 * gridY/gridX 는 서비스가 GridEncoder.decode(gridId) 로 산출하고, coverThumbnailKey 는 서비스가 presign 한다.
	 */
	@Query(value = """
		SELECT
			ug.grid_id            AS "gridId",
			ug.first_collected_at AS "firstCollectedAt",
			ug.last_uploaded_at   AS "lastUploadedAt",
			ug.video_count        AS "videoCount",
			ug.cover_video_id     AS "coverVideoId",
			v.thumbnail_url       AS "coverThumbnailKey",
			r.region_name         AS "regionName"
		FROM user_grids ug
		LEFT JOIN videos v ON v.id = ug.cover_video_id AND v.processing_status = 'READY'
		LEFT JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN regions r ON r.region_code = g.region_code
		WHERE ug.user_id = :userId
		ORDER BY ug.first_collected_at DESC, ug.grid_id DESC
		LIMIT 30
		""", nativeQuery = true)
	List<CollectionGridProjection> getCollectionGrids(@Param("userId") long userId);
}
