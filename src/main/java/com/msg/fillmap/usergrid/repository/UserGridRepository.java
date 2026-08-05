package com.msg.fillmap.usergrid.repository;

import java.time.LocalDateTime;
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
	 * - visitedRegionCount: 방문한 서로 다른 행정동 수 — 영상이 있는 격자들의 라벨(grids.region_code) DISTINCT
	 *   (MSG-246, by-grid 귀속 MSG-167). videos.region_code 는 쓰기 경로가 없어 항상 NULL 이라 축에서 제외했다.
	 *   DELETED 제외·BLINDED 포함(MSG-152 D6), 무라벨 격자(NULL)는 COUNT DISTINCT 가 자동 제외.
	 *   videos.grid_id 는 NOT NULL FK 라 inner JOIN 에서 유실 없음. 저장 라벨 equi-join 소비(geospatial 0).
	 * COUNT 는 bigint 라 ::int 캐스트로 Integer 프로젝션과 맞춘다.
	 */
	@Query(value = """
		SELECT
			(SELECT COUNT(*)::int
				FROM user_grids WHERE user_id = :userId) AS "totalGridCount",
			(SELECT COALESCE(SUM(video_count), 0)
				FROM user_grids WHERE user_id = :userId) AS "totalVideoCount",
			(SELECT COUNT(DISTINCT g.region_code)::int
				FROM videos v
				JOIN grids g ON g.grid_id = v.grid_id
				WHERE v.user_id = :userId AND v.status <> 'DELETED') AS "visitedRegionCount"
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

	/**
	 * 동 단위 내 영상 — 그 행정동(grids.region_code) 격자들에 올린 내 ACTIVE 영상을 created_at 내림차순으로
	 * 반환한다 (MSG-167 §D3, B-내부 read). 귀속은 격자 축(g.region_code) — 영상 좌표(videos.region_code,
	 * 66 라벨러 유예로 전부 NULL)가 아니라 격자 소속 행정동 기준이라, 영상 좌표가 옆 동이어도 격자 소속 동으로
	 * 잡힌다(DoD). 저장된 라벨을 equi 로 소비해 geospatial 0(성공 기준 8) — point-in-polygon 은 쓰기 경로
	 * (upsertGrid)·백필에서만 돈다. 내 도감 관례(127): status='ACTIVE' 만(DELETED/BLINDED 제외),
	 * visibility·processing_status 무필터(PRIVATE·인코딩 중도 내겐 보여야 한다). READY 이전은 thumbnail_url
	 * NULL → 서비스 presign 이 thumbnailUrl NULL 로 흡수. 미존재/이상 regionCode 는 매치 0 → 빈 리스트
	 * (§D3, 신규 에러 코드 없음). no-LIMIT(전부 반환 — 조용한 절단 금지, §D3). 구동은 idx_videos_user_created
	 * (user 몰기) → grids PK 조인 → region_code 필터(§D5, region_code 인덱스 불요).
	 */
	@Query(value = """
		SELECT
			v.id                AS "videoId",
			v.grid_id           AS "gridId",
			v.thumbnail_url     AS "thumbnailKey",
			v.processing_status AS "processingStatus",
			v.duration_sec      AS "durationSec",
			v.created_at        AS "createdAt"
		FROM videos v
		JOIN grids g ON g.grid_id = v.grid_id
		WHERE g.region_code = :regionCode
			AND v.user_id = :userId
			AND v.status = 'ACTIVE'
		ORDER BY v.created_at DESC, v.id DESC
		""", nativeQuery = true)
	List<RegionVideoProjection> getRegionVideos(@Param("userId") long userId, @Param("regionCode") String regionCode);

	/**
	 * 친구에게 보여줄 격자 목록 — 격자 사실(수집 시각·방문 시각·영상 수·행정동)은 전부, 썸네일은 그 친구가
	 * 재생할 수 있는 영상 것만 (MSG-186 D6). 정렬·개수는 본인 갤러리와 동일(first_collected_at DESC,
	 * grid_id DESC, 최대 30). getCollectionGrids 와 갈리는 지점은 썸네일 선정 한 곳뿐이다 — 본인용은
	 * cover 를 visibility 무필터로 읽어 PRIVATE 썸네일이 그대로 나가므로 친구에게 재사용할 수 없다.
	 * LATERAL 은 격자당 최대 1행이라 30격자 × 1건으로 끝난다(재생 허용 영상이 없으면 NULL → 격자 사실만).
	 */
	@Query(value = """
		SELECT
			ug.grid_id            AS "gridId",
			ug.first_collected_at AS "firstCollectedAt",
			ug.last_uploaded_at   AS "lastUploadedAt",
			ug.video_count        AS "videoCount",
			t.thumbnail_url       AS "thumbnailKey",
			r.region_name         AS "regionName"
		FROM user_grids ug
		LEFT JOIN LATERAL (
			-- 친구에게 보여줄 썸네일 1건: 재생 허용 영상만(PUBLIC + FRIENDS — MSG-187 D6 에서 MSG-285 재생
			-- 판정과 정합화, PRIVATE 은 계속 제외), cover 우선(소유자 선택 존중) → 최신순 폴백.
			-- 없으면 NULL(격자 사실만).
			SELECT v.thumbnail_url
			FROM videos v
			WHERE v.grid_id = ug.grid_id AND v.user_id = ug.user_id
				AND v.status = 'ACTIVE' AND v.visibility IN ('PUBLIC', 'FRIENDS')
				AND v.processing_status = 'READY' AND v.thumbnail_url IS NOT NULL
			ORDER BY (v.id = ug.cover_video_id) DESC, v.created_at DESC, v.id DESC
			LIMIT 1
		) t ON TRUE
		LEFT JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN regions r ON r.region_code = g.region_code
		WHERE ug.user_id = :userId
		ORDER BY ug.first_collected_at DESC, ug.grid_id DESC
		LIMIT 30
		""", nativeQuery = true)
	List<FriendCollectionGridProjection> getCollectionGridsForFriend(@Param("userId") long userId);

	/**
	 * 격자 → 점령 사용자 역조회 (MSG-181 D6, 핫구역 진입 통지용). 최초의 grid 축 접근 경로 —
	 * PK (user_id, grid_id)는 선두가 user_id 라 못 받쳐 V24 idx_user_grids_grid 가 구동한다.
	 * region_name 은 문구 재료 — 행정동 없는 격자(해상 등)는 LEFT JOIN 으로 null.
	 */
	@Query(value = """
		SELECT ug.user_id AS "userId", r.region_name AS "regionName"
		FROM user_grids ug
		LEFT JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN regions r ON r.region_code = g.region_code
		WHERE ug.grid_id = :gridId
		""", nativeQuery = true)
	List<GridOccupantProjection> getGridOccupants(@Param("gridId") String gridId);

	/**
	 * 주간 요약 대상·수치 동시 집계 (MSG-315 D3). 대상 판정("활동이 있었는가")과 수치("몇 개인가")가 같은
	 * 재료라 UNION ALL 한 번으로 읽는다 — 결과에 든 사용자가 곧 활동자이므로 FR-6 이 별도 필터 없이 성립한다.
	 * 임계값 두 개는 호출자가 KST 주 시작을 절대 시각으로 잡아 저장 존(JVM 기본 존)으로 환산해 바인딩한다 —
	 * 이 두 컬럼은 notifications.created_at 과 달리 UTC 가 아니라 JVM 기본 존 벽시계로 저장되기 때문(D2 표).
	 * 영상은 status &lt;&gt; 'DELETED'(도감 요약과 같은 기준, MSG-152 D6 — 삭제 제외·블라인드 포함),
	 * 격자는 별도 필터가 없다(점령 롤백이 행 자체를 지우므로 남은 행이 곧 현재 진실).
	 * COUNT 계열이 bigint 라 ::int 캐스트로 Integer 프로젝션과 맞춘다(도감 요약 선례).
	 */
	@Query(value = """
		SELECT t.user_id AS "userId",
			sum(t.grid_delta)::int AS "gridCount",
			sum(t.video_delta)::int AS "videoCount"
		FROM (
			SELECT user_id, 1 AS grid_delta, 0 AS video_delta
			FROM user_grids
			WHERE first_collected_at >= :weekStart AND first_collected_at < :now
			UNION ALL
			SELECT user_id, 0, 1
			FROM videos
			WHERE created_at >= :weekStart AND created_at < :now AND status <> 'DELETED'
		) t
		GROUP BY t.user_id
		""", nativeQuery = true)
	List<WeeklyActivityProjection> findWeeklyActivity(
		@Param("weekStart") LocalDateTime weekStart,
		@Param("now") LocalDateTime now
	);
}
