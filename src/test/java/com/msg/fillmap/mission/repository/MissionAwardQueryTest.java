package com.msg.fillmap.mission.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.EventVideoFixtures;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 미션 판정 native 쿼리 검증 (실 PostGIS, MSG-223 모듈 2). 후보 역조회(활성·미발급 술어 — FR-18 전제)와
 * 단일 판정 쿼리(distinct 격자 카운트·기간·삭제 제외 — FR-12·13)를 유형 무분기로 본다. 시각은 전부
 * UTC 순간(§D3) — 기간 시드는 UTC now 기준 상대값, KST 경계는 UTC 저장값으로 검증한다.
 *
 * 격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백. 후보 단언은 내 미션 id 기준으로
 * 스코프하고, isEmpty 단언이 필요한 격자는 한국 밖 합성 인덱스(+7000)를 써서 시드 데이터와 못 겹치게 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("MissionRepository 판정 쿼리 (후보 역조회·단일 판정, 실 PostGIS)")
class MissionAwardQueryTest {

	// 다른 테스트(성수·잠실·뚝섬·망원)와 겹치지 않는 기준점 (연희동).
	private static final double 연희_LAT = 37.5686;
	private static final double 연희_LON = 126.9292;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long userId;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		String email = "mission-award-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "판정쿼리테스터")).getId();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(연희_LAT, 연희_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("미션 대상이 아닌 격자는 후보가 없다 — 조기 종료 전제(FR-18)")
	void 미션_대상이_아닌_격자는_후보가_없다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		insertMissionGrid(mission, GridFixtures.gridId(baseY, baseX));

		// 어떤 미션도 참조하지 않는 한국 밖 합성 격자 — 시드 미션과도 못 겹친다.
		assertThat(candidates(GridFixtures.gridId(baseY + 7000, baseX))).isEmpty();
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("기간이 끝난 미션은 후보에서 제외된다")
	void 기간이_끝난_미션은_후보에서_제외된다() {
		String gridId = GridFixtures.gridId(baseY, baseX);
		long expired = insertMission(nowUtc().minusDays(2), nowUtc().minusDays(1), 1);
		insertMissionGrid(expired, gridId);

		assertThat(candidates(gridId)).doesNotContain(expired);
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("시작 전 미션은 후보에서 제외된다")
	void 시작_전_미션은_후보에서_제외된다() {
		String gridId = GridFixtures.gridId(baseY, baseX);
		long future = insertMission(nowUtc().plusDays(1), nowUtc().plusDays(2), 1);
		insertMissionGrid(future, gridId);

		assertThat(candidates(gridId)).doesNotContain(future);
	}

	// 검증: FR-MISSION-04
	@Test
	@DisplayName("이미 스탬프를 받은 미션은 후보에서 제외된다 — 중복 판정 차단")
	void 이미_스탬프를_받은_미션은_후보에서_제외된다() {
		String gridId = GridFixtures.gridId(baseY, baseX);
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		insertMissionGrid(mission, gridId);
		assertThat(candidates(gridId)).contains(mission);

		insertStamp(userId, mission);

		assertThat(candidates(gridId)).doesNotContain(mission);
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("기간 내 서로 다른 격자 target_count 곳이면 충족된다 (FR-13)")
	void 기간_내_서로_다른_격자_target_count곳이면_충족된다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 2);
		String first = seedGrid(0);
		String second = seedGrid(1);
		insertMissionGrid(mission, first);
		insertMissionGrid(mission, second);
		seedVideo(userId, first, nowUtc(), "ACTIVE");
		seedVideo(userId, second, nowUtc(), "ACTIVE");

		List<CompletedMissionProjection> completed = missionRepository.findCompleted(userId, List.of(mission));

		assertThat(completed).hasSize(1);
		assertThat(completed.get(0).getMissionId()).isEqualTo(mission);
		assertThat(completed.get(0).getType()).isEqualTo("EVENT");
		assertThat(completed.get(0).getTitle()).isNotBlank();
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("같은 격자 재방문은 distinct 라 진행이 늘지 않는다 — target_count 는 서로 다른 격자 수(FR-13)")
	void 같은_격자_재방문은_distinct라_진행이_늘지_않는다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 2);
		String first = seedGrid(0);
		insertMissionGrid(mission, first);
		insertMissionGrid(mission, seedGrid(1));
		seedVideo(userId, first, nowUtc(), "ACTIVE");
		seedVideo(userId, first, nowUtc(), "ACTIVE");

