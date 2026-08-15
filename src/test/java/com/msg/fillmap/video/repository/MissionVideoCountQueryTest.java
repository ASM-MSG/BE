package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 미션 격자별 영상 개수 native 쿼리 검증 (실 PostGIS, MSG-399 §영상 개수). 후보 술어(미션 격자 조인 +
 * 미션 기간 + 전역 공개 게이트 3종)가 MSG-390 목록(findMissionVideos)과 술어 동등임을 GROUP BY 집계로
 * 본다. 영상이 없는 격자는 결과 행이 없다 — 0 채움은 서비스 몫.
 *
 * <p>격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백한다. 집계에 사용자 조건이
 * 없어 격자는 시드 데이터와 겹치지 않는 한국 밖 합성 인덱스(+7000)를 쓴다(MissionVideoListQueryTest 선례).
 */
@SpringBootTest
@Transactional
@DisplayName("VideoRepository 미션 격자별 영상 개수 조회 (실 PostGIS)")
class MissionVideoCountQueryTest {

	private static final double 문래_LAT = 37.5172;
	private static final double 문래_LON = 126.8895;

	// 시드·타 테스트와 못 겹치는 합성 격자 오프셋 (한국 밖).
	private static final long SYNTHETIC_OFFSET = 7000L;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long userId;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		String email = "mission-count-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "미션개수테스터")).getId();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(문래_LAT, 문래_LON));
		baseY = base.gridY() + SYNTHETIC_OFFSET;
		baseX = base.gridX();
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("전체 공개 READY ACTIVE 영상만 격자별로 센다 — MSG-390 게이트 3종과 같은 조건")
	void 전체_공개_READY_ACTIVE_영상만_격자별로_센다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String first = seedGrid(0);
		String second = seedGrid(1);
		insertMissionGrid(mission, first);
		insertMissionGrid(mission, second);
		publicReady(first, at(10, 9));
		publicReady(first, at(11, 9));
		publicReady(second, at(12, 9));
		insertVideo(first, at(13, 9), "ACTIVE", "PRIVATE", "READY");     // 게이트 탈락
		insertVideo(first, at(14, 9), "ACTIVE", "FRIENDS", "READY");     // 게이트 탈락
		insertVideo(first, at(15, 9), "DELETED", "PUBLIC", "READY");     // 게이트 탈락
		insertVideo(first, at(16, 9), "BLINDED", "PUBLIC", "READY");     // 게이트 탈락 (개수는 목록과 같은 게이트)
		insertVideo(second, at(17, 9), "ACTIVE", "PUBLIC", "ENCODING");  // 게이트 탈락

		List<MissionVideoCountProjection> rows = count(mission);

		assertThat(rows).hasSize(2);
		assertThat(countOf(rows, first)).isEqualTo(2);
		assertThat(countOf(rows, second)).isEqualTo(1);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("미션 기간 밖의 영상은 세지 않는다 — 기간이 끝난 미션도 활성 당시 촬영분만")
	void 미션_기간_밖의_영상은_세지_않는다() {
		long mission = insertMission(at(10, 0), at(20, 0));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		publicReady(gridId, at(9, 23));    // 시작 전
		publicReady(gridId, at(20, 1));    // 종료 후
		publicReady(gridId, at(15, 12));

		List<MissionVideoCountProjection> rows = count(mission);

		assertThat(rows).hasSize(1);
		assertThat(countOf(rows, gridId)).isEqualTo(1);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("기간이 없는 미션은 과거 영상도 센다 — IS NULL OR 자연 생략 (코스·지속형)")
	void 기간이_없는_미션은_과거_영상도_센다() {
		long mission = insertMission(null, null);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		insertVideo(gridId, LocalDateTime.of(2001, 3, 4, 5, 6), "ACTIVE", "PUBLIC", "READY");
		publicReady(gridId, at(20, 18));

		assertThat(countOf(count(mission), gridId)).isEqualTo(2);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("영상이 없는 격자는 COUNT 결과에 행이 없다 — 0 채움은 서비스 몫")
	void 영상이_없는_격자는_COUNT_결과에_행이_없다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String filled = seedGrid(0);
		String empty = seedGrid(1);
		insertMissionGrid(mission, filled);
		insertMissionGrid(mission, empty);
		publicReady(filled, at(10, 9));

		List<MissionVideoCountProjection> rows = count(mission);

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getGridId()).isEqualTo(filled);
	}

	private List<MissionVideoCountProjection> count(long missionId) {
		em.flush();
		em.clear();
		return videoRepository.countMissionVideosByGrid(missionId);
	}

	private long countOf(List<MissionVideoCountProjection> rows, String gridId) {
		return rows.stream()
			.filter(row -> row.getGridId().equals(gridId))
			.map(MissionVideoCountProjection::getVideoCount)
			.findFirst()
			.orElseThrow();
	}

	private static LocalDateTime at(int day, int hour) {
		return LocalDateTime.of(2026, 7, day, hour, 0, 0);
	}

	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private long insertMission(LocalDateTime startAt, LocalDateTime endAt) {
		String title = "MSG399-count-" + System.nanoTime();
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count)
				VALUES ('EVENT', :title, :startAt, :endAt, 1)
				""")
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	private void insertMissionGrid(long missionId, String gridId) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:missionId, :gridId)")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.executeUpdate();
	}

	/** 후보 술어 전 조건(ACTIVE·PUBLIC·READY)을 만족하는 영상. */
	private void publicReady(String gridId, LocalDateTime recordedAt) {
		insertVideo(gridId, recordedAt, "ACTIVE", "PUBLIC", "READY");
	}

	private void insertVideo(String gridId, LocalDateTime recordedAt,
		String status, String visibility, String processingStatus) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at,
					status, visibility, processing_status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.88, 37.51), 4326)::geography,
					10, :recordedAt, :status, :visibility, :processingStatus
				)
				""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("recordedAt", recordedAt)
			.setParameter("status", status)
			.setParameter("visibility", visibility)
			.setParameter("processingStatus", processingStatus)
			.executeUpdate();
	}
}
