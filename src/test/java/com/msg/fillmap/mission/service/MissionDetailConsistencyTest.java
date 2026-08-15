package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto.SpotStats;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 상세 집계와 MSG-390 목록의 술어 동등 정합 (실 PostGIS, MSG-399 §정합 테스트). 화면 상단 영상 개수와
 * 영상 목록의 실제 후보 수가 같은 데이터에서 항상 같음을 고정한다 — 목록의 첫 페이지만 비교하지 않고
 * 리포지토리의 후보 행 전체(모든 후보를 담는 limit)를 직접 대조해 페이지 크기(기본 20) 때문에 거짓
 * 통과하지 않게 한다. 진행도·스탬프 쪽 정합(filledCount = 방문 스팟 수, 비회수 completed)도 여기서 본다.
 *
 * <p>격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백한다. 집계에 사용자 조건이
 * 없어 격자는 시드 데이터와 겹치지 않는 한국 밖 합성 인덱스(+7000)를 쓴다(MissionVideoListQueryTest 선례).
 */
@SpringBootTest
@Transactional
@DisplayName("미션 상세 ↔ MSG-390 목록 정합 (실 PostGIS)")
class MissionDetailConsistencyTest {

	private static final double 서교_LAT = 37.5531;
	private static final double 서교_LON = 126.9187;

	// 시드·타 테스트와 못 겹치는 합성 격자 오프셋 (한국 밖).
	private static final long SYNTHETIC_OFFSET = 7000L;

	/** MSG-390 목록의 기본 페이지 크기 — 이보다 많은 후보를 만들어야 첫 페이지 비교가 거짓 통과하지 않는다. */
	private static final int LIST_DEFAULT_PAGE_SIZE = 20;

	/** 모든 후보를 담을 수 있는 조회 크기 — 후보 행 전체를 대조한다(§정합 테스트). */
	private static final int ALL_CANDIDATES = 1000;

