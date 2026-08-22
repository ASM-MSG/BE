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
 * 진행도 native 쿼리 검증 (실 PostgreSQL, MSG-398 D8·D9·D11). 채운 칸 = 미션 기간 안에 촬영한 내 영상이
 * 있는 서로 다른 격자 수(LEAST 로 목표 캡) — videos 조인 조건이 판정 쿼리(findCompleted)와 글자 단위로
 * 같음을 정합 케이스로 고정한다(2026-08-15 성민 확정으로 user_grids 교집합 정의를 대체). 스탬프는
 * user_missions LEFT JOIN 이다.
 *
 * 격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백. 단언은 내 미션 id 로 스코프한다.
 */
@SpringBootTest
@Transactional
@DisplayName("MissionRepository 진행도 쿼리 (기간 내 영상 집계·스탬프, 실 PostgreSQL)")
class MissionProgressQueryTest {

	// 다른 테스트(연희·성수·잠실 등)와 겹치지 않는 기준점 (합정).
	private static final double 합정_LAT = 37.5496;
	private static final double 합정_LON = 126.9139;

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
		userId = newUser();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(합정_LAT, 합정_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("기간 내 촬영 영상이 있는 격자 수가 나온다")
	void 기간_내_촬영_영상이_있는_격자_수가_나온다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 3);
		String first = seedGrid(0);
		String second = seedGrid(1);
		insertMissionGrid(mission, first);
		insertMissionGrid(mission, second);
		insertMissionGrid(mission, seedGrid(2));
		seedVideo(userId, first, nowUtc(), "ACTIVE");
		seedVideo(userId, second, nowUtc(), "ACTIVE");

		MissionProgressProjection row = single(mission);

		assertThat(row.getTargetCount()).isEqualTo(3);
		assertThat(row.getFilledCount()).isEqualTo(2);
		assertThat(row.getCompleted()).isFalse();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("같은 격자의 재방문 영상 여러 개는 한 칸으로 센다 — DISTINCT를 지우면 깨진다")
	void 같은_격자의_재방문_영상_여러_개는_한_칸으로_센다() {
		// videos 조인은 재방문으로 팬아웃한다 — COUNT(DISTINCT v.grid_id) 가 아니면 3/3 이 나간다 (D8).
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 3);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		insertMissionGrid(mission, seedGrid(1));
		insertMissionGrid(mission, seedGrid(2));
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");

		assertThat(single(mission).getFilledCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("삭제 영상은 세지 않고 블라인드 영상은 센다 — 판정 술어와 같은 status 조건")
	void 삭제_영상은_세지_않고_블라인드_영상은_센다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 3);
		String deleted = seedGrid(0);
		String blinded = seedGrid(1);
		insertMissionGrid(mission, deleted);
		insertMissionGrid(mission, blinded);
		insertMissionGrid(mission, seedGrid(2));
		seedVideo(userId, deleted, nowUtc(), "DELETED");
		// BLINDED 는 촬영 사실이 유효해 포함 — findCompleted 와 같은 status <> 'DELETED' 하나만 거른다.
		seedVideo(userId, blinded, nowUtc(), "BLINDED");

		assertThat(single(mission).getFilledCount()).isEqualTo(1);
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("하나도 진행하지 않은 미션은 0으로 나온다 — 실패가 아니다")
	void 하나도_진행하지_않은_미션은_0으로_나온다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		insertMissionGrid(mission, seedGrid(0));

		MissionProgressProjection row = single(mission);

		assertThat(row.getFilledCount()).isZero();
		assertThat(row.getCompleted()).isFalse();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("채운 칸 수는 목표를 넘지 않는다 — 81칸 축제에서 5칸을 채워도 1/1")
	void 채운_칸_수는_목표를_넘지_않는다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		for (int offset = 0; offset < 5; offset++) {
			String gridId = seedGrid(offset);
			insertMissionGrid(mission, gridId);
			seedVideo(userId, gridId, nowUtc(), "ACTIVE");
		}

		MissionProgressProjection row = single(mission);

		assertThat(row.getTargetCount()).isEqualTo(1);
		assertThat(row.getFilledCount()).isEqualTo(1);
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("내가 아닌 사용자의 영상은 세지 않는다")
	void 내가_아닌_사용자의_영상은_세지_않는다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(newUser(), gridId, nowUtc(), "ACTIVE");

		assertThat(single(mission).getFilledCount()).isZero();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("스탬프가 있으면 완료로 나온다")
	void 스탬프가_있으면_완료로_나온다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");
		insertStamp(userId, mission);

		MissionProgressProjection row = single(mission);

