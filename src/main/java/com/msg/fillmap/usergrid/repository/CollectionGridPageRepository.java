package com.msg.fillmap.usergrid.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 행정동 전체 보기 키셋 페이지 전용 조회 저장소 (MSG-460).
 *
 * user_grids(Owner B)의 정렬값과 grids(Owner A)의 행정동 라벨을 같은 SQL에서 비교해야
 * 페이지 경계가 정확하다. Grid 엔티티를 Owner B에 직접 의존시키거나 행정동의 모든 gridId를
 * 메모리로 가져오지 않도록,
 * 불가피한 교차 테이블 읽기를 이 저장소 한 곳의 네이티브 SQL로 격리한다.
 */
@Repository
@RequiredArgsConstructor
public class CollectionGridPageRepository {

	private static final String SELECT_FROM = """
		SELECT
			ug.grid_id            AS "gridId",
			ug.first_collected_at AS "firstCollectedAt",
			ug.last_uploaded_at   AS "lastUploadedAt",
			ug.video_count        AS "videoCount",
			ug.cover_video_id     AS "coverVideoId",
			CASE WHEN v.processing_status = 'READY' THEN v.thumbnail_url END AS "coverThumbnailKey",
			v.duration_sec        AS "coverDurationSec",
			r.region_name         AS "regionName"
		FROM user_grids ug
		JOIN grids g ON g.grid_id = ug.grid_id
		LEFT JOIN videos v ON v.id = ug.cover_video_id
		LEFT JOIN regions r ON r.region_code = g.region_code
		WHERE ug.user_id = :userId
			AND g.region_code = :regionCode
		""";

	private static final String ORDER_AND_LIMIT = """
		ORDER BY ug.last_uploaded_at DESC, ug.video_count DESC, ug.grid_id DESC
		LIMIT :limit
		""";

	private final JdbcClient jdbcClient;

	public List<CollectionGridProjection> getPage(long userId, String regionCode, int limit) {
		return baseQuery(SELECT_FROM + ORDER_AND_LIMIT, userId, regionCode, limit)
			.query(CollectionGridPageRepository::mapRow)
			.list();
	}

	public List<CollectionGridProjection> getPageAfter(
		long userId,
		String regionCode,
		LocalDateTime cursorLastUploadedAt,
		int cursorVideoCount,
		String cursorGridId,
		int limit
	) {
		String sql = SELECT_FROM + """
			AND (
				ug.last_uploaded_at < :cursorLastUploadedAt
				OR (ug.last_uploaded_at = :cursorLastUploadedAt AND ug.video_count < :cursorVideoCount)
				OR (ug.last_uploaded_at = :cursorLastUploadedAt AND ug.video_count = :cursorVideoCount
					AND ug.grid_id < :cursorGridId)
			)
			""" + ORDER_AND_LIMIT;
		return baseQuery(sql, userId, regionCode, limit)
			.param("cursorLastUploadedAt", cursorLastUploadedAt)
			.param("cursorVideoCount", cursorVideoCount)
			.param("cursorGridId", cursorGridId)
			.query(CollectionGridPageRepository::mapRow)
			.list();
	}

	private JdbcClient.StatementSpec baseQuery(String sql, long userId, String regionCode, int limit) {
		return jdbcClient.sql(sql)
			.param("userId", userId)
			.param("regionCode", regionCode)
			.param("limit", limit);
	}

	private static CollectionGridProjection mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new CollectionGridRow(
			rs.getString("gridId"),
			rs.getObject("firstCollectedAt", LocalDateTime.class),
			rs.getObject("lastUploadedAt", LocalDateTime.class),
			rs.getInt("videoCount"),
			rs.getObject("coverVideoId", Long.class),
			rs.getString("coverThumbnailKey"),
			rs.getObject("coverDurationSec", Integer.class),
			rs.getString("regionName")
		);
	}

	private record CollectionGridRow(
		String gridId,
		LocalDateTime firstCollectedAt,
		LocalDateTime lastUploadedAt,
		Integer videoCount,
		Long coverVideoId,
		String coverThumbnailKey,
		Integer coverDurationSec,
		String regionName
	) implements CollectionGridProjection {

		@Override
		public String getGridId() {
			return gridId;
		}

		@Override
		public LocalDateTime getFirstCollectedAt() {
			return firstCollectedAt;
		}

		@Override
		public LocalDateTime getLastUploadedAt() {
			return lastUploadedAt;
		}

		@Override
		public Integer getVideoCount() {
			return videoCount;
		}

		@Override
		public Long getCoverVideoId() {
			return coverVideoId;
		}

		@Override
		public String getCoverThumbnailKey() {
			return coverThumbnailKey;
		}

		@Override
		public Integer getCoverDurationSec() {
			return coverDurationSec;
		}

		@Override
		public String getRegionName() {
			return regionName;
		}
	}
}