	@Autowired
	private MissionQueryService missionQueryService;

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
		userId = newUser();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(서교_LAT, 서교_LON));
		baseY = base.gridY() + SYNTHETIC_OFFSET;
		baseX = base.gridX();
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("같은 데이터에서 MSG390 목록 전체 건수와 상세 videoCount가 같다 — 페이지 크기 너머까지")
	void 같은_데이터에서_MSG390_목록_전체_건수와_상세_videoCount가_같다() {
		long mission = insertMission("EVENT", nowUtc().minusDays(10), nowUtc().plusDays(10), 1);
		String first = seedGrid(0);
		String second = seedGrid(1);
		insertMissionGrid(mission, first, null);
		insertMissionGrid(mission, second, null);
		// 기본 페이지 크기(20)를 넘는 후보 23건 — 첫 페이지만 세는 구현이면 여기서 어긋난다.
		for (int i = 0; i < 23; i++) {
			insertVideo(userId, i % 2 == 0 ? first : second, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		}
		insertVideo(userId, first, nowUtc(), "ACTIVE", "PRIVATE", "READY");            // 게이트 탈락
		insertVideo(userId, second, nowUtc(), "ACTIVE", "PUBLIC", "ENCODING");         // 게이트 탈락
		insertVideo(userId, first, nowUtc().minusDays(30), "ACTIVE", "PUBLIC", "READY"); // 기간 밖

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);
		List<Video> candidates = allCandidates(mission);

		assertThat(candidates).hasSizeGreaterThan(LIST_DEFAULT_PAGE_SIZE);
		assertThat(detail.videoCount()).isEqualTo(candidates.size());
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("각 코스 스팟의 MSG390 후보 건수와 spotStats videoCount가 같다")
	void 각_코스_스팟의_MSG390_후보_건수와_spotStats_videoCount가_같다() {
		long mission = insertMission("COURSE", null, null, 3);
		String firstSpot = seedGrid(0);
		String secondSpot = seedGrid(1);
		String thirdSpot = seedGrid(2);
		insertMissionGrid(mission, firstSpot, 1);
		insertMissionGrid(mission, secondSpot, 2);
		insertMissionGrid(mission, thirdSpot, 3);
		insertVideo(userId, firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(newUser(), firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(newUser(), firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(newUser(), thirdSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(newUser(), thirdSpot, nowUtc(), "ACTIVE", "FRIENDS", "READY");     // 게이트 탈락
		insertVideo(userId, secondSpot, nowUtc(), "DELETED", "PUBLIC", "READY");       // 게이트 탈락

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);
		Map<String, Long> candidateCountByGrid = allCandidates(mission).stream()
			.collect(Collectors.groupingBy(Video::getGridId, Collectors.counting()));

		assertThat(detail.spotStats()).hasSize(3);
		assertThat(detail.spotStats()).allSatisfy(spot ->
			assertThat(spot.videoCount()).isEqualTo(candidateCountByGrid.getOrDefault(spot.gridId(), 0L)));
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("상세 filledCount는 방문한 스팟 수와 같고 targetCount를 넘지 않는다 — LEAST 캡")
	void 상세_filledCount는_방문한_스팟_수와_같고_targetCount를_넘지_않는다() {
		long mission = insertMission("COURSE", null, null, 2);
		String firstSpot = seedGrid(0);
		String secondSpot = seedGrid(1);
		String thirdSpot = seedGrid(2);
		insertMissionGrid(mission, firstSpot, 1);
		insertMissionGrid(mission, secondSpot, 2);
		insertMissionGrid(mission, thirdSpot, 3);
		insertVideo(userId, firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(userId, secondSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");

		MissionDetailResponseDto twoVisited = missionQueryService.getMissionDetail(mission, userId);

		// 방문 2 < 목표 2 이하 — filledCount = 방문한 스팟 수.
		assertThat(visitedCount(twoVisited)).isEqualTo(2);
		assertThat(twoVisited.progress().filledCount()).isEqualTo(2);

		// 세 번째 스팟까지 방문하면 방문 스팟은 3인데 filledCount 는 targetCount(2)에서 캡된다.
		insertVideo(userId, thirdSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		MissionDetailResponseDto threeVisited = missionQueryService.getMissionDetail(mission, userId);

		assertThat(visitedCount(threeVisited)).isEqualTo(3);
		assertThat(threeVisited.progress().filledCount()).isEqualTo(2);
		assertThat(threeVisited.progress().filledCount()).isLessThanOrEqualTo(threeVisited.progress().targetCount());
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("영상을 지워 진행도가 줄어도 스탬프가 있으면 completed는 true다 — 비회수 (FR-MISSION-04)")
	void 영상을_지워_진행도가_줄어도_스탬프가_있으면_completed는_true다() {
		// 소프트 삭제가 끝난 상태 — 영상은 DELETED 로 남고 user_missions 만 유효하다.
		long mission = insertMission("COURSE", null, null, 1);
		String spot = seedGrid(0);
		insertMissionGrid(mission, spot, 1);
		insertVideo(userId, spot, nowUtc(), "DELETED", "PUBLIC", "READY");
		insertStamp(userId, mission);

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);

		assertThat(detail.progress().filledCount()).isZero();
		assertThat(detail.progress().completed()).isTrue();
		// 마지막 영상을 지운 스팟은 방문하지 않은 것으로 바뀌고(§도메인), 삭제 영상은 개수에서도 빠진다.
		assertThat(visitedCount(detail)).isZero();
		assertThat(detail.videoCount()).isZero();
	}

	private static long visitedCount(MissionDetailResponseDto detail) {
		return detail.spotStats().stream().filter(SpotStats::visited).count();
	}

	/** MSG-390 목록 쿼리의 후보 행 전체 — 모든 후보를 담는 limit 으로 읽는다. */
	private List<Video> allCandidates(long missionId) {
		em.flush();
		em.clear();
		return videoRepository.findMissionVideos(missionId, ALL_CANDIDATES);
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private long newUser() {
		String email = "mission-consistency-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "정합테스터")).getId();
	}

	/** (baseY + offset, baseX) 셀을 grids 에 시드하고 grid_id 를 돌려준다 — videos FK 용. */
	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private long insertMission(String type, LocalDateTime startAt, LocalDateTime endAt, int targetCount) {
		String title = "MSG399-consistency-" + System.nanoTime();
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count)
				VALUES (:type, :title, :startAt, :endAt, :targetCount)
				""")
			.setParameter("type", type)
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.setParameter("targetCount", targetCount)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	/** seq — 코스 포토스팟 순번(1..N), 순서 없는 유형은 null. */
	private void insertMissionGrid(long missionId, String gridId, Integer seq) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id, seq) VALUES (:missionId, :gridId, :seq)")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.setParameter("seq", seq)
			.executeUpdate();
	}

	private void insertStamp(long stampUserId, long missionId) {
		em.createNativeQuery("INSERT INTO user_missions (user_id, mission_id) VALUES (:userId, :missionId)")
			.setParameter("userId", stampUserId)
			.setParameter("missionId", missionId)
			.executeUpdate();
	}

	/** videos.geom NOT NULL — 픽스처에 지오메트리 포함 필수 (MissionVideoListQueryTest.insertVideo 선례). */
	private void insertVideo(long ownerId, String gridId, LocalDateTime recordedAt,
		String status, String visibility, String processingStatus) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at,
					status, visibility, processing_status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.91, 37.55), 4326)::geography,
					10, :recordedAt, :status, :visibility, :processingStatus
				)
				""")
			.setParameter("userId", ownerId)
			.setParameter("gridId", gridId)
			.setParameter("recordedAt", recordedAt)
			.setParameter("status", status)
			.setParameter("visibility", visibility)
			.setParameter("processingStatus", processingStatus)
			.executeUpdate();
	}
}