		assertThat(row.getFilledCount()).isEqualTo(1);
		assertThat(row.getCompleted()).isTrue();
	}

	// 검증: FR-MISSION-04
	@Test
	@DisplayName("영상을 모두 지우면 진행도는 0이지만 완료는 남는다 — 스탬프 비회수")
	void 영상을_모두_지우면_진행도는_0이지만_완료는_남는다() {
		// 소프트 삭제가 끝난 상태 — 영상은 DELETED 로 남고 user_missions 만 유효하다.
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(userId, gridId, nowUtc(), "DELETED");
		insertStamp(userId, mission);

		MissionProgressProjection row = single(mission);

		assertThat(row.getFilledCount()).isZero();
		assertThat(row.getCompleted()).isTrue();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("기간이 끝난 미션도 진행도가 나온다 — 활성 조건이 없다 (D11)")
	void 기간이_끝난_미션도_진행도가_나온다() {
		long ended = insertMission(nowUtc().minusDays(5), nowUtc().minusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(ended, gridId);
		// 기간 안에 찍은 영상은 미션 종료 뒤에도 그대로 세어진다.
		seedVideo(userId, gridId, nowUtc().minusDays(3), "ACTIVE");

		assertThat(single(ended).getFilledCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("존재하지 않는 미션 id는 결과에서 빠진다 — 오류가 아니다")
	void 존재하지_않는_미션_id는_결과에서_빠진다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		insertMissionGrid(mission, seedGrid(0));

		List<MissionProgressProjection> rows =
			missionRepository.findProgress(userId, List.of(mission, 999_999_999L));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getMissionId()).isEqualTo(mission);
	}

	@Test
	@DisplayName("격자가 없는 미션도 0으로 나온다")
	void 격자가_없는_미션도_0으로_나온다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);

		MissionProgressProjection row = single(mission);

		assertThat(row.getFilledCount()).isZero();
		assertThat(row.getCompleted()).isFalse();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("한 격자를 두 미션이 공유해도 각각 세어진다")
	void 한_격자를_두_미션이_공유해도_각각_세어진다() {
		String shared = seedGrid(0);
		long first = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		insertMissionGrid(first, shared);
		long second = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		insertMissionGrid(second, shared);
		seedVideo(userId, shared, nowUtc(), "ACTIVE");

		List<MissionProgressProjection> rows = missionRepository.findProgress(userId, List.of(first, second));

		assertThat(rows).hasSize(2);
		assertThat(rows).allSatisfy(row -> assertThat(row.getFilledCount()).isEqualTo(1));
	}

	@Test
	@DisplayName("응답은 missionId 오름차순이다 — 요청 순서를 보존하지 않는다")
	void 응답은_missionId_오름차순이다() {
		long first = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		long second = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		long third = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);

		List<MissionProgressProjection> rows =
			missionRepository.findProgress(userId, List.of(third, first, second));

		assertThat(rows).extracting(MissionProgressProjection::getMissionId)
			.containsExactly(first, second, third);
	}

	@Test
	@DisplayName("기간 밖에 촬영한 영상은 진행도에도 스탬프 판정에도 세지 않는다 — 두 술어의 일치 고정 (D8)")
	void 기간_밖에_촬영한_영상은_진행도에도_스탬프_판정에도_세지_않는다() {
		// 미션 시작 전에 그 격자에서 찍은 영상만 있으면 진행도 0, 스탬프 없음. 이 케이스가 깨지면 두 술어가
		// 조용히 갈라진 것이다 — 2026-08-15 전환 전(도감 기준)에는 진행도만 1이 나오는 것이 기대값이었다.
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(userId, gridId, nowUtc().minusYears(1), "ACTIVE");

		MissionProgressProjection progress = single(mission);

		assertThat(progress.getFilledCount()).isZero();
		assertThat(progress.getCompleted()).isFalse();
		assertThat(missionRepository.findCompleted(userId, List.of(mission))).isEmpty();
	}

	// 검증: FR-EVENT-12
	@Test
	@DisplayName("행사 영상은 미션 진행도에 세어지지 않는다 — 격자 방문 증거가 아니다 (MSG-450)")
	void 행사_영상은_미션_진행도에_세어지지_않는다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1), 3);
		String eventOnly = seedGrid(0);
		String mixed = seedGrid(1);
		insertMissionGrid(mission, eventOnly);
		insertMissionGrid(mission, mixed);
		EventVideoFixtures.linkEventVideo(em, seedVideo(userId, eventOnly, nowUtc(), "ACTIVE"));
		EventVideoFixtures.linkEventVideo(em, seedVideo(userId, mixed, nowUtc(), "ACTIVE"));
		seedVideo(userId, mixed, nowUtc(), "ACTIVE");   // 같은 격자의 일반 영상 — 이 한 칸만 세어진다

		assertThat(single(mission).getFilledCount()).isEqualTo(1);
	}

	private MissionProgressProjection single(long missionId) {
		List<MissionProgressProjection> rows = missionRepository.findProgress(userId, List.of(missionId));
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private long newUser() {
		String email = "mission-progress-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "진행도테스터")).getId();
	}

	/** (baseY + offset, baseX) 셀을 grids 에 시드하고 grid_id 를 돌려준다 — videos FK 용. */
	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private long insertMission(LocalDateTime startAt, LocalDateTime endAt, int targetCount) {
		String title = "MSG398-progress-" + System.nanoTime();
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

	/** videos.geom NOT NULL — 픽스처에 지오메트리 포함 필수 (MissionAwardQueryTest.seedVideo 선례). */
	private long seedVideo(long ownerId, String gridId, LocalDateTime recordedAt, String status) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.91, 37.55), 4326)::geography,
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
