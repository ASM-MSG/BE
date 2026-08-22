package com.msg.fillmap.event;

import jakarta.persistence.EntityManager;

/**
 * 행사 영상 연결 시드 헬퍼 (MSG-450). event_videos 행 하나를 붙이려면 FK 사슬
 * (event_series → event_occurrences → event_locations → event_location_grids)이 전부 필요해서,
 * 미션 쿼리 테스트 네 벌이 이 최소 체인 하나를 공유한다.
 */
public final class EventVideoFixtures {

	private EventVideoFixtures() {
	}

	/**
	 * videoId 를 행사 영상으로 만든다 — 호출마다 회차를 새로 만들어 회차 내 격자 유일 제약
	 * (uq_event_grid_per_occ)과 무관하게 같은 격자의 영상 여러 개를 링크할 수 있다. 위치의 격자 영역은
	 * 그 영상의 격자를 그대로 쓴다: 행사 위치의 대표 격자가 미션 대상 격자와 겹치는 상황이 안티조인이
	 * 막으려는 바로 그 경우다(MSG-450 §배경).
	 */
	public static void linkEventVideo(EntityManager em, long videoId) {
		String gridId = (String) em.createNativeQuery("SELECT grid_id FROM videos WHERE id = :videoId")
			.setParameter("videoId", videoId)
			.getSingleResult();
		String[] index = gridId.split("_");
		String key = "msg450-" + System.nanoTime();

		// 같은 커넥션의 직전 시퀀스 값 = 방금 넣은 행의 id (native INSERT 라 엔티티 id 를 못 받는다).
		em.createNativeQuery("INSERT INTO event_series (series_key, name) VALUES (:key, '테스트 시리즈')")
			.setParameter("key", key)
			.executeUpdate();
		long seriesId = lastId(em);

		// visible_from = starts_at - 14일은 DDL CHECK 라 값을 맞춰 넣는다. 기간은 이 쿼리들이 보지 않는다.
		em.createNativeQuery("""
				INSERT INTO event_occurrences (event_series_id, occurrence_key, title, city_name,
					starts_at, ends_at, visible_from, min_grid_y, max_grid_y, min_grid_x, max_grid_x)
				VALUES (:seriesId, :key, '테스트 회차', '테스트시',
					TIMESTAMP '2026-07-01 00:00:00', TIMESTAMP '2026-07-31 00:00:00',
					TIMESTAMP '2026-06-17 00:00:00', :gridY, :gridY, :gridX, :gridX)
				""")
			.setParameter("seriesId", seriesId)
			.setParameter("key", key)
			.setParameter("gridY", Integer.parseInt(index[0]))
			.setParameter("gridX", Integer.parseInt(index[1]))
			.executeUpdate();
		long occurrenceId = lastId(em);

		em.createNativeQuery("""
				INSERT INTO event_locations (event_occurrence_id, location_key, name, type,
					display_order, representative_grid_id)
				VALUES (:occurrenceId, :key, '테스트 위치', 'PHOTO_ZONE', 0, :gridId)
				""")
			.setParameter("occurrenceId", occurrenceId)
			.setParameter("key", key)
			.setParameter("gridId", gridId)
			.executeUpdate();
		long locationId = lastId(em);

		em.createNativeQuery("""
				INSERT INTO event_location_grids (event_location_id, event_occurrence_id, grid_id)
				VALUES (:locationId, :occurrenceId, :gridId)
				""")
			.setParameter("locationId", locationId)
			.setParameter("occurrenceId", occurrenceId)
			.setParameter("gridId", gridId)
			.executeUpdate();

		em.createNativeQuery("""
				INSERT INTO event_videos (video_id, event_occurrence_id, event_location_id)
				VALUES (:videoId, :occurrenceId, :locationId)
				""")
			.setParameter("videoId", videoId)
			.setParameter("occurrenceId", occurrenceId)
			.setParameter("locationId", locationId)
			.executeUpdate();
	}

	private static long lastId(EntityManager em) {
		return ((Number) em.createNativeQuery("SELECT lastval()").getSingleResult()).longValue();
	}
}