		assertThat(missionRepository.findCompleted(userId, List.of(mission))).isEmpty();
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("기간 밖 recorded_at 영상은 판정에서 제외된다 — 작년 촬영 오발급 차단(FR-12)")
	void 기간_밖_recorded_at_영상은_판정에서_제외된다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(userId, gridId, nowUtc().minusYears(1), "ACTIVE");

		assertThat(missionRepository.findCompleted(userId, List.of(mission))).isEmpty();
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("무기간 미션은 과거 영상도 인정한다 — 코스, start/end NULL(FR-12)")
	void 무기간_미션은_과거_영상도_인정한다() {
		long mission = insertMission(null, null, 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(userId, gridId, nowUtc().minusYears(2), "ACTIVE");

		assertThat(missionRepository.findCompleted(userId, List.of(mission)))
			.extracting(CompletedMissionProjection::getMissionId)
			.containsExactly(mission);
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("삭제된 영상은 판정 근거에서 제외된다 (D2)")
	void 삭제된_영상은_판정_근거에서_제외된다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(userId, gridId, nowUtc(), "DELETED");

		assertThat(missionRepository.findCompleted(userId, List.of(mission))).isEmpty();
	}

	// 검증: FR-MISSION-03
	@Test
	@DisplayName("KST 자정 경계 영상이 UTC 저장 기간으로 옳게 판정된다 (D3)")
	void KST_자정_경계_영상이_UTC_저장_기간으로_옳게_판정된다() {
		// 축제 7/1~7/5 KST 를 시더가 UTC 로 변환해 넣은 값(§D3) — 판정은 순수 순간 비교만 한다.
		long mission = insertMission(
			LocalDateTime.of(2026, 6, 30, 15, 0, 0), LocalDateTime.of(2026, 7, 5, 14, 59, 59), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);

		// 6/30 23:50 KST(=6/30 14:50 UTC) 촬영 — 축제 전날 밤이라 불인정.
		seedVideo(userId, gridId, LocalDateTime.of(2026, 6, 30, 14, 50, 0), "ACTIVE");
		assertThat(missionRepository.findCompleted(userId, List.of(mission))).isEmpty();

		// 7/1 00:10 KST(=6/30 15:10 UTC) 촬영 — 축제 첫날 자정 직후라 인정.
		seedVideo(userId, gridId, LocalDateTime.of(2026, 6, 30, 15, 10, 0), "ACTIVE");
		assertThat(missionRepository.findCompleted(userId, List.of(mission)))
			.extracting(CompletedMissionProjection::getMissionId)
			.containsExactly(mission);
	}

	// 검증: FR-EVENT-12
	@Test
	@DisplayName("행사 영상만으로는 미션이 완료되지 않는다 (MSG-450)")
	void 행사_영상만으로는_미션이_완료되지_않는다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		EventVideoFixtures.linkEventVideo(em, seedVideo(userId, gridId, nowUtc(), "ACTIVE"));

		assertThat(missionRepository.findCompleted(userId, List.of(mission))).isEmpty();

		// 같은 격자에 일반 영상이 하나 들어오면 그때 충족된다 — 빠지는 것은 행사 영상뿐이다.
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");
		assertThat(missionRepository.findCompleted(userId, List.of(mission)))
			.extracting(CompletedMissionProjection::getMissionId)
			.containsExactly(mission);
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private List<Long> candidates(String gridId) {
		return missionRepository.findAwardCandidateIds(gridId, userId);
	}

	/** (baseY + offset, baseX) 셀을 grids 에 시드하고 grid_id 를 돌려준다 — videos FK 용. */
	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private long insertMission(LocalDateTime startAt, LocalDateTime endAt, int targetCount) {
		String title = "MSG223-award-" + System.nanoTime();
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count)
				VALUES ('EVENT', :title, :startAt, :endAt, :targetCount)
				""")
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.setParameter("targetCount", targetCount)
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

	private void insertStamp(long stampUserId, long missionId) {
		em.createNativeQuery("INSERT INTO user_missions (user_id, mission_id) VALUES (:userId, :missionId)")
			.setParameter("userId", stampUserId)
			.setParameter("missionId", missionId)
			.executeUpdate();
	}

	private long seedVideo(long ownerId, String gridId, LocalDateTime recordedAt, String status) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.92, 37.56), 4326)::geography,
					10, :recordedAt, :status
				)
				""")
			.setParameter("userId", ownerId)
			.setParameter("gridId", gridId)
			.setParameter("recordedAt", recordedAt)
			.setParameter("status", status)
			.executeUpdate();
		// 같은 커넥션의 직전 시퀀스 값 = 방금 넣은 영상 id (native INSERT 라 엔티티 id 를 못 받는다).
		return ((Number) em.createNativeQuery("SELECT lastval()").getSingleResult()).longValue();
	}
}
